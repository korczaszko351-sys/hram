package pl.hram.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Wykonywanie poleceń jako root przez `su`.
 *
 * Wykrywanie roota jest zachowawcze: jeśli `su -c id` nie odpowie w 4 s,
 * uznajemy, że roota nie ma (nie zawieszamy UI).
 */
object RootShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
        val combined: String get() = (stdout + "\n" + stderr).trim()
    }

    @Volatile
    private var cachedAvailability: Boolean? = null

    /** Zwraca true, jeśli `su` istnieje i potrafi wykonać polecenie jako root. */
    fun isRootAvailable(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) cachedAvailability?.let { return it }
        val result = run("id", timeoutSec = 4)
        val available = result.ok && result.stdout.contains("uid=0")
        cachedAvailability = available
        return available
    }

    fun invalidateCache() {
        cachedAvailability = null
    }

    /**
     * Wykonuje polecenie przez `su -c`. Działa też bez roota dla poleceń
     * nie wymagających uprawnień (wtedy zwróci błąd — sprawdzaj `ok`).
     */
    fun run(command: String, timeoutSec: Long = 20): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()

            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { r ->
                        r.forEachLine { out.append(it).append('\n') }
                    }
                } catch (_: Exception) {
                }
            }
            val tErr = Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.errorStream)).use { r ->
                        r.forEachLine { err.append(it).append('\n') }
                    }
                } catch (_: Exception) {
                }
            }
            tOut.start(); tErr.start()

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result(-1, out.toString(), "timeout po ${timeoutSec}s")
            }
            tOut.join(2000); tErr.join(2000)
            Result(process.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            try { process?.destroy() } catch (_: Exception) { }
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Sprawdza dostępność narzędzia w PATH (np. mkswap, swapon). */
    fun hasBinary(name: String): Boolean {
        val r = run("which $name", timeoutSec = 8)
        return r.ok && r.stdout.trim().isNotEmpty()
    }
}
