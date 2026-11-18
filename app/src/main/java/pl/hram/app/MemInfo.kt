package pl.hram.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.os.StatFs
import java.io.File
import java.util.Locale

/**
 * Odczyt realnych limitów pamięci urządzenia i procesu.
 *
 * Tu rozdzielamy to, co narzuca system (twarde), od tego, co ustawia aplikacja.
 */
object MemInfo {

    data class Snapshot(
        val totalRam: Long,
        val availRam: Long,
        val lowMemory: Boolean,
        val threshold: Long,
        val memoryClassMb: Int,
        val largeMemoryClassMb: Int,
        val largeHeapDeclared: Boolean,
        val javaHeapUsed: Long,
        val javaHeapMax: Long,
        val nativeHeapUsed: Long,
        val freeDisk: Long,
        val totalDisk: Long,
        val is64Bit: Boolean,
        val abis: List<String>
    )

    fun read(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val ai = context.applicationInfo
        val largeHeap = (ai.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0

        val stat = StatFs(context.filesDir.absolutePath)
        val abis = android.os.Build.SUPPORTED_ABIS.toList()

        return Snapshot(
            totalRam = mi.totalMem,
            availRam = mi.availMem,
            lowMemory = mi.lowMemory,
            threshold = mi.threshold,
            memoryClassMb = am.memoryClass,
            largeMemoryClassMb = am.largeMemoryClass,
            largeHeapDeclared = largeHeap,
            javaHeapUsed = javaUsed,
            javaHeapMax = runtime.maxMemory(),
            nativeHeapUsed = Debug.getNativeHeapAllocatedSize(),
            freeDisk = stat.availableBytes,
            totalDisk = stat.totalBytes,
            is64Bit = android.os.Process.is64Bit(),
            abis = abis
        )
    }

    /**
     * Realne zajęcie pliku na dysku (st_blocks), a nie rozmiar logiczny.
     * Plik rzadki ma ogromny rozmiar logiczny i znikome zajęcie fizyczne.
     */
    fun allocatedBytes(file: File): Long {
        return try {
            val p = ProcessBuilder("stat", "-c", "%b %B", file.absolutePath).start()
            val line = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            val parts = line.split(" ")
            if (parts.size == 2) {
                (parts[0].toLongOrNull() ?: 0L) * (parts[1].toLongOrNull() ?: 0L)
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f kB", b / 1024.0)
        else -> "$b B"
    }
}
