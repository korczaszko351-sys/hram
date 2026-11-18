package pl.hram.app

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Zarządzanie swapem i zRAM. Wszystkie operacje wymagają roota.
 *
 * Aplikacja NIE dodaje fizycznego RAM-u — swap to obszar na flashu,
 * a zRAM to kompresja w RAM. Obie techniki zmieniają charakter pamięci:
 * więcej pojemności, wolniejszy dostęp.
 */
class SwapManager(private val context: Context) {

    data class SwapEntry(val path: String, val type: String, val sizeKb: Long, val usedKb: Long)

    data class SwapState(
        val entries: List<SwapEntry>,
        val totalKb: Long,
        val usedKb: Long,
        val swappiness: String,
        val zramDisks: List<String>,
        val error: String? = null
    )

    /** Plik wymiany trzymany w pamięci aplikacji (usuwalny razem z danymi). */
    val swapFile: File get() = File(context.filesDir, "hram_swap.img")

    /** Ścieżka używana przez shell (ma dostęp do filesDir aplikacji). */
    private val swapPath: String get() = swapFile.absolutePath

    fun readState(): SwapState {
        val swapsRaw = RootShell.run("cat /proc/swaps", timeoutSec = 8)
        val entries = mutableListOf<SwapEntry>()
        swapsRaw.stdout.lineSequence().drop(1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 5 && parts[0].isNotEmpty()) {
                val size = parts[2].toLongOrNull() ?: 0L
                val used = parts[3].toLongOrNull() ?: 0L
                entries.add(SwapEntry(parts[0], parts[1], size, used))
            }
        }

        val swappinessRaw = RootShell.run("cat /proc/sys/vm/swappiness", timeoutSec = 6)
        val swappiness = swappinessRaw.stdout.trim().ifEmpty { "?" }

        val zramRaw = RootShell.run("ls -1 /sys/block | grep -i zram", timeoutSec = 6)
        val zram = zramRaw.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }

        return SwapState(
            entries = entries,
            totalKb = entries.sumOf { it.sizeKb },
            usedKb = entries.sumOf { it.usedKb },
            swappiness = swappiness,
            zramDisks = zram,
            error = if (!swapsRaw.ok && entries.isEmpty()) swapsRaw.combined else null
        )
    }

    /**
     * Tworzy plik wymiany i włącza go. `sizeMb` jest ograniczany do 4096 MB,
     * a plik nie może przekroczyć wolnego miejsca minus 256 MB zapasu.
     *
     * Zwraca log operacji (do pokazania użytkownikowi) — bez udawania sukcesu.
     */
    fun enableSwap(sizeMb: Int, freeDiskMb: Long, onLog: (String) -> Unit): Boolean {
        val cappedMb = minOf(sizeMb, 4096)
        val safeMb = minOf(cappedMb, maxOf(64, (freeDiskMb - 256).toInt()))
        if (safeMb <= 0) {
            onLog("Brak miejsca na plik wymiany (wolne: ${freeDiskMb} MB).")
            return false
        }
        if (safeMb < sizeMb) {
            onLog("Rozmiar zmniejszony do ${safeMb} MB (limit miejsca / 4096 MB).")
        }

        // Jeśli plik już jest aktywny — najpierw go wyłącz.
        if (isActive()) {
            onLog("Swap już aktywny — wyłączam przed ponownym utworzeniem.")
            disableSwap(onLog)
        }

        onLog("Tworzę plik ${safeMb} MB: $swapPath")
        val alloc = RootShell.run("dd if=/dev/zero of=$swapPath bs=1048576 count=$safeMb", 120)
        onLog("dd: exit=${alloc.exitCode} ${alloc.stderr.trim()}")
        if (!alloc.ok) {
            RootShell.run("rm -f $swapPath", 20)
            return false
        }

        val chmod = RootShell.run("chmod 600 $swapPath", 10)
        if (!chmod.ok) onLog("chmod: ${chmod.combined}")

        val mkswap = RootShell.run("mkswap $swapPath", 30)
        onLog("mkswap: ${mkswap.combined}")
        if (!mkswap.ok) return false

        val swapon = RootShell.run("swapon $swapPath", 30)
        onLog("swapon: ${swapon.combined}")
        return swapon.ok
    }

    fun disableSwap(onLog: (String) -> Unit): Boolean {
        val off = RootShell.run("swapoff $swapPath", 60)
        onLog("swapoff: ${off.combined.ifEmpty { "ok" }}")
        val rm = RootShell.run("rm -f $swapPath", 20)
        onLog("rm: ${rm.combined.ifEmpty { "ok" }}")
        return off.ok
    }

    fun isActive(): Boolean =
        readState().entries.any { it.path.contains("hram_swap") }

    /** Ustawia vm.swappiness (0–200). Wymaga roota. */
    fun setSwappiness(value: Int): Boolean {
        val v = value.coerceIn(0, 200)
        return RootShell.run("echo $v > /proc/sys/vm/swappiness", 10).ok
    }

    /**
     * Konfiguruje zRAM: ustawia rozmiar dysku i go włącza.
     * UWAGA: nadpisuje istniejącą konfigurację zRAM (często używaną przez ROM).
     */
    fun configureZram(sizeMb: Int, onLog: (String) -> Unit): Boolean {
        val state = readState()
        val dev = state.zramDisks.firstOrNull()
        if (dev == null) {
            onLog("Brak urządzenia zram w /sys/block — jądro nie ma modułu zram.")
            return false
        }
        onLog("Konfiguruję /dev/$dev na ${sizeMb} MB")
        val off = RootShell.run("swapoff /dev/$dev", 30)
        onLog("swapoff: ${off.combined.ifEmpty { "ok" }}")
        val setSize = RootShell.run("echo ${sizeMb}M > /sys/block/$dev/disksize", 15)
        onLog("disksize: ${setSize.combined.ifEmpty { "ok" }}")
        if (!setSize.ok) return false
        val mk = RootShell.run("mkswap /dev/$dev", 30)
        onLog("mkswap: ${mk.combined}")
        val on = RootShell.run("swapon /dev/$dev", 30)
        onLog("swapon: ${on.combined}")
        if (on.ok) {
            // Przy zRAM sensowne jest agresywniejsze wypychanie stron.
            setSwappiness(100)
            onLog("vm.swappiness ustawione na 100 (zRAM).")
        }
        return on.ok
    }

    fun formatKb(kb: Long): String = when {
        kb >= 1024 * 1024 -> String.format(Locale.US, "%.2f GB", kb / 1024.0 / 1024.0)
        kb >= 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
        else -> "$kb kB"
    }
}
