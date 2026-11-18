package pl.hram.app

import java.util.Locale
import kotlin.math.abs

/**
 * Matematyka przepustowości — liczona wprost, bez zaokrąglania „na korzyść”.
 *
 * Podstawowy wzór:  przepustowość = rozmiar transferu / czas transferu
 *
 * Moduł istnieje po to, żeby UI mogło pokazać, ile faktycznie wychodzi
 * z danego rozmiaru bloku i czasu, oraz jak daleko od celu 14 GB/s jest
 * zmierzona wartość.
 */
object HyperMath {

    const val MB = 1_048_576.0          // 1 MiB w bajtach (jednostka naszych pomiarów)
    const val MB_DECIMAL = 1_000_000.0  // 1 MB dziesiętny (jednostka „10,24 MB” użytkownika)
    const val GB = 1_073_741_824.0      // 1 GiB
    const val GB_DECIMAL = 1_000_000_000.0

    /** Cel, który użytkownik chce osiągnąć — pokazywany jako 14 GB/s. */
    const val TARGET_GBPS = 14.0

    /** Przepustowość w MB/s dla transferu `bytes` w czasie `nanos`. */
    fun mbps(bytes: Double, nanos: Double): Double {
        if (nanos <= 0.0) return 0.0
        return (bytes / MB) / (nanos / 1e9)
    }

    /** Przepustowość w GB/s (dziesiętnych) dla transferu `bytes` w czasie `nanos`. */
    fun gbps(bytes: Double, nanos: Double): Double {
        if (nanos <= 0.0) return 0.0
        return (bytes / GB_DECIMAL) / (nanos / 1e9)
    }

    /** Ile nanosekund potrzeba na przesłanie `bytes` przy `mbps`. */
    fun requiredNanos(bytes: Double, mbps: Double): Double {
        if (mbps <= 0.0) return Double.POSITIVE_INFINITY
        return (bytes / MB) / mbps * 1e9
    }

    /**
     * Weryfikacja konkretnego założenia: 10,24 MB w 10 nanosekund.
     *
     * 10,24 MB = 10 240 000 B;  10 ns = 1e-8 s
     * => 1,024e15 B/s = 1 024 000 GB/s = 1024 TB/s
     *
     * To nie jest 14 GB/s. 14 GB/s wymaga 10,24 MB w ok. 731 µs.
     */
    data class ClaimCheck(
        val bytes: Double,
        val nanos: Double,
        val throughputGbps: Double,
        val throughputTbps: Double,
        val requiredNanosFor14: Double,
        val requiredMicrosFor14: Double,
        val factorVsTarget: Double,
        val lightTravelMetres: Double
    )

    fun checkClaim10ns(bytes: Double = 10.24 * MB_DECIMAL, nanos: Double = 10.0): ClaimCheck {
        val gbps = gbps(bytes, nanos)
        val reqNs = requiredNanos(bytes, TARGET_GBPS * 1000.0)
        return ClaimCheck(
            bytes = bytes,
            nanos = nanos,
            throughputGbps = gbps,
            throughputTbps = gbps / 1000.0,
            requiredNanosFor14 = reqNs,
            requiredMicrosFor14 = reqNs / 1000.0,
            factorVsTarget = if (gbps > 0) gbps / TARGET_GBPS else 0.0,
            lightTravelMetres = 299_792_458.0 * (nanos / 1e9)
        )
    }

    /** Postęp w stronę celu 14 GB/s, 0..1 (do paska postępu). */
    fun progressToTarget(measuredGbps: Double): Float =
        (measuredGbps / TARGET_GBPS).coerceIn(0.0, 1.0).toFloat()

    /** Ile procent celu osiągnięto. */
    fun percentOfTarget(measuredGbps: Double): Double =
        if (TARGET_GBPS <= 0) 0.0 else measuredGbps / TARGET_GBPS * 100.0

    /**
     * Wąskie gardło na podstawie relacji między zmierzonymi warstwami.
     * Nie zgadujemy — porównujemy to, co naprawdę wyszło z pomiaru.
     */
    fun bottleneck(h: HramNative.Hyper?): String {
        if (h == null || h.writeMbps <= 0.0 && h.readMbps <= 0.0) {
            return "Brak pomiaru — uruchom benchmark."
        }
        val storage = maxOf(h.storageWriteMbps, h.storageReadMbps)
        val hramLayer = maxOf(h.writeMbps, h.readMbps)
        return when {
            storage <= 0.0 -> "Nie udało się zmierzyć warstwy zapisu — sprawdź miejsce na dysku."
            hramLayer < storage * 0.6 ->
                "Wąskie gardło: CPU (kompresja LZ4 + CRC32). Zapis surowy ${fmt(storage)} MB/s, " +
                    "pełna ścieżka HRAM ${fmt(hramLayer)} MB/s."
            hramLayer < storage * 0.9 ->
                "Blisko sufitu: częściowo CPU (kompresja), częściowo flash."
            else ->
                "Wąskie gardło: flash/kontroler (${fmt(storage)} MB/s). Warstwa HRAM nie nadąża " +
                    "ograniczać bardziej niż samo nośnik."
        }
    }

    /**
     * Ile danych trzeba przesłać, żeby przy zmierzonej przepustowości
     * „osiągnąć” 14 GB/s — czyli jak absurdalnie krótki musiałby być transfer.
     */
    fun timeForTarget(blockBytes: Double, measuredGbps: Double): Double {
        if (measuredGbps <= 0) return Double.NaN
        return blockBytes / (measuredGbps * GB_DECIMAL) * 1e9
    }

    fun fmt(v: Double): String = when {
        abs(v) >= 1000 -> String.format(Locale.US, "%.0f", v)
        abs(v) >= 100 -> String.format(Locale.US, "%.1f", v)
        abs(v) >= 1 -> String.format(Locale.US, "%.2f", v)
        abs(v) > 0 -> String.format(Locale.US, "%.3f", v)
        else -> "0"
    }

    fun fmtGbps(v: Double): String = String.format(Locale.US, "%.3f GB/s", v)

    fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f kB", b / 1024.0)
        else -> "$b B"
    }
}
