package com.you.spy.shell

import com.you.spy.util.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object Shell {

    fun hasRoot(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        p.waitFor() == 0
    }.getOrDefault(false)

    fun hasShizuku(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
    }.getOrDefault(false)

    fun mode(): String = when {
        Config.shellMode == "root" && hasRoot() -> "root"
        Config.shellMode == "shizuku" && hasShizuku() -> "shizuku"
        Config.shellMode == "auto" && hasRoot() -> "root"
        Config.shellMode == "auto" && hasShizuku() -> "shizuku"
        else -> "none"
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 15000): String = withContext(Dispatchers.IO) {
        when (mode()) {
            "root" -> run(arrayOf("su", "-c", cmd), timeoutMs)
            "shizuku" -> runShizuku(cmd, timeoutMs)
            else -> run(arrayOf("sh", "-c", cmd), timeoutMs)
        }
    }

    /**
     * Shizuku 13 me newProcess() Kotlin-level par private hai.
     * JVM method public hi hai, isliye reflection se call karte hain.
     */
    private fun runShizuku(cmd: String, timeoutMs: Long): String = runCatching {
        val cls = Class.forName("rikka.shizuku.Shizuku")
        val m = cls.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        collect(proc, timeoutMs)
    }.getOrElse {
        // reflection fail ho to normal shell par gir jao
        run(arrayOf("sh", "-c", cmd), timeoutMs)
    }

    private fun run(argv: Array<String>, timeoutMs: Long): String = runCatching {
        val proc = Runtime.getRuntime().exec(argv)
        collect(proc, timeoutMs)
    }.getOrElse { "ERR: ${it.message}" }

    private fun collect(proc: Process, timeoutMs: Long): String {
        val out = StringBuilder()
        val t = Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    r.lineSequence().forEach { if (out.length < 200_000) out.appendLine(it) }
                }
            }
        }
        t.start()
        proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        t.join(1500)
        runCatching { proc.destroy() }
        val err = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("")
        return (out.toString() + err).trim().ifEmpty { "(no output)" }
    }
}
