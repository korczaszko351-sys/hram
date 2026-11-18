package pl.hram.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Panel roota: wykrywanie su, plik wymiany, zRAM, vm.swappiness.
 *
 * Przed pierwszą operacją wymagającą roota pokazywany jest ekran ostrzeżenia
 * o ryzyku (zużycie flasha, wolniejsze działanie, możliwy bootloop).
 */
class RootActivity : AppCompatActivity() {

    private lateinit var swap: SwapManager
    private val log = StringBuilder()

    private lateinit var rootStatus: TextView
    private lateinit var swapStatus: TextView
    private lateinit var swappinessValue: TextView
    private lateinit var rootLog: TextView
    private lateinit var labelSwap: TextView
    private lateinit var labelZram: TextView
    private lateinit var labelSwappiness: TextView

    private var warningAccepted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_root)

        swap = SwapManager(this)

        rootStatus = findViewById(R.id.rootStatus)
        swapStatus = findViewById(R.id.swapStatus)
        swappinessValue = findViewById(R.id.swappinessValue)
        rootLog = findViewById(R.id.rootLog)
        labelSwap = findViewById(R.id.labelSwap)
        labelZram = findViewById(R.id.labelZram)
        labelSwappiness = findViewById(R.id.labelSwappiness)

        val sliderSwap = findViewById<Slider>(R.id.sliderSwap)
        val sliderZram = findViewById<Slider>(R.id.sliderZram)
        val sliderSwappiness = findViewById<Slider>(R.id.sliderSwappiness)

        sliderSwap.addOnChangeListener { _, v, _ -> labelSwap.text = "${v.toInt()} MB" }
        sliderZram.addOnChangeListener { _, v, _ -> labelZram.text = "${v.toInt()} MB" }
        sliderSwappiness.addOnChangeListener { _, v, _ -> labelSwappiness.text = "${v.toInt()}" }
        labelSwap.text = "${sliderSwap.value.toInt()} MB"
        labelZram.text = "${sliderZram.value.toInt()} MB"
        labelSwappiness.text = "${sliderSwappiness.value.toInt()}"

        findViewById<MaterialButton>(R.id.btnCheckRoot).setOnClickListener {
            RootShell.invalidateCache()
            checkRoot()
        }

        findViewById<MaterialButton>(R.id.btnEnableSwap).setOnClickListener {
            withRootWarning {
                val snap = MemInfo.read(this)
                val freeMb = snap.freeDisk / (1024 * 1024)
                runOp("Tworzę swap ${sliderSwap.value.toInt()} MB") {
                    swap.enableSwap(sliderSwap.value.toInt(), freeMb) { line -> publish(line) }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnDisableSwap).setOnClickListener {
            withRootWarning {
                runOp("Wyłączam swap") {
                    swap.disableSwap { line -> publish(line) }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnZram).setOnClickListener {
            withRootWarning {
                runOp("Konfiguruję zRAM ${sliderZram.value.toInt()} MB") {
                    swap.configureZram(sliderZram.value.toInt()) { line -> publish(line) }
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnSwappiness).setOnClickListener {
            withRootWarning {
                runOp("Ustawiam vm.swappiness = ${sliderSwappiness.value.toInt()}") {
                    swap.setSwappiness(sliderSwappiness.value.toInt())
                }
            }
        }

        checkRoot()
    }

    private fun checkRoot() {
        lifecycleScope.launch {
            rootStatus.text = "Sprawdzam su…"
            val hasRoot = withContext(Dispatchers.IO) { RootShell.isRootAvailable(forceRefresh = true) }
            rootStatus.text = if (hasRoot) {
                "Root: dostępny (su odpowiada jako uid=0)"
            } else {
                "Root: niedostępny\nOperacje swap/zRAM nie będą wykonywane. Reszta aplikacji (arena HRAM, pomiary) działa bez roota."
            }
            refreshState()
        }
    }

    private fun refreshState() {
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) { swap.readState() }
            swapStatus.text = if (st.entries.isEmpty()) {
                if (st.error != null) "Brak aktywnych obszarów swap\n(${st.error})"
                else "Brak aktywnych obszarów swap"
            } else {
                st.entries.joinToString("\n") { e ->
                    "${e.path}  [${e.type}]  ${swap.formatKb(e.sizeKb)} (użyte ${swap.formatKb(e.usedKb)})"
                } + "\nRazem: ${swap.formatKb(st.totalKb)}, użyte ${swap.formatKb(st.usedKb)}"
            }
            swappinessValue.text = buildString {
                append("vm.swappiness = ").append(st.swappiness)
                append("\nzRAM: ")
                append(if (st.zramDisks.isEmpty()) "brak urządzeń zram" else st.zramDisks.joinToString(", "))
            }
        }
    }

    /** Ekran ostrzeżenia o ryzyku — pokazywany raz na sesję przed operacjami root. */
    private fun withRootWarning(action: () -> Unit) {
        if (!RootShell.isRootAvailable()) {
            publish("Brak roota — operacja przerwana.")
            return
        }
        if (warningAccepted) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.root_warning_title)
            .setMessage(R.string.root_warning_body)
            .setCancelable(false)
            .setPositiveButton("Rozumiem, kontynuuj") { _, _ ->
                warningAccepted = true
                action()
            }
            .setNegativeButton("Anuluj") { _, _ -> publish("Operacja anulowana przez użytkownika.") }
            .show()
    }

    private fun runOp(title: String, block: () -> Boolean) {
        publish("— $title —")
        rootStatus.text = title
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { block() }
            publish(if (ok) "Wynik: OK" else "Wynik: NIEUDANE (sprawdź log powyżej)")
            rootStatus.text = if (ok) "Ostatnia operacja: OK" else "Ostatnia operacja: nieudana"
            refreshState()
        }
    }

    private fun publish(line: String) {
        runOnUiThread {
            log.append(line).append('\n')
            if (log.length > 8000) log.delete(0, log.length - 8000)
            rootLog.text = log.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }
}
