package pl.hram.app

/**
 * Cienki wrapper na bibliotekę natywną `libhram.so`.
 *
 * Wszystkie pomiary pochodzą z warstwy C++ — Kotlin nie wymyśla żadnych liczb.
 * Uchwyt (Long) to wskaźnik na hram::Arena po stronie natywnej; 0 = brak areny.
 */
object HramNative {

    init {
        System.loadLibrary("hram")
    }

    // Kolejność pól w tablicy statystyk — musi odpowiadać hram_jni.cpp.
    const val STATS_LEN = 12
    const val STATS_READS = 0
    const val STATS_WRITES = 1
    const val STATS_RAM_HITS = 2
    const val STATS_DISK_READS = 3
    const val STATS_BYTES_IN = 4
    const val STATS_BYTES_STORED = 5
    const val STATS_EVICTIONS = 6
    const val STATS_CACHE_BYTES = 7
    const val STATS_FILE_ALLOCATED = 8
    const val STATS_FILE_LOGICAL = 9
    const val STATS_BLOCK_SIZE = 10
    const val STATS_BLOCK_COUNT = 11

    external fun nativeVersion(): String
    external fun nativeLastError(): String

    external fun nativeOpen(
        path: String,
        blockSize: Long,
        blockCount: Long,
        cacheBytes: Long,
        compress: Boolean
    ): Long

    external fun nativeClose(handle: Long)
    external fun nativeWriteBlock(handle: Long, idx: Long, data: ByteArray, len: Int): Boolean
    external fun nativeReadBlock(handle: Long, idx: Long, out: ByteArray): Int
    external fun nativeStats(handle: Long): LongArray
    external fun nativeFlush(handle: Long)
    external fun nativeDropCache(handle: Long)
    external fun nativeSelfTest(handle: Long, blocks: Int): Int
    external fun nativeBench(handle: Long, megabytes: Int): DoubleArray
    external fun nativeHyper(handle: Long, blockBytes: Long, channels: Int, megabytes: Int): DoubleArray

    /** Wynik benchmarku jednościeżkowego (wartości zmierzone, MB/s). */
    data class Bench(
        val seqWriteMbps: Double,
        val seqReadRamMbps: Double,
        val seqReadDiskMbps: Double,
        val randReadRamMbps: Double,
        val randReadDiskMbps: Double,
        val rawWriteMbps: Double,
        val compressRatio: Double
    )

    /** Wynik benchmarku wielokanałowego — moduł „hiperprędkość”. */
    data class Hyper(
        val writeMbps: Double,
        val readMbps: Double,
        val storageWriteMbps: Double,
        val storageReadMbps: Double,
        val nsPerBlockAvg: Double,
        val nsPerBlockMin: Double,
        val nsRawPerBlockAvg: Double,
        val blocks: Long,
        val blockBytes: Long,
        val channels: Int,
        val compressRatio: Double,
        val perChannelMbps: List<Double>
    )

    fun bench(handle: Long, megabytes: Int): Bench {
        val a = nativeBench(handle, megabytes)
        if (a.size < 7) return Bench(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        return Bench(a[0], a[1], a[2], a[3], a[4], a[5], a[6])
    }

    fun hyper(handle: Long, blockBytes: Long, channels: Int, megabytes: Int): Hyper {
        val a = nativeHyper(handle, blockBytes, channels, megabytes)
        if (a.size < 12) {
            return Hyper(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, blockBytes, channels, 0.0, emptyList())
        }
        val perChannel = if (a.size > 12) a.drop(12) else emptyList()
        return Hyper(
            writeMbps = a[0],
            readMbps = a[1],
            storageWriteMbps = a[2],
            storageReadMbps = a[3],
            nsPerBlockAvg = a[4],
            nsPerBlockMin = a[5],
            nsRawPerBlockAvg = a[6],
            blocks = a[7].toLong(),
            blockBytes = a[8].toLong(),
            channels = a[9].toInt(),
            compressRatio = a[10],
            perChannelMbps = perChannel
        )
    }
}
