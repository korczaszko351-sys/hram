package pl.hram.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ekran główny: status urządzenia, limity aplikacji, arena HRAM, wejścia dalej.
 * Wszystkie wartości pochodzą z pomiaru — nie ma tu liczb „na zapas”.
 */
class MainActivity : AppCompatActivity() {

    private var handle: Long = 0L
    private lateinit var arenaFile: File

    private lateinit var deviceRam: TextView
    private lateinit var deviceDisk: TextView
    private lateinit var deviceHeap: TextView
    private lateinit var limitHeap: TextView
    private lateinit var limitNative: TextView
    private lateinit var limitAbi: TextView
    private lateinit var arenaStatus: TextView
    private lateinit var arenaStats: TextView
    private lateinit var benchResult: TextView
    private lateinit var labelArenaMb: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arenaFile = File(filesDir, "hram_arena.bin")

        deviceRam = findViewById(R.id.deviceRam)
        deviceDisk = findViewById(R.id.deviceDisk)
        deviceHeap = findViewById(R.id.deviceHeap)
        limitHeap = findViewById(R.id.limitHeap)
        limitNative = findViewById(R.id.limitNative)
        limitAbi = findViewById(R.id.limitAbi)
        arenaStatus = findViewById(R.id.arenaStatus)
        arenaStats = findViewById(R.id.arenaStats)
        benchResult = findViewById(R.id.benchResult)
        labelArenaMb = findViewById(R.id.labelArenaMb)

        val sliderArena = findViewById<Slider>(R.id.sliderArenaMb)
        sliderArena.addOnChangeListener { _, value, _ ->
            labelArenaMb.text = "${value.toInt()} MB"
        }
        labelArenaMb.text = "${sliderArena.value.toInt()} MB"

        findViewById<MaterialButton>(R.id.btnOpenArena).setOnClickListener {
            openArena(sliderArena.value.toInt())
        }
        findViewById<MaterialButton>(R.id.btnSelfTest).setOnClickListener { runSelfTest() }
        findViewById<MaterialButton>(R.id.btnBench).setOnClickListener { runBench() }
        findViewById<MaterialButton>(R.id.btnHyper).setOnClickListener {
            startActivity(Intent(this, HyperActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnRoot).setOnClickListener {
            startActivity(Intent(this, RootActivity::class.java))
        }

        refreshDevice()
    }

    override fun onResume() {
        super.onResume()
        refreshDevice()
        if (handle != 0L) updateArenaStats()
    }

    private fun refreshDevice() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { MemInfo.read(this@MainActivity) }
            val usedRam = snap.totalRam - snap.availRam
            deviceRam.text = buildString {
                append("RAM: ").append(MemInfo.fmtBytes(usedRam))
                append(" / ").append(MemInfo.fmtBytes(snap.totalRam))
                append("  (wolne ").append(MemInfo.fmtBytes(snap.availRam)).append(")")
                if (snap.lowMemory) append("\n⚠ System zgłasza low memory")
            }
            deviceDisk.text = buildString {
                append("Dysk: ").append(MemInfo.fmtBytes(snap.totalDisk - snap.freeDisk))
                append(" / ").append(MemInfo.fmtBytes(snap.totalDisk))
                append("\nWolne: ").append(MemInfo.fmtBytes(snap.freeDisk))
            }
            deviceHeap.text = "ABI: " + snap.abis.joinToString(", ") +
                (if (snap.is64Bit) "  (64-bit)" else "  (32-bit)")

            limitHeap.text = "Heap Javy: limit ${snap.memoryClassMb} MB / largeHeap ${snap.largeMemoryClassMb} MB" +
                "\nZajęte: ${MemInfo.fmtBytes(snap.javaHeapUsed)} z ${MemInfo.fmtBytes(snap.javaHeapMax)}" +
                (if (snap.largeHeapDeclared) "\nlargeHeap: zadeklarowany" else "\nlargeHeap: NIE")
            limitNative.text = "Heap natywny (NDK): ${MemInfo.fmtBytes(snap.nativeHeapUsed)} zajęte"
            limitAbi.text = "Limit procesu: ${snap.largeMemoryClassMb} MB (largeHeap), próg LMK ${MemInfo.fmtBytes(snap.threshold)}"
        }
    }

    private fun openArena(sizeMb: Int) {
        lifecycleScope.launch {
            if (handle != 0L) {
                withContext(Dispatchers.IO) { HramNative.nativeFlush(handle) }
                updateArenaStats()
                return@launch
            }
            arenaStatus.text = "Otwieram arenę…"
            val blockSize = 1L shl 20  // 1 MiB
            val blockCount = (sizeMb.toLong() * 1024 * 1024) / blockSize
            val cacheBytes = minOf(blockCount * blockSize / 4, 64L * 1024 * 1024)

            val h = withContext(Dispatchers.IO) {
                HramNative.nativeOpen(arenaFile.absolutePath, blockSize, blockCount, cacheBytes, true)
            }
            if (h == 0L) {
                arenaStatus.text = "Nie udało się otworzyć areny: ${HramNative.nativeLastError()}"
                return@launch
            }
            handle = h
            arenaStatus.text = "Arena otwarta: ${sizeMb} MB logicznie, cache ${MemInfo.fmtBytes(cacheBytes)}"
            updateArenaStats()
        }
    }

    private fun updateArenaStats() {
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { HramNative.nativeStats(handle) }
            if (s.size < HramNative.STATS_LEN) return@launch
            val reads = s[HramNative.STATS_READS]
            val hits = s[HramNative.STATS_RAM_HITS]
            val hitRate = if (reads > 0) hits * 100.0 / reads else 0.0
            val ratio = if (s[HramNative.STATS_BYTES_IN] > 0)
                s[HramNative.STATS_BYTES_STORED].toDouble() / s[HramNative.STATS_BYTES_IN] else 0.0
            val logical = s[HramNative.STATS_FILE_LOGICAL]
            val alloc = s[HramNative.STATS_FILE_ALLOCATED]
            arenaStats.text = buildString {
                append("Logicznie: ").append(MemInfo.fmtBytes(logical))
                append("   fizycznie na flashu: ").append(MemInfo.fmtBytes(alloc))
                if (logical > 0) {
                    append("  (").append(String.format(java.util.Locale.US, "%.1f%%", alloc * 100.0 / logical)).append(")")
                }
                append("\nOdczyty: ").append(reads).append("  trafienia RAM: ").append(hits)
                append("  (").append(String.format(java.util.Locale.US, "%.1f%%", hitRate)).append(")")
                append("\nZapisy: ").append(s[HramNative.STATS_WRITES])
                append("  współczynnik kompresji: ").append(String.format(java.util.Locale.US, "%.3f", ratio))
                if (ratio > 0) append("  (1:").append(String.format(java.util.Locale.US, "%.1f", 1.0 / ratio)).append(")")
            }
        }
    }

    private fun runSelfTest() {
        if (handle == 0L) { benchResult.text = "Najpierw otwórz arenę."; return }
        lifecycleScope.launch {
            benchResult.text = "Test poprawności trwa…"
            val errs = withContext(Dispatchers.IO) { HramNative.nativeSelfTest(handle, 16) }
            benchResult.text = if (errs == 0)
                "Test poprawności: OK — zapis, odczyt z flasha i CRC zgodne (16 bloków)."
            else "Test poprawności: $errs błędów — dane nie odtworzyły się poprawnie."
            updateArenaStats()
        }
    }

    private fun runBench() {
        if (handle == 0L) { benchResult.text = "Najpierw otwórz arenę."; return }
        lifecycleScope.launch {
            benchResult.text = "Pomiar trwa (kilka sekund)…"
            val b = withContext(Dispatchers.IO) { HramNative.bench(handle, 16) }
            benchResult.text = buildString {
                append("Zapis sekwencyjny HRAM: ").append(HyperMath.fmt(b.seqWriteMbps)).append(" MB/s\n")
                append("Odczyt z RAM (cache): ").append(HyperMath.fmt(b.seqReadRamMbps)).append(" MB/s\n")
                append("Odczyt z flasha: ").append(HyperMath.fmt(b.seqReadDiskMbps)).append(" MB/s\n")
                append("Losowy 64 KiB z RAM: ").append(HyperMath.fmt(b.randReadRamMbps)).append(" MB/s\n")
                append("Losowy 64 KiB z flasha: ").append(HyperMath.fmt(b.randReadDiskMbps)).append(" MB/s\n")
                append("Surowy zapis (sufit flasha): ").append(HyperMath.fmt(b.rawWriteMbps)).append(" MB/s\n")
                append("Kompresja: ").append(String.format(java.util.Locale.US, "%.3f", b.compressRatio))
            }
            updateArenaStats()
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
