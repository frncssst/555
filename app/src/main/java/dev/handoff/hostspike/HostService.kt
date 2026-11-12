package dev.handoff.hostspike

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * The host agent: a foreground service that watches calls, texts and the SIM, and keeps a heartbeat
 * so a 48-hour run shows whether the phone ever stopped the app.
 */
class HostService : Service() {

    companion object {
        private const val NOTIF_STATUS = 1
        private const val NOTIF_CALL = 2
        private const val CH_STATUS = "status"
        private const val CH_CALL = "call"
        private const val HEARTBEAT_MS = 15 * 60 * 1000L
        private const val LATE_GAP_MIN = 20.0
        private const val HOST_CONFIRM_MS = 2 * 60 * 1000L
        private const val ACTION_BEAT = "dev.handoff.hostspike.HEARTBEAT"

        @Volatile var running = false
        @Volatile var lastHeartbeatLabel: String = "none yet"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, HostService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HostService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var server: CommandServer? = null
    private var callCallback: TelephonyCallback? = null
    private var subsListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var watcher: MessageWatcher? = null
    private var lastBeat = 0L
    private var lastSimSignature: String? = null

    // Host status after filtering out short blips.
    private var stableHost: Boolean? = null
    private var stableHostKnown = false
    private var unknownSince = 0L
    private val confirmHost = Runnable { confirmHostChange() }

    // Heartbeat uses an alarm that Android allows during Doze, instead of an in-app timer.
    private val beatReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            beat("alarm")
            scheduleBeat()
        }
    }

    private val autoAction = Runnable { runAutoAction() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = statusNotification("Starting")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
        running = true
        EventLog.log(
            this, "service_started",
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        registerCallState()
        registerSims()
        registerMessages()
        server = CommandServer(applicationContext, Prefs.PORT, Prefs.token(this)).also { it.start() }
        registerReceiver(beatReceiver, IntentFilter(ACTION_BEAT), Context.RECEIVER_NOT_EXPORTED)
        beat("startup")
        scheduleBeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        getSystemService(AlarmManager::class.java).cancel(beatIntent())
        try {
            unregisterReceiver(beatReceiver)
        } catch (_: Exception) {
        }
        callCallback?.let { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) }
        subsListener?.let { getSystemService(SubscriptionManager::class.java).removeOnSubscriptionsChangedListener(it) }
        watcher?.let { contentResolver.unregisterContentObserver(it) }
        server?.stop()
        bgThread?.quitSafely()
        running = false
        EventLog.log(this, "service_stopped")
        super.onDestroy()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---- Calls ----

    private fun registerCallState() {
        if (!has(Manifest.permission.READ_PHONE_STATE)) {
            EventLog.log(this, "permission_missing", "permission" to "READ_PHONE_STATE", "effect" to "call state not monitored")
            return
        }
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handler.post { onCallState(state) }
            }
        }
        getSystemService(TelephonyManager::class.java).registerTelephonyCallback(mainExecutor, cb)
        callCallback = cb
    }

    private fun onCallState(state: Int) {
        val prev = CallState.state
        if (state == prev) return
        CallState.state = state
        val now = System.currentTimeMillis()
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                CallState.ringingAt = now
                val number = CallState.screenedNumber.takeIf { now - CallState.screenedAt < 15_000 }
                EventLog.log(this, "call_ringing", "number" to (number ?: "unknown (screening not invoked)"))
                showCallNotification(number)
                scheduleAutoAction()
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                handler.removeCallbacks(autoAction)
                cancelCallNotification()
                if (prev == TelephonyManager.CALL_STATE_RINGING) {
                    val cmd = CallState.commandDuringRinging()
                    EventLog.log(
                        this, "call_answered",
                        "ms_from_ringing" to (now - CallState.ringingAt),
                        "trigger" to (cmd ?: "none"),
                        "ms_from_command" to if (cmd != null) now - CallState.commandAt else null
                    )
                } else {
                    EventLog.log(this, "call_outgoing_started")
                }
            }
            else -> {
                handler.removeCallbacks(autoAction)
                cancelCallNotification()
                if (prev == TelephonyManager.CALL_STATE_RINGING) {
                    val cmd = CallState.commandDuringRinging()
                    EventLog.log(
                        this, "call_ended_while_ringing",
                        "ms_from_ringing" to (now - CallState.ringingAt),
                        "trigger" to (cmd ?: "none (caller hung up, user declined, or no-answer forward)"),
                        "ms_from_command" to if (cmd != null) now - CallState.commandAt else null
                    )
                } else if (prev == TelephonyManager.CALL_STATE_OFFHOOK) {
                    EventLog.log(this, "call_ended")
                }
            }
        }
        updateStatusNotification()
    }

    private fun scheduleAutoAction() {
        val action = Prefs.autoAction(this)
        if (action == "none") return
        handler.removeCallbacks(autoAction)
        handler.postDelayed(autoAction, Prefs.autoDelaySec(this) * 1000L)
    }

    private fun runAutoAction() {
        if (CallState.state != TelephonyManager.CALL_STATE_RINGING) return
        when (Prefs.autoAction(this)) {
            "answer" -> CallControl.answer(this, "auto")
            "decline" -> CallControl.decline(this, "auto")
        }
    }

    // ---- SIM ----

    private fun registerSims() {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return
        val l = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                handler.post { checkSims("listener") }
            }
        }
        getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(mainExecutor, l)
        subsListener = l
        checkSims("startup")
    }

    private fun checkSims(source: String) {
        val sims = SimInfo.snapshot(this)
        val sig = SimInfo.signature(sims)
        if (sig == lastSimSignature) return
        val first = lastSimSignature == null
        lastSimSignature = sig
        EventLog.log(
            this, if (first) "sims_initial" else "sims_changed",
            "source" to source,
            "count" to sims.size,
            "is_host" to (SimInfo.isHost(this, sims) ?: "unknown"),
            "sims" to SimInfo.toJson(sims)
        )
        evaluateHost()
        updateStatusNotification()
    }

    /**
     * The raw SIM events above include short blips (the number briefly missing, or no service).
     * Only a change between host and not-host that lasts two minutes counts. Unknown, such as no
     * service on a flight, keeps the current role and is logged as a period.
     */
    private fun evaluateHost() {
        val now = SimInfo.isHost(this, SimInfo.snapshot(this))
        val t = System.currentTimeMillis()
        if (!stableHostKnown) {
            stableHostKnown = true
            stableHost = now
            EventLog.log(this, "host_status", "is_host" to (now ?: "unknown"), "reason" to "startup")
            return
        }
        if (now == null) {
            if (unknownSince == 0L) {
                unknownSince = t
                EventLog.log(this, "host_unknown_started", "keeping_role" to (stableHost ?: "unknown"))
            }
            return
        }
        if (unknownSince != 0L) {
            EventLog.log(this, "host_unknown_ended", "duration_min" to Math.round((t - unknownSince) / 6000.0) / 10.0)
            unknownSince = 0L
        }
        if (now == stableHost) {
            handler.removeCallbacks(confirmHost)
            return
        }
        handler.removeCallbacks(confirmHost)
        handler.postDelayed(confirmHost, HOST_CONFIRM_MS)
    }

    private fun confirmHostChange() {
        val now = SimInfo.isHost(this, SimInfo.snapshot(this))
        if (now == null || now == stableHost) {
            EventLog.log(this, "host_blip_ignored", "is_host_now" to (now ?: "unknown"))
            return
        }
        EventLog.log(this, "host_changed", "from" to (stableHost ?: "unknown"), "to" to now)
        stableHost = now
    }

    // ---- Messages ----

    private fun registerMessages() {
        val t = HandlerThread("message-watcher").also { it.start() }
        bgThread = t
        val w = MessageWatcher(applicationContext, Handler(t.looper))
        Handler(t.looper).post { w.prime() }
        for (uri in listOf("content://sms", "content://mms", "content://mms-sms/")) {
            contentResolver.registerContentObserver(Uri.parse(uri), true, w)
        }
        watcher = w
    }

    // ---- Heartbeat ----

    private fun beatIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 10, Intent(ACTION_BEAT).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleBeat() {
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + HEARTBEAT_MS,
            beatIntent()
        )
    }

    private fun beat(source: String) {
        val now = System.currentTimeMillis()
        val gapMin = if (lastBeat == 0L) 0.0 else (now - lastBeat) / 60000.0
        lastBeat = now
        lastHeartbeatLabel = EventLog.format(now)
        val (pct, charging) = Status.battery(this)
        val pm = getSystemService(PowerManager::class.java)
        EventLog.log(
            this, "heartbeat",
            "gap_min" to Math.round(gapMin * 10) / 10.0,
            "late" to (gapMin > LATE_GAP_MIN),
            "battery_pct" to pct,
            "charging" to charging,
            "doze" to pm.isDeviceIdleMode,
            "battery_opt_exempt" to pm.isIgnoringBatteryOptimizations(packageName),
            "power_saver" to pm.isPowerSaveMode,
            "source" to source
        )
        checkSims("heartbeat")
        watcher?.requestScan("heartbeat")
        updateStatusNotification()
    }

    // ---- Notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Host status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, "Incoming call test actions", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun statusNotification(text: String): Notification =
        Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Host spike running")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun updateStatusNotification() {
        val host = SimInfo.isHost(this, SimInfo.snapshot(this))
        val hostLabel = when (host) {
            true -> "host"
            false -> "not host"
            null -> "host unknown"
        }
        val text = "$hostLabel · ${CallState.label()} · beat ${lastHeartbeatLabel.takeLast(12)}"
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, statusNotification(text))
    }

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        this, code, Intent(this, ActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun showCallNotification(number: String?) {
        val answer = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.sym_action_call), "Answer (app)",
            actionIntent(ActionReceiver.ACTION_ANSWER, 1)
        ).build()
        val decline = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Decline (app)",
            actionIntent(ActionReceiver.ACTION_DECLINE, 2)
        ).build()
        val n = Notification.Builder(this, CH_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Ringing: test the app's controls")
            .setContentText(number ?: "Caller number not seen by the app")
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(answer)
            .addAction(decline)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CALL, n)
    }

    private fun cancelCallNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_CALL)
    }
}
