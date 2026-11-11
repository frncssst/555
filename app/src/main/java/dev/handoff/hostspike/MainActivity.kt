package dev.handoff.hostspike

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val perms = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private lateinit var checksView: TextView
    private lateinit var statusView: TextView
    private lateinit var lanView: TextView
    private lateinit var summaryView: TextView
    private lateinit var logView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)
        }
    }
    private val logListener: () -> Unit = { handler.post { refreshLog() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.setup(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        root.addView(heading("Host spike", 22f))
        root.addView(para("Phase 1 test app for the main-phone host agent. It never blocks calls or changes your default apps."))

        root.addView(heading("1. Set up"))
        checksView = para("")
        root.addView(checksView)
        root.addView(button("Grant permissions") { requestPermissions(perms, 1) })
        root.addView(button("Allow call screening (to see caller numbers)") { requestScreeningRole() })
        root.addView(button("Exempt from battery optimisation") { requestBatteryExemption() })
        root.addView(button("Open app settings (restricted settings, OnePlus battery)") { openAppSettings() })

        val numberField = edit("Main mobile number, e.g. 0412 345 678", Prefs.mainNumber(this), InputType.TYPE_CLASS_PHONE)
        root.addView(numberField)
        root.addView(button("Save main number") {
            Prefs.setMainNumber(this, numberField.text.toString())
            toast("Saved")
            refresh()
        })
        root.addView(row(
            button("Start host service") {
                Prefs.setAutoStart(this, true)
                HostService.start(this)
                handler.postDelayed({ refresh() }, 500)
            },
            button("Stop") {
                Prefs.setAutoStart(this, false)
                HostService.stop(this)
                handler.postDelayed({ refresh() }, 500)
            }
        ))

        root.addView(heading("2. Remote control from another device"))
        lanView = para("")
        root.addView(lanView)

        root.addView(heading("3. Automatic action when a call rings"))
        root.addView(para("Lets you test answer and decline with one phone: call the main number from another phone and the app acts by itself."))
        val group = RadioGroup(this)
        val options = listOf("none" to "Do nothing", "answer" to "Answer automatically", "decline" to "Decline automatically (tests decline-to-forward)")
        val ids = HashMap<Int, String>()
        for ((key, label) in options) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                this.text = label
            }
            ids[rb.id] = key
            group.addView(rb)
            if (Prefs.autoAction(this) == key) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            ids[checkedId]?.let {
                Prefs.setAutoAction(this, it)
                EventLog.log(this, "setting_changed", "auto_action" to it)
            }
        }
        root.addView(group)
        val delayField = edit("Seconds after ringing starts", Prefs.autoDelaySec(this).toString(), InputType.TYPE_CLASS_NUMBER)
        root.addView(delayField)
        root.addView(button("Save delay") {
            Prefs.setAutoDelaySec(this, delayField.text.toString().toIntOrNull() ?: 3)
            delayField.setText(Prefs.autoDelaySec(this).toString())
            toast("Saved")
        })

        root.addView(heading("4. Send a test SMS from this phone"))
        val toField = edit("Recipient, e.g. 0412 345 678", "", InputType.TYPE_CLASS_PHONE)
        val bodyField = edit("Message", "Test from host spike", InputType.TYPE_CLASS_TEXT)
        root.addView(toField)
        root.addView(bodyField)
        root.addView(button("Send SMS") {
            toast(SmsSender.send(this, toField.text.toString(), bodyField.text.toString(), "app"))
        })

        root.addView(heading("Status"))
        statusView = mono("")
        root.addView(statusView)

        root.addView(heading("Results"))
        summaryView = mono("")
        root.addView(summaryView)
        root.addView(row(
            button("Save log") { saveLog() },
            button("Copy") { copyLog() },
            button("Clear") { confirmClear() }
        ))

        root.addView(heading("Event log (newest first)"))
        logView = mono("")
        root.addView(logView)

        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        EventLog.addListener(logListener)
        handler.post(refresher)
        refreshLog()
    }

    override fun onPause() {
        super.onPause()
        EventLog.removeListener(logListener)
        handler.removeCallbacks(refresher)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
        EventLog.log(this, "permissions_result", "denied" to denied.joinToString(",").ifEmpty { "none" })
        if (HostService.running) {
            // The service registers its listeners when it starts, so restart it to pick up new permissions.
            HostService.stop(this)
            handler.postDelayed({ HostService.start(this) }, 500)
        }
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refresh()
    }

    // ---- Actions ----

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            toast("Call screening role not available on this phone")
            return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            toast("Already the call screening app")
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 2)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already exempt")
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun saveLog() {
        val name = "host-spike-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("Could not create the file")
            return
        }
        contentResolver.openOutputStream(uri)?.use {
            val content = "SUMMARY\n" + Summary.build(this) + "\n\nEVENTS (one JSON object per line)\n" + EventLog.readAll(this)
            it.write(content.toByteArray())
        }
        toast("Saved to Downloads/$name")
    }

    private fun copyLog() {
        val content = Summary.build(this) + "\n\n" + EventLog.readAll(this)
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Host spike log", content.takeLast(200_000)))
        toast("Copied")
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear the event log?")
            .setMessage("Save it first if you need the results.")
            .setPositiveButton("Clear") { _, _ ->
                EventLog.clear(this)
                refresh()
                refreshLog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Refresh ----

    private fun refresh() {
        val lines = ArrayList<String>()
        for (p in perms) {
            val ok = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            lines.add((if (ok) "✓ " else "✗ ") + p.substringAfterLast('.'))
        }
        val rm = getSystemService(RoleManager::class.java)
        lines.add((if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) "✓ " else "✗ ") + "Call screening role")
        val pm = getSystemService(PowerManager::class.java)
        lines.add((if (pm.isIgnoringBatteryOptimizations(packageName)) "✓ " else "✗ ") + "Battery optimisation exemption")
        lines.add((if (Prefs.mainNumber(this).isNotBlank()) "✓ " else "✗ ") + "Main number saved")
        lines.add((if (HostService.running) "✓ " else "✗ ") + "Host service running")
        checksView.text = lines.joinToString("\n")

        statusView.text = try {
            Status.build(this).toString(2)
        } catch (e: Exception) {
            "Status unavailable: $e"
        }

        val ip = Status.lanAddress(this)
        val token = Prefs.token(this)
        lanView.text = if (!HostService.running) {
            "Start the host service first."
        } else if (ip == null) {
            "No network address. Connect this phone to Wi-Fi."
        } else {
            "On another device on the same Wi-Fi, open:\nhttp://$ip:${Prefs.PORT}/?token=$token\n\n" +
                "That page stands in for a linked device: answer, decline-to-forward and send SMS through this phone."
        }

        summaryView.text = Summary.build(this)
    }

    private fun refreshLog() {
        val lines = EventLog.recentLines().takeLast(80).reversed().map { pretty(it) }
        logView.text = lines.joinToString("\n").ifEmpty { "No events yet." }
    }

    private fun pretty(line: String): String = try {
        val o = JSONObject(line)
        val time = o.optString("time").let { if (it.length >= 19) it.substring(5, 19) else it }
        val type = o.optString("type")
        val extras = o.keys().asSequence()
            .filter { it != "t" && it != "time" && it != "type" }
            .joinToString(" ") { "$it=${o.opt(it)}" }
        "$time $type $extras".take(300)
    } catch (e: Exception) {
        line
    }

    // ---- Small view helpers ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(s: String, size: Float = 17f) = TextView(this).apply {
        text = s
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun para(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun mono(s: String) = TextView(this).apply {
        text = s
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun edit(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
