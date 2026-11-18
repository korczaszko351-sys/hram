# HRAM — warstwowa pamięć: RAM jako cache, bloki na SSD (flash)

Aplikacja Android (Kotlin + NDK/C++) implementująca **warstwę pamięci HRAM**:
dane trzymane są w stałych blokach na pamięci flash urządzenia, a RAM służy
jako cache LRU nad tymi blokami. Do tego panel roota (swap, zRAM, swappiness)
i ekran **Hiperprędkość** z wielokanałowym benchmarkiem przepustowości.

---

## Uczciwość pomiarów — przeczytaj najpierw

Aplikacja **nie** deklaruje 14 GB/s ani 14000 MT/s jako osiąganej przepustowości.
To wartości **specyfikacji DRAM**, a nie pamięci flash w telefonie. Interfejs
pokazuje wyłącznie przepustowość **zmierzoną** na danym urządzeniu.

**HRAM rozszerza pojemność pamięci kosztem szybkości — nie dodaje fizycznego RAM.**

### Założenie „10,24 MB w 10 nanosekund”

Ekran Hiperprędkość liczy to wprost:

```
10,24 MB = 10 240 000 B
10 ns    = 1e-8 s

przepustowość = 10 240 000 / 1e-8 = 1,024e15 B/s = 1 024 000 GB/s = 1024 TB/s
```

To daje **~1 PB/s**, a nie 14 GB/s. Żeby uzyskać 14 GB/s, 10,24 MB musi
przejść w **~731 µs**:

```
10 240 000 B / 14e9 B/s = 731,4 µs
```

Światło w 10 ns pokonuje ~3 m, więc liczba „10,24 MB w 10 ns” jest o rzędy
wielkości poza jakąkolwiek fizyką pamięci — aplikacja pokazuje to jako
weryfikację arytmetyki, a nie jako obietnicę.

---

## Co robi warstwa HRAM (NDK/C++)

| Element | Implementacja |
|---|---|
| Arena | plik rzadki (`ftruncate`) + `mmap(MAP_SHARED)` |
| Bloki | stały rozmiar (np. 1 MiB), nagłówek 16 B (magic, rawSize, compSize, CRC32) |
| Kompresja | LZ4 (pobierany skryptem `tools/fetch-lz4.sh`, licencja BSD-2) |
| Cache | LRU w RAM nad blokami na dysku, budżet konfigurowalny |
| Integralność | CRC32 IEEE payloadu przy każdym odczycie |
| Stronicowanie | na żądanie: blok czytany z flasha przy chybieniu |
| Prefetch | sekwencyjny odczyt kolejnych bloków; `dropCache()` używa `madvise(MADV_DONTNEED)` |
| Zapis | `msync(MS_SYNC)` na żądanie (flush) |

Kod: `app/src/main/cpp/hram_core.cpp`, mostek `hram_jni.cpp`.

### Formuła przepustowości

```
przepustowość [MB/s] = (bajty / 1 048 576) / (nanosekundy / 1e9)
```

Ta sama formuła jest użyta w `HyperMath.kt` i w `hram_core.cpp` (`throughputMbps`).

---

## Ekran Hiperprędkość

- Suwaki: **rozmiar bloku 1–16 MB**, **liczba kanałów 1–8** (osobny wątek na kanał).
- Pomiar **wielokanałowy** z barierą startową: surowy zapis, surowy odczyt,
  pełna ścieżka zapisu HRAM (kompresja + CRC), pełna ścieżka odczytu HRAM.
- Wynik: **MB/s i GB/s**, czas na transfer bloku w µs, przepustowość per kanał.
- Pasek postępu do celu 14 GB/s + ile brakuje + wskazanie wąskiego gardła
  (CPU/kompresja vs flash/kontroler) — wyliczone z relacji zmierzonych warstw.

---

## Panel roota (opcjonalny)

Wymaga `su`. Bez roota aplikacja działa dalej — arena i pomiary nie potrzebują roota.

- Wykrywanie roota (`su -c id`, timeout 4 s — nie zawiesza UI).
- Plik wymiany: `dd` → `chmod 600` → `mkswap` → `swapon`, wyłączanie `swapoff` + `rm`.
- zRAM: rozmiar z `/sys/block/zram*/disksize`, `mkswap`, `swapon`.
- `vm.swappiness` (0–200).
- Stan z `/proc/swaps` i `/proc/sys/vm/swappiness`.
- **Przed pierwszą operacją: ekran ostrzeżenia o ryzyku** (zużycie flasha,
  wolniejsze działanie, możliwy bootloop, utrata danych).

---

## Limity, których nie da się obejść

| Limit | Charakter | Czy aplikacja może go zmienić |
|---|---|---|
| Fizyczna ilość RAM | sprzęt | nie |
| Przepustowość flasha (UFS/eMMC) | sprzęt | nie |
| Limit sterty ART (`memoryClass`) | system, per aplikacja | `largeHeap` podnosi do `largeMemoryClass` |
| Próg LMK / low-memory-killer | system | nie (tylko root/ROM) |
| zRAM / swap | system | tak, przez root |

`android:largeHeap="true"` jest ustawione w manifeście — podnosi limit **sterty Javy**,
nie fizyczny RAM. Alokacje natywne (arena) nie liczą się do limitu sterty Javy.

---

## Budowanie

### GitHub Actions (zalecane — nic nie instalujesz lokalnie)

Workflow `.github/workflows/android.yml` kompiluje APK przy każdym pushu:

1. Otwórz repozytorium → zakładka **Actions**.
2. Wybierz przebieg **Build APK** (zielony znaczek).
3. Na dole strony, sekcja **Artifacts** → pobierz **`hram-apk`** (ZIP).
4. Rozpakuj — w środku jest `app-debug.apk`.

### Lokalnie (Android Studio)

```bash
# JDK 17, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1
gradle :app:assembleDebug
# wynik: app/build/outputs/apk/debug/app-debug.apk
```

### Instalacja na telefonie

1. Skopiuj `app-debug.apk` na telefon.
2. Ustawienia → **Zezwól na instalację z nieznanych źródeł** dla menedżera plików/przeglądarki.
3. Otwórz APK → **Zainstaluj**. Jeśli Android zgłosi „aplikacja niezaufana”,
to normalne dla APK debug podpisanego kluczem debug — nie jest w Google Play.
4. Alternatywnie przez ADB:

```bash
adb install -r app-debug.apk
```

---

## Testy wykonane przed publikacją

Warstwa natywna została skompilowana i uruchomiona **na hoście Linux** (g++, x86_64),
zanim trafiła do projektu Androida:

- `selfTest`: zapis 32 bloków wzorca, `dropCache`, odczyt i porównanie → **0 błędów**,
- kompresja danych tekstowych: **0,032** (≈ 1:31),
- benchmarki i `runHyper` zwracają wartości niezerowe i rosnące z liczbą kanałów.

Czego **nie** sprawdzono: działania na prawdziwym telefonie (brak urządzenia
w środowisku budowania) — dlatego liczby w UI są zawsze mierzone lokalnie,
a nie zaszyte.

---

## Struktura

```
app/src/main/cpp/
  hram_core.h / hram_core.cpp   arena, LRU, LZ4, CRC32, benchmarki
  hram_jni.cpp                  mostek JNI
  lz4/                          LZ4 1.9.4 (BSD-2, pobierany skryptem)
app/src/main/java/pl/hram/app/
  HramNative.kt                 wrapper JNI + typy wyników
  HyperMath.kt                  matematyka przepustowości, weryfikacja założeń
  SwapManager.kt                swap / zRAM / swappiness
  RootShell.kt                  wykrywanie i wykonywanie jako su
  MemInfo.kt                    limity RAM, heap, dysk, ABI
  MainActivity.kt               status + arena + benchmark
  HyperActivity.kt              ekran Hiperprędkość
  RootActivity.kt               panel roota + ostrzeżenie
```

## Licencje

- Kod aplikacji: MIT.
- LZ4: BSD-2-Clause (pobierany z oficjalnego taga v1.9.4).
