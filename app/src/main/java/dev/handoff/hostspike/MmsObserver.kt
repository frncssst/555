package dev.handoff.hostspike

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler

/**
 * A non-default app cannot download MMS itself. This watches the shared message store and logs
 * picture messages after Google Messages has downloaded them, to measure whether that is good enough.
 */
class MmsObserver(private val ctx: Context, handler: Handler) : ContentObserver(handler) {
    private var lastId = -1L
    private var deniedLogged = false

    fun prime() {
        lastId = latestInboxId() ?: -1L
        EventLog.log(ctx, "mms_observer_started", "last_inbox_id" to lastId)
    }

    override fun onChange(selfChange: Boolean) {
        val id = latestInboxId() ?: return
        if (id <= lastId) return
        lastId = id
        logMms(id)
    }

    /** Latest fully downloaded incoming MMS (message type 132, "retrieve confirmation"). */
    private fun latestInboxId(): Long? = try {
        ctx.contentResolver.query(
            Uri.parse("content://mms/inbox"), arrayOf("_id"), "m_type=132", null, "_id DESC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (e: SecurityException) {
        if (!deniedLogged) {
            EventLog.log(ctx, "mms_read_denied", "error" to e.message)
            deniedLogged = true
        }
        null
    } catch (e: Exception) {
        EventLog.log(ctx, "mms_read_error", "error" to e.toString())
        null
    }

    private fun logMms(id: Long) {
        val cr = ctx.contentResolver
        var dateSec = 0L
        var subId = -1
        try {
            cr.query(Uri.parse("content://mms/$id"), arrayOf("date", "sub_id"), null, null, null)?.use {
                if (it.moveToFirst()) {
                    dateSec = it.getLong(0)
                    subId = it.getInt(1)
                }
            }
        } catch (_: Exception) {
        }

        var from: String? = null
        try {
            cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type=137", null, null)?.use {
                if (it.moveToFirst()) from = it.getString(0)
            }
        } catch (_: Exception) {
        }

        val types = ArrayList<String>()
        val text = StringBuilder()
        try {
            cr.query(Uri.parse("content://mms/part"), arrayOf("ct", "text"), "mid=?", arrayOf(id.toString()), null)?.use {
                while (it.moveToNext()) {
                    val ct = it.getString(0) ?: "?"
                    types.add(ct)
                    if (ct == "text/plain") text.append(it.getString(1) ?: "")
                }
            }
        } catch (_: Exception) {
        }

        EventLog.log(
            ctx, "mms_seen",
            "id" to id,
            "from" to from,
            "part_types" to types.joinToString(","),
            "text_preview" to text.toString().take(40),
            "sub_id" to subId,
            "ms_since_message_date" to if (dateSec > 0) System.currentTimeMillis() - dateSec * 1000 else null
        )
    }
}
