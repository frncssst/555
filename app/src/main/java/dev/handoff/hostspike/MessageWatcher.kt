package dev.handoff.hostspike

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler

/**
 * Watches the phone's shared message store for every text and picture message, in both directions,
 * whichever app sent it. A non-default app cannot download MMS itself, so this reads what Google
 * Messages has stored.
 *
 * Two lessons from the first runs are built in:
 * - Change notifications are not reliable on their own, so the store is also scanned on every heartbeat.
 * - An MMS row can appear before its parts are written, so incomplete MMS are read again a few times.
 *
 * Message text is never logged, only its length.
 */
class MessageWatcher(private val ctx: Context, private val handler: Handler) : ContentObserver(handler) {

    private var lastSmsId = -1L
    private var lastMmsId = -1L
    private var deniedLogged = false
    private val retryDelaysMs = longArrayOf(3_000, 10_000, 30_000, 120_000)

    fun prime() {
        lastSmsId = maxId("content://sms") ?: -1L
        lastMmsId = maxId("content://mms") ?: -1L
        EventLog.log(ctx, "message_watcher_started", "last_sms_id" to lastSmsId, "last_mms_id" to lastMmsId)
    }

    override fun onChange(selfChange: Boolean) {
        scan("observer")
    }

    /** Safe to call from any thread. */
    fun requestScan(trigger: String) {
        handler.post { scan(trigger) }
    }

    private fun scan(trigger: String) {
        if (lastSmsId < 0 && lastMmsId < 0) return
        scanSms(trigger)
        scanMms(trigger)
    }

    // ---- SMS ----

    private fun scanSms(trigger: String) {
        try {
            ctx.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id", "type", "address", "date", "body", "sub_id"),
                "_id > ?", arrayOf(lastSmsId.toString()), "_id ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    lastSmsId = maxOf(lastSmsId, id)
                    val type = c.getInt(1)
                    if (type == 3) continue // drafts
                    val date = c.getLong(3)
                    EventLog.log(
                        ctx, "sms_stored",
                        "id" to id,
                        "direction" to if (type == 1) "in" else "out",
                        "box" to smsBox(type),
                        "address" to c.getString(2),
                        "chars" to (c.getString(4)?.length ?: 0),
                        "sub_id" to c.getInt(5),
                        "ms_since_message_date" to if (date > 0) System.currentTimeMillis() - date else null,
                        "found_by" to trigger
                    )
                }
            }
        } catch (e: SecurityException) {
            logDenied(e)
        } catch (e: Exception) {
            EventLog.log(ctx, "messages_read_error", "table" to "sms", "error" to e.toString())
        }
    }

    private fun smsBox(type: Int) = when (type) {
        1 -> "inbox"
        2 -> "sent"
        4 -> "outbox"
        5 -> "failed"
        6 -> "queued"
        else -> "type_$type"
    }

    // ---- MMS ----

    private fun scanMms(trigger: String) {
        val newIds = ArrayList<Long>()
        try {
            ctx.contentResolver.query(
                Uri.parse("content://mms"), arrayOf("_id"),
                "_id > ?", arrayOf(lastMmsId.toString()), "_id ASC"
            )?.use { c ->
                while (c.moveToNext()) newIds.add(c.getLong(0))
            }
        } catch (e: SecurityException) {
            logDenied(e)
            return
        } catch (e: Exception) {
            EventLog.log(ctx, "messages_read_error", "table" to "mms", "error" to e.toString())
            return
        }
        for (id in newIds) {
            lastMmsId = maxOf(lastMmsId, id)
            readMms(id, 0, trigger)
        }
    }

    private fun readMms(id: Long, attempt: Int, trigger: String) {
        val cr = ctx.contentResolver
        var box = -1
        var mType = -1
        var dateSec = 0L
        var subId = -1
        try {
            cr.query(Uri.parse("content://mms/$id"), arrayOf("msg_box", "m_type", "date", "sub_id"), null, null, null)?.use {
                if (it.moveToFirst()) {
                    box = it.getInt(0)
                    mType = it.getInt(1)
                    dateSec = it.getLong(2)
                    subId = it.getInt(3)
                } else {
                    // The row was replaced, usually a download notification becoming the full message.
                    EventLog.log(ctx, "mms_row_replaced", "id" to id, "attempt" to attempt)
                    return
                }
            }
        } catch (e: Exception) {
            EventLog.log(ctx, "messages_read_error", "table" to "mms", "id" to id, "error" to e.toString())
            return
        }

        var from: String? = null
        var to: String? = null
        try {
            cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use {
                while (it.moveToNext()) {
                    when (it.getInt(1)) {
                        137 -> from = it.getString(0)
                        151 -> if (to == null) to = it.getString(0)
                    }
                }
            }
        } catch (_: Exception) {
        }

        val types = ArrayList<String>()
        var textChars = 0
        try {
            cr.query(Uri.parse("content://mms/part"), arrayOf("ct", "text"), "mid=?", arrayOf(id.toString()), null)?.use {
                while (it.moveToNext()) {
                    val ct = it.getString(0) ?: "?"
                    if (ct == "application/smil") continue
                    types.add(ct)
                    if (ct == "text/plain") textChars += it.getString(1)?.length ?: 0
                }
            }
        } catch (_: Exception) {
        }

        val notDownloadedYet = mType == 130 // notification only; Google Messages has not fetched the content
        val incomplete = notDownloadedYet || types.isEmpty()
        if (incomplete && attempt < retryDelaysMs.size) {
            handler.postDelayed({ readMms(id, attempt + 1, trigger) }, retryDelaysMs[attempt])
            return
        }

        EventLog.log(
            ctx, "mms_stored",
            "id" to id,
            "direction" to if (box == 1) "in" else "out",
            "box" to box,
            "m_type" to mType,
            "from" to if (box == 1) from else "me",
            "to" to if (box == 1) "me" else to,
            "part_types" to types.joinToString(","),
            "text_chars" to textChars,
            "complete" to !incomplete,
            "attempts" to attempt + 1,
            "sub_id" to subId,
            "ms_since_message_date" to if (dateSec > 0) System.currentTimeMillis() - dateSec * 1000 else null,
            "found_by" to trigger
        )
    }

    // ---- Helpers ----

    private fun maxId(uri: String): Long? = try {
        ctx.contentResolver.query(Uri.parse(uri), arrayOf("_id"), null, null, "_id DESC")
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    } catch (e: SecurityException) {
        logDenied(e)
        null
    } catch (e: Exception) {
        EventLog.log(ctx, "messages_read_error", "uri" to uri, "error" to e.toString())
        null
    }

    private fun logDenied(e: SecurityException) {
        if (deniedLogged) return
        deniedLogged = true
        EventLog.log(ctx, "messages_read_denied", "error" to e.message)
    }
}
