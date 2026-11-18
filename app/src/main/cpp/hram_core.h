// HRAM — warstwowa pamięć: arena na pliku rzadkim + mmap, bloki stałego rozmiaru,
// kompresja LZ4, cache LRU w RAM nad blokami na dysku (flash).
//
// Ten nagłówek definiuje publiczne API warstwy natywnej. Nie zawiera logiki UI.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace hram {

constexpr uint32_t kBlockMagic = 0x4D415248u;  // "HRAM" little-endian
constexpr uint32_t kFormatVersion = 1u;
constexpr size_t kHeaderSize = 16;  // 4 x uint32

// Nagłówek bloku zapisany na początku każdego bloku w arenie.
struct BlockHeader {
  uint32_t magic;
  uint32_t rawSize;   // rozmiar danych przed kompresją
  uint32_t compSize;  // 0 = dane zapisane bez kompresji (surowy zapis)
  uint32_t crc;       // CRC32 zapisanego payloadu
};
static_assert(sizeof(BlockHeader) == kHeaderSize, "BlockHeader musi miec 16B");

struct ArenaConfig {
  std::string path;
  size_t blockSize = 1u << 20;    // 1 MiB logicznego bloku
  size_t blockCount = 64;         // 64 bloki => 64 MiB areny
  size_t cacheBytes = 16u << 20;  // budżet cache w RAM
  bool compress = true;
};

struct ArenaStats {
  uint64_t reads = 0;
  uint64_t writes = 0;
  uint64_t ramHits = 0;
  uint64_t diskReads = 0;
  uint64_t bytesIn = 0;      // bajty danych wejściowych
  uint64_t bytesStored = 0;  // bajty faktycznie zapisane na flashu
  uint64_t evictions = 0;
  uint64_t cacheBytes = 0;
  uint64_t fileAllocated = 0;  // st_blocks * 512 (realne zajęcie dysku)
  uint64_t fileLogical = 0;    // ftruncate size (rozmiar rzadki)
  uint64_t blockSize = 0;
  uint64_t blockCount = 0;
};

struct BenchResult {
  double seqWriteMbps = 0;
  double seqReadRamMbps = 0;
  double seqReadDiskMbps = 0;
  double randReadRamMbps = 0;
  double randReadDiskMbps = 0;
  double rawWriteMbps = 0;
  double compressRatio = 0;
  double bytesMoved = 0;
};

// Wynik benchmarku wielokanałowego ("hiperprędkość").
// Wszystkie wartości są ZMIERZONE; nic nie jest deklarowane z góry.
struct HyperResult {
  double writeMbps = 0;         // pełna ścieżka HRAM: kompresja + CRC + zapis na flash
  double readMbps = 0;          // pełna ścieżka HRAM: odczyt + CRC + dekompresja
  double storageWriteMbps = 0;  // tylko zapis bajtów do mmap (bez kompresji/CRC)
  double storageReadMbps = 0;   // tylko odczyt bajtów z mmap
  double nsPerBlockAvg = 0;     // średni czas pojedynczego transferu bloku (pełna ścieżka)
  double nsPerBlockMin = 0;     // najszybszy zmierzony transfer bloku
  double nsRawPerBlockAvg = 0;  // średni czas surowego transferu bloku
  std::vector<double> channelWriteMbps;
  uint64_t blocks = 0;
  uint64_t blockBytes = 0;
  int channels = 0;
  double compressRatio = 0;
};

class Arena {
 public:
  Arena();
  ~Arena();

  Arena(const Arena&) = delete;
  Arena& operator=(const Arena&) = delete;

  bool open(const ArenaConfig& cfg, std::string* err);
  void close();
  bool isOpen() const { return map_ != nullptr; }

  // Zapis/odczyt pojedynczego bloku logicznego (indeks < blockCount).
  bool writeBlock(uint64_t idx, const void* data, size_t len);
  bool readBlock(uint64_t idx, void* out, size_t outCap, size_t* outLen);

  void flush();
  void dropCache();
  ArenaStats stats() const;

  // Benchmark jednościeżkowy (ścieżka areny, z cache LRU).
  BenchResult runBenchmark(uint64_t megabytes);

  // Benchmark wielokanałowy ("hiperprędkość").
  HyperResult runHyper(size_t blockBytes, int channels, uint64_t megabytes);

  // Test poprawności: zapis wzorca, odczyt, porównanie. Zwraca liczbę błędów.
  int selfTest(uint64_t blocks);

  const ArenaConfig& config() const { return cfg_; }

 private:
  struct Entry {
    std::vector<uint8_t> data;
  };

  void insertLocked(uint64_t idx, std::vector<uint8_t>&& data);
  void evictLocked();
  void clearLocked();
  uint8_t* blockPtr(uint64_t idx) const;
  uint64_t allocatedBytes() const;

  ArenaConfig cfg_;
  int fd_ = -1;
  uint8_t* map_ = nullptr;
  size_t mapLen_ = 0;
  size_t perBlockRegion_ = 0;  // = blockSize

  mutable std::mutex mu_;
  std::unordered_map<uint64_t, Entry> cache_;
  std::list<uint64_t> lru_;  // front = najnowszy
  std::unordered_map<uint64_t, std::list<uint64_t>::iterator> lruPos_;
  size_t cacheBytes_ = 0;

  mutable ArenaStats st_{};
};

// Pomocnicza matematyka przepustowości — używana także w testach na hoście.
// Zwraca przepustowość w MB/s dla danego rozmiaru transferu i czasu w ns.
double throughputMbps(double bytes, double ns);

}  // namespace hram
