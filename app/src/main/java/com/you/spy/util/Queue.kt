package com.you.spy.util

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Simple line-based retry queue for outgoing messages. */
object Queue {

    private lateinit var file: File
    private val lock = ReentrantLock()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "outbox.txt")
    }

    fun push(text: String) = lock.withLock {
        runCatching { file.appendText(text.replace("\n", "\\n") + "\n") }
    }

    fun drain(): List<String> = lock.withLock {
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        file.writeText("")
        lines.map { it.replace("\\n", "\n") }
    }

    fun size(): Int = lock.withLock { if (file.exists()) file.readLines().count { it.isNotBlank() } else 0 }
}
