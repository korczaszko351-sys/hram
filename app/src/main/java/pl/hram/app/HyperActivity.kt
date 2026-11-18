package pl.hram.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Ekran „Hiperprędkość”.
 *
 * Pokazuje wyłącznie przepustowość ZMIERZONĄ na tym urządzeniu, na tle celu
 * 14 GB/s. Cel pochodzi ze specyfikacji DRAM i nie jest deklaracją aplikacji.
 * Moduł liczy też wprost założenie „10,24 MB w 10 ns”, żeby pokazać, że
 * daje ono ~1 PB/s, a nie 14 GB/s.
 */
class HyperActivity : AppCompatActivity() {

    private var handle: Long = 0L
    private lateinit var arenaFile: File

    private lateinit var tvGbps: TextView
    private lateinit var tvMbps: TextView
    private lateinit var tvProgressLabel: TextView
    private lateinit var tvGap: TextView
    private lateinit var tvBottleneck: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvClaim: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var labelBlock: TextView
    private lateinit var labelChannels: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hyper)

        arenaFile = File(filesDir, "hram_hyper.bin")

        tvGbps = findViewById(R.id.hyperGbps)
        tvMbps = findViewById(R.id.hyperMbps)
        tvProgressLabel = findViewById(R.id.hyperProgressLabel)
        tvGap = findViewById(R.id.hyperGap)
        tvBottleneck = findViewById(R.id.hyperBottleneck)
        tvDetail = findViewById(R.id.hyperDetail)
        tvClaim = findViewById(R.id.claimResult)
        progress = findViewById(R.id.hyperProgress)
        labelBlock = findViewById(R.id.labelBlock)
        labelChannels = findViewById(R.id.labelChannels)

        val sliderBlock = findViewById<Slider>(R.id.sliderBlock)
        val sliderChannels = findViewById<Slider>(R.id.sliderChannels)

        sliderBlock.addOnChangeListener { _, value, _ ->
            labelBlock.text = "${value.toInt()} MB"
        }
        sliderChannels.addOnChangeListener { _, value, _ ->
            labelChannels.text = "${value.toInt()} kanałów"
        }
        labelBlock.text = "${sliderBlock.value.toInt()} MB"
        labelChannels.text = "${sliderChannels.value.toInt()} kanałów"

        showClaimCheck()

        findViewById<MaterialButton>(R.id.btnRunHyper).setOnClickListener {
            runHyper(sliderBlock.value.toInt(), sliderChannels.value.toInt())
        }

        tvGbps.text = "—"
        tvMbps.text = "Brak pomiaru — uruchom benchmark wielokanałowy."
    }

    /** Weryfikacja założenia użytkownika: 10,24 MB w 10 ns. */
    private fun showClaimCheck() {
        val c = HyperMath.checkClaim10ns()
        tvClaim.text = buildString {
            append("10,24 MB = ").append(String.format(Locale.US, "%,.0f", c.bytes)).append(" B")
            append(" w 10 ns (1e-8 s)\n")
            append("→ ").append(String.format(Locale.US, "%,.0f", c.throughputGbps))
            append(" GB/s = ").append(String.format(Locale.US, "%.0f", c.throughputTbps)).append(" TB/s\n")
            append("→ to ").append(String.format(Locale.US, "%,.0f", c.factorVsTarget))
            append("× więcej niż 14 GB/s\n\n")
            append("Żeby uzyskać 14 GB/s, 10,24 MB musi przejść w ")
            append(String.format(Locale.US, "%.0f", c.requiredMicrosFor14)).append(" µs")
            append(" (")
            append(String.format(Locale.US, "%,.0f", c.requiredNanosFor14)).append(" ns).\n")
            append("Światło w 10 ns pokonuje ")
            append(String.format(Locale.US, "%.2f", c.lightTravelMetres)).append(" m — ")
            append("dane nie mogą być szybsze niż światło, ale to nadal nie jest limit Twojego flasha.")
        }
    }

    private suspend fun ensureArena(): Boolean {
        if (handle != 0L) return true
        val blockSize = 1L shl 20
        val blockCount = 64L  // 64 MiB
        val cacheBytes = 16L * 1024 * 1024
        val h = withContext(Dispatchers.IO) {
            HramNative.nativeOpen(arenaFile.absolutePath, blockSize, blockCount, cacheBytes, true)
        }
        if (h == 0L) {
            tvMbps.text = "Nie udało się otworzyć pliku testowego: ${HramNative.nativeLastError()}"
            return false
        }
        handle = h
        return true
    }

    private fun runHyper(blockMb: Int, channels: Int) {
        lifecycleScope.launch {
            if (!ensureArena()) return@launch
            tvGbps.text = "…"
            tvMbps.text = "Pomiar: ${blockMb} MB × ${channels} kanałów"
            tvGap.text = "Trwa benchmark wielokanałowy…"

            val h = withContext(Dispatchers.IO) {
                HramNative.hyper(handle, blockMb.toLong() * 1024 * 1024, channels, 64)
            }
            render(h)
        }
    }

    private fun render(h: HramNative.Hyper) {
        val best = maxOf(h.writeMbps, h.readMbps)
        val bestGbps = best * 1024.0 * 1024.0 / 1_000_000_000.0

        tvGbps.text = HyperMath.fmtGbps(bestGbps)
        tvMbps.text = buildString {
            append("Zapis HRAM: ").append(HyperMath.fmt(h.writeMbps)).append(" MB/s")
            append("   Odczyt HRAM: ").append(HyperMath.fmt(h.readMbps)).append(" MB/s")
            append("\nSurowy zapis: ").append(HyperMath.fmt(h.storageWriteMbps)).append(" MB/s")
            append("   Surowy odczyt: ").append(HyperMath.fmt(h.storageReadMbps)).append(" MB/s")
        }

        val pct = HyperMath.percentOfTarget(bestGbps)
        progress.progress = (HyperMath.progressToTarget(bestGbps) * 1000).toInt()
        tvProgressLabel.text = String.format(Locale.US, "%.2f%% celu 14 GB/s", pct)

        tvGap.text = if (bestGbps >= HyperMath.TARGET_GBPS) {
            String.format(Locale.US, "Zmierzone %.3f GB/s — cel osiągnięty na tym urządzeniu.", bestGbps)
        } else {
            val missing = HyperMath.TARGET_GBPS - bestGbps
            val factor = HyperMath.TARGET_GBPS / bestGbps
            String.format(
                Locale.US,
                "Brakuje %.3f GB/s. Żeby dojść do 14 GB/s, ten sam blok musiałby przejść %.1f× szybciej, " +
                    "czyli w %.1f µs zamiast zmierzonych %.1f µs.",
                missing, factor,
                HyperMath.timeForTarget(h.blockBytes.toDouble(), HyperMath.TARGET_GBPS) / 1000.0,
                h.nsPerBlockAvg / 1000.0
            )
        }

        tvBottleneck.text = HyperMath.bottleneck(h)

        val perChannel = if (h.perChannelMbps.isEmpty()) "—" else
            h.perChannelMbps.mapIndexed { i, v -> "K${i + 1}: ${HyperMath.fmt(v)}" }.joinToString("  ")

        tvDetail.text = buildString {
            append("Blok: ").append(h.blockBytes / 1048576).append(" MB")
            append("   Kanały: ").append(h.channels)
            append("   Bloków: ").append(h.blocks).append("\n")
            append("Czas na transfer bloku (pełna ścieżka): ")
            append(String.format(Locale.US, "%.1f µs", h.nsPerBlockAvg / 1000.0))
            append("\nNajszybszy transfer bloku: ")
            append(String.format(Locale.US, "%.1f µs", h.nsPerBlockMin / 1000.0))
            append("\nSurowy transfer bloku: ")
            append(String.format(Locale.US, "%.1f µs", h.nsRawPerBlockAvg / 1000.0))
            append("\nKompresja: ").append(String.format(Locale.US, "%.3f", h.compressRatio))
            append("\n\nPrzepustowość per kanał (zapis):\n").append(perChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (handle != 0L) {
            HramNative.nativeFlush(handle)
            HramNative.nativeClose(handle)
            handle = 0L
        }
    }
}
