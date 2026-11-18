// HRAM — implementacja warstwy natywnej.
//
// Model: arena to plik rzadki mapowany przez mmap. Logiczne bloki o stałym
// rozmiarze leżą na flashu; RAM pełni rolę cache LRU nad blokami. Bloki są
// kompresowane LZ4 i chronione CRC32. Wszystkie pomiary są rzeczywiste.
#include "hram_core.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <thread>

#include "lz4/lz4.h"

namespace hram {
namespace {

// ---- CRC32 (IEEE, tabela generowana raz) -----------------------------------
uint32_t gCrcTable[256];
bool gCrcReady = false;

void initCrc() {
  if (gCrcReady) return;
  for (uint32_t i = 0; i < 256; ++i) {
    uint32_t c = i;
    for (int k = 0; k < 8; ++k) c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
    gCrcTable[i] = c;
  }
  gCrcReady = true;
}

uint32_t crc32(const void* data, size_t len) {
  initCrc();
  const uint8_t* p = static_cast<const uint8_t*>(data);
  uint32_t c = 0xFFFFFFFFu;
  for (size_t i = 0; i < len; ++i) c = gCrcTable[(c ^ p[i]) & 0xFFu] ^ (c >> 8);
  return c ^ 0xFFFFFFFFu;
}

inline uint64_t nowNs() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
         static_cast<uint64_t>(ts.tv_nsec);
}

// Wzorzec "danych aplikacji": tekst strukturalny z powtarzalnymi słowami
// i zmieniającym się licznikiem. Odzwierciedla realne dane (JSON/tekst/logi),
// które LZ4 faktycznie kompresuje. Nie jest to szum — szum kompresuje się 1:1.
const char* kWords[] = {"hram ", "block ", "cache ", "lz4 ",  "android ",
                        "flash ", "ram ",   "page ",  "swap ", "zram "};
constexpr int kWordsN = 10;

void fillPattern(uint8_t* buf, size_t len, uint32_t seed) {
  size_t i = 0;
  uint32_t counter = seed;
  char num[16];
  while (i < len) {
    const char* w = kWords[(counter >> 3) % kWordsN];
    const size_t wl = std::strlen(w);
    const size_t n_room = std::min(wl, len - i);
    std::memcpy(buf + i, w, n_room);
    i += n_room;
    if (i + 12 <= len && (counter & 0x1Fu) == 0) {
      const int written = std::snprintf(num, sizeof(num), "%08u", counter);
      if (written > 0) {
        const size_t nl = std::min(static_cast<size_t>(written), len - i);
        std::memcpy(buf + i, num, nl);
        i += nl;
      }
    }
    counter += 1u;
  }
}

// Wzorzec nieściśliwy (entropijny) — do uczciwego pomiaru "najgorszego przypadku".
void fillRandom(uint8_t* buf, size_t len, uint32_t seed) {
  uint32_t x = seed | 1u;
  for (size_t i = 0; i < len; ++i) {
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    buf[i] = static_cast<uint8_t>(x >> 24);
  }
}

}  // namespace

double throughputMbps(double bytes, double ns) {
  if (ns <= 0.0) return 0.0;
  return (bytes / (1024.0 * 1024.0)) / (ns / 1e9);
}

Arena::Arena() = default;

Arena::~Arena() { close(); }

uint8_t* Arena::blockPtr(uint64_t idx) const {
  return map_ + static_cast<size_t>(idx) * perBlockRegion_;
}

uint64_t Arena::allocatedBytes() const {
  if (fd_ < 0) return 0;
  struct stat st;
  if (fstat(fd_, &st) != 0) return 0;
  return static_cast<uint64_t>(st.st_blocks) * 512ull;
}

bool Arena::open(const ArenaConfig& cfg, std::string* err) {
  close();
  if (cfg.blockSize < 4096 || cfg.blockCount == 0) {
    if (err) *err = "blockSize musi byc >= 4096, blockCount > 0";
    return false;
  }
  // Blok musi zmieścić nagłówek plus ewentualny surowy payload.
  if (cfg.blockSize <= kHeaderSize + 64) {
    if (err) *err = "blockSize za maly wzgledem naglowka";
    return false;
  }
  cfg_ = cfg;
  perBlockRegion_ = cfg.blockSize;

  fd_ = ::open(cfg_.path.c_str(), O_RDWR | O_CREAT, 0600);
  if (fd_ < 0) {
    if (err) *err = std::string("open: ") + strerror(errno);
    return false;
  }
  const size_t len = perBlockRegion_ * cfg_.blockCount;
  if (ftruncate(fd_, static_cast<off_t>(len)) != 0) {
    if (err) *err = std::string("ftruncate: ") + strerror(errno);
    ::close(fd_);
    fd_ = -1;
    return false;
  }
  void* m = mmap(nullptr, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
  if (m == MAP_FAILED) {
    if (err) *err = std::string("mmap: ") + strerror(errno);
    ::close(fd_);
    fd_ = -1;
    return false;
  }
  map_ = static_cast<uint8_t*>(m);
  mapLen_ = len;

  std::lock_guard<std::mutex> lk(mu_);
  clearLocked();
  st_ = ArenaStats{};
  return true;
}

void Arena::close() {
  if (map_) {
    msync(map_, mapLen_, MS_SYNC);
    munmap(map_, mapLen_);
    map_ = nullptr;
  }
  if (fd_ >= 0) {
    ::close(fd_);
    fd_ = -1;
  }
  mapLen_ = 0;
  std::lock_guard<std::mutex> lk(mu_);
  clearLocked();
}

void Arena::clearLocked() {
  cache_.clear();
  lru_.clear();
  lruPos_.clear();
  cacheBytes_ = 0;
}

void Arena::insertLocked(uint64_t idx, std::vector<uint8_t>&& data) {
  auto it = cache_.find(idx);
  if (it != cache_.end()) {
    // Aktualizacja istniejącego wpisu.
    cacheBytes_ -= it->second.data.size();
    it->second.data = std::move(data);
    cacheBytes_ += it->second.data.size();
    auto pos = lruPos_.find(idx);
    if (pos != lruPos_.end()) lru_.erase(pos->second);
    lru_.push_front(idx);
    lruPos_[idx] = lru_.begin();
    return;
  }
  const size_t sz = data.size();
  cache_[idx] = Entry{std::move(data)};
  cacheBytes_ += sz;
  lru_.push_front(idx);
  lruPos_[idx] = lru_.begin();
  evictLocked();
}

void Arena::evictLocked() {
  while (cacheBytes_ > cfg_.cacheBytes && !lru_.empty()) {
    uint64_t victim = lru_.back();
    lru_.pop_back();
    auto pit = lruPos_.find(victim);
    if (pit != lruPos_.end()) lruPos_.erase(pit);
    auto it = cache_.find(victim);
    if (it != cache_.end()) {
      cacheBytes_ -= it->second.data.size();
      cache_.erase(it);
      st_.evictions++;
    }
  }
}

bool Arena::writeBlock(uint64_t idx, const void* data, size_t len) {
  if (!isOpen() || idx >= cfg_.blockCount || len > perBlockRegion_ - kHeaderSize) {
    return false;
  }
  const uint8_t* src = static_cast<const uint8_t*>(data);
  uint8_t* dst = blockPtr(idx);

  std::vector<uint8_t> tmp;
  const uint8_t* payload = src;
  size_t payloadLen = len;
  uint32_t compSize = 0;

  if (cfg_.compress && len > 0) {
    tmp.resize(static_cast<size_t>(LZ4_compressBound(static_cast<int>(len))));
    const int c = LZ4_compress_default(reinterpret_cast<const char*>(src),
                                       reinterpret_cast<char*>(tmp.data()),
                                       static_cast<int>(len),
                                       static_cast<int>(tmp.size()));
    if (c > 0 && static_cast<size_t>(c) < len) {
      payload = tmp.data();
      payloadLen = static_cast<size_t>(c);
      compSize = static_cast<uint32_t>(c);
    }
  }

  BlockHeader hdr{};
  hdr.magic = kBlockMagic;
  hdr.rawSize = static_cast<uint32_t>(len);
  hdr.compSize = compSize;
  hdr.crc = crc32(payload, payloadLen);

  std::memcpy(dst, &hdr, kHeaderSize);
  if (payloadLen > 0) std::memcpy(dst + kHeaderSize, payload, payloadLen);

  std::vector<uint8_t> forCache(src, src + len);
  {
    std::lock_guard<std::mutex> lk(mu_);
    insertLocked(idx, std::move(forCache));
    st_.writes++;
    st_.bytesIn += len;
    st_.bytesStored += payloadLen;
  }
  return true;
}

bool Arena::readBlock(uint64_t idx, void* out, size_t outCap, size_t* outLen) {
  if (!isOpen() || idx >= cfg_.blockCount) return false;

  {
    std::lock_guard<std::mutex> lk(mu_);
    st_.reads++;
    auto it = cache_.find(idx);
    if (it != cache_.end()) {
      const size_t n = std::min(outCap, it->second.data.size());
      std::memcpy(out, it->second.data.data(), n);
      if (outLen) *outLen = n;
      st_.ramHits++;
      // Odśwież pozycję LRU.
      auto pos = lruPos_.find(idx);
      if (pos != lruPos_.end()) {
        lru_.erase(pos->second);
        lru_.push_front(idx);
        lruPos_[idx] = lru_.begin();
      }
      return true;
    }
  }

  const uint8_t* src = blockPtr(idx);
  BlockHeader hdr{};
  std::memcpy(&hdr, src, kHeaderSize);
  if (hdr.magic != kBlockMagic || hdr.rawSize > perBlockRegion_ - kHeaderSize) {
    return false;  // blok nigdy nie zapisany albo uszkodzony
  }
  const uint8_t* payload = src + kHeaderSize;
  const size_t payloadLen = hdr.compSize ? hdr.compSize : hdr.rawSize;
  if (crc32(payload, payloadLen) != hdr.crc) return false;

  std::vector<uint8_t> decoded(hdr.rawSize);
  if (hdr.compSize) {
    const int d = LZ4_decompress_safe(reinterpret_cast<const char*>(payload),
                                      reinterpret_cast<char*>(decoded.data()),
                                      static_cast<int>(payloadLen),
                                      static_cast<int>(hdr.rawSize));
    if (d < 0) return false;
    decoded.resize(static_cast<size_t>(d));
  } else {
    std::memcpy(decoded.data(), payload, payloadLen);
    decoded.resize(payloadLen);
  }

  const size_t n = std::min(outCap, decoded.size());
  std::memcpy(out, decoded.data(), n);
  if (outLen) *outLen = n;

  std::lock_guard<std::mutex> lk(mu_);
  st_.diskReads++;
  insertLocked(idx, std::move(decoded));
  return true;
}

void Arena::flush() {
  if (map_) msync(map_, mapLen_, MS_SYNC);
}

void Arena::dropCache() {
  std::lock_guard<std::mutex> lk(mu_);
  clearLocked();
  if (map_) madvise(map_, mapLen_, MADV_DONTNEED);
}

ArenaStats Arena::stats() const {
  std::lock_guard<std::mutex> lk(mu_);
  ArenaStats s = st_;
  s.cacheBytes = cacheBytes_;
  s.fileAllocated = allocatedBytes();
  s.fileLogical = mapLen_;
  s.blockSize = perBlockRegion_;
  s.blockCount = cfg_.blockCount;
  return s;
}

int Arena::selfTest(uint64_t blocks) {
  if (!isOpen()) return -1;
  int errors = 0;
  const size_t bs = cfg_.blockSize;
  std::vector<uint8_t> a(bs), b(bs);
  const uint64_t n = std::min<uint64_t>(blocks, cfg_.blockCount);

  for (uint64_t i = 0; i < n; ++i) {
    const size_t len = bs - kHeaderSize;
    fillPattern(a.data(), len, static_cast<uint32_t>(i * 2654435761u + 12345));
    if (!writeBlock(i, a.data(), len)) { errors++; continue; }
  }
  dropCache();  // wymuś odczyt z flasha
  for (uint64_t i = 0; i < n; ++i) {
    const size_t len = bs - kHeaderSize;
    fillPattern(a.data(), len, static_cast<uint32_t>(i * 2654435761u + 12345));
    size_t got = 0;
    if (!readBlock(i, b.data(), b.size(), &got)) { errors++; continue; }
    if (got != len || std::memcmp(a.data(), b.data(), len) != 0) errors++;
  }
  return errors;
}

// ---------------------------------------------------------------------------
// Benchmark jednościeżkowy
// ---------------------------------------------------------------------------
BenchResult Arena::runBenchmark(uint64_t megabytes) {
  BenchResult r{};
  if (!isOpen()) return r;

  const size_t usable = cfg_.blockSize - kHeaderSize;
  const uint64_t wantBytes = megabytes << 20;
  uint64_t reps = std::max<uint64_t>(1, wantBytes / static_cast<uint64_t>(usable));
  const uint64_t maxBlocks = cfg_.blockCount;

  std::vector<uint8_t> buf(cfg_.blockSize);
  fillPattern(buf.data(), buf.size(), 0xC0FFEEu);

  // 1) Zapis sekwencyjny (kompresja + CRC + zapis do mmap).
  uint64_t t0 = nowNs();
  uint64_t moved = 0;
  for (uint64_t i = 0; i < reps; ++i) {
    const uint64_t idx = i % maxBlocks;
    writeBlock(idx, buf.data(), usable);
    moved += usable;
  }
  flush();
  uint64_t dt = nowNs() - t0;
  r.seqWriteMbps = throughputMbps(static_cast<double>(moved), static_cast<double>(dt));

  // 2) Odczyt z RAM (cache ciepły po zapisie).
  t0 = nowNs();
  std::vector<uint8_t> out(cfg_.blockSize);
  std::vector<uint8_t> verify(usable);
  uint64_t sink = 0;
  for (uint64_t i = 0; i < reps; ++i) {
    const uint64_t idx = i % maxBlocks;
    size_t got = 0;
    if (readBlock(idx, out.data(), out.size(), &got)) {
      // Dotknij całego odczytanego bufora, żeby pomiar nie był zoptymalizowany.
      std::memcpy(verify.data(), out.data(), std::min(got, verify.size()));
      sink += verify[i % verify.size()];
    }
  }
  dt = nowNs() - t0;
  r.seqReadRamMbps = throughputMbps(static_cast<double>(moved), static_cast<double>(dt));

  // 3) Odczyt z SSD (cache zimny).
  dropCache();
  t0 = nowNs();
  for (uint64_t i = 0; i < reps; ++i) {
    const uint64_t idx = i % maxBlocks;
    size_t got = 0;
    if (readBlock(idx, out.data(), out.size(), &got)) {
      std::memcpy(verify.data(), out.data(), std::min(got, verify.size()));
      sink += verify[i % verify.size()];
    }
  }
  dt = nowNs() - t0;
  r.seqReadDiskMbps = throughputMbps(static_cast<double>(moved), static_cast<double>(dt));

  // 4) Losowy odczyt: 64 KiB z cache.
  const size_t rnd = std::min<size_t>(64u << 10, usable);
  std::vector<uint8_t> rb(rnd);
  const uint64_t rndReps = std::max<uint64_t>(64, reps);
  uint32_t seed = 12345;
  t0 = nowNs();
  for (uint64_t i = 0; i < rndReps; ++i) {
    seed = seed * 1664525u + 1013904223u;
    const uint64_t idx = seed % maxBlocks;
    size_t got = 0;
    if (readBlock(idx, rb.data(), rb.size(), &got)) sink += got;
  }
  dt = nowNs() - t0;
  r.randReadRamMbps =
      throughputMbps(static_cast<double>(rnd * rndReps), static_cast<double>(dt));

  // 5) Losowy odczyt z SSD.
  dropCache();
  t0 = nowNs();
  for (uint64_t i = 0; i < rndReps; ++i) {
    seed = seed * 1664525u + 1013904223u;
    const uint64_t idx = seed % maxBlocks;
    size_t got = 0;
    if (readBlock(idx, rb.data(), rb.size(), &got)) sink += got;
  }
  dt = nowNs() - t0;
  r.randReadDiskMbps =
      throughputMbps(static_cast<double>(rnd * rndReps), static_cast<double>(dt));

  // 6) Surowy zapis do mmap, bez kompresji i bez CRC (sufit flasha).
  dropCache();
  const uint64_t rawBytes = std::min<uint64_t>(wantBytes, mapLen_);
  t0 = nowNs();
  for (uint64_t off = 0; off < rawBytes; off += cfg_.blockSize) {
    const size_t n = static_cast<size_t>(std::min<uint64_t>(cfg_.blockSize, rawBytes - off));
    std::memcpy(map_ + off, buf.data(), std::min(n, buf.size()));
  }
  flush();
  dt = nowNs() - t0;
  r.rawWriteMbps = throughputMbps(static_cast<double>(rawBytes), static_cast<double>(dt));

  const ArenaStats s = stats();
  r.compressRatio =
      s.bytesIn ? static_cast<double>(s.bytesStored) / static_cast<double>(s.bytesIn) : 0.0;
  r.bytesMoved = static_cast<double>(moved);
  (void)sink;
  return r;
}

// ---------------------------------------------------------------------------
// Benchmark wielokanałowy ("hiperprędkość")
// ---------------------------------------------------------------------------
HyperResult Arena::runHyper(size_t blockBytes, int channels, uint64_t megabytes) {
  HyperResult res{};
  blockBytes = std::max<size_t>(1u << 20, std::min<size_t>(blockBytes, 16u << 20));
  channels = std::max(1, std::min(channels, 8));
  res.blockBytes = blockBytes;
  res.channels = channels;

  const uint64_t totalTarget = std::max<uint64_t>(8ull << 20, megabytes << 20);
  uint64_t reps = totalTarget / (static_cast<uint64_t>(blockBytes) * channels);
  reps = std::max<uint64_t>(2, reps);

  const std::string hpath = cfg_.path.empty() ? std::string("/data/local/tmp/hyper.bin")
                                              : cfg_.path + ".hyper";
  const int fd = ::open(hpath.c_str(), O_RDWR | O_CREAT, 0600);
  if (fd < 0) return res;

  // +128 B zapasu na nagłówek i ewentualny payload bez kompresji.
  const size_t regionPerChannel = static_cast<size_t>(blockBytes) * reps + 128;
  const size_t total = regionPerChannel * static_cast<size_t>(channels);
  if (ftruncate(fd, static_cast<off_t>(total)) != 0) {
    ::close(fd);
    return res;
  }
  void* m = mmap(nullptr, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (m == MAP_FAILED) {
    ::close(fd);
    return res;
  }
  uint8_t* base = static_cast<uint8_t*>(m);

  // Wzorce: każdy kanał ma własny, żeby zapis nie był zoptymalizowany.
  std::vector<std::vector<uint8_t>> patterns(channels);
  for (int c = 0; c < channels; ++c) {
    patterns[c].resize(blockBytes);
    fillPattern(patterns[c].data(), blockBytes, 0xA5A50000u + static_cast<uint32_t>(c));
  }

  // Bariera startowa — wszystkie wątki ruszają w tej samej chwili.
  std::atomic<int> ready{0};
  std::atomic<bool> go{false};
  auto waitAll = [&]() {
    ready.fetch_add(1);
    while (!go.load(std::memory_order_acquire)) {
    }
  };

  struct Phase {
    std::vector<uint64_t> ns;      // czas każdego kanału
    std::vector<uint64_t> nsPerBlk;
  };

  auto runPhase = [&](int kind, Phase* out) {
    out->ns.assign(channels, 0);
    out->nsPerBlk.assign(channels, 0);
    ready.store(0);
    go.store(false);
    std::vector<std::thread> th;
    th.reserve(channels);
    for (int c = 0; c < channels; ++c) {
      th.emplace_back([&, c]() {
        uint8_t* region = base + static_cast<size_t>(c) * regionPerChannel;
        const uint8_t* pat = patterns[c].data();
        std::vector<uint8_t> scratch(blockBytes + 64);
        std::vector<uint8_t> decoded(blockBytes);
        waitAll();
        uint64_t t0 = nowNs();
        uint64_t minBlk = ~0ull;
        for (uint64_t i = 0; i < reps; ++i) {
          uint8_t* dst = region + static_cast<size_t>(i) * blockBytes;
          const uint64_t b0 = nowNs();
          switch (kind) {
            case 0:  // surowy zapis
              std::memcpy(dst, pat, blockBytes);
              break;
            case 1: {  // surowy odczyt — kopiujemy cały blok, by zmusić CPU do ruchu
              std::memcpy(decoded.data(), dst, blockBytes);
              static std::atomic<uint64_t> gSink{0};
              gSink.fetch_add(decoded[i % blockBytes], std::memory_order_relaxed);
              break;
            }
            case 2: {  // HRAM zapis: kompresja + CRC + nagłówek
              const int n = LZ4_compress_default(reinterpret_cast<const char*>(pat),
                                                 reinterpret_cast<char*>(scratch.data()),
                                                 static_cast<int>(blockBytes),
                                                 static_cast<int>(scratch.size()));
              BlockHeader hdr{};
              hdr.magic = kBlockMagic;
              hdr.rawSize = static_cast<uint32_t>(blockBytes);
              hdr.compSize = n > 0 ? static_cast<uint32_t>(n) : 0u;
              hdr.crc = crc32(n > 0 ? scratch.data() : pat,
                              n > 0 ? static_cast<size_t>(n) : blockBytes);
              std::memcpy(dst, &hdr, kHeaderSize);
              std::memcpy(dst + kHeaderSize, n > 0 ? scratch.data() : pat,
                          n > 0 ? static_cast<size_t>(n) : blockBytes);
              break;
            }
            case 3: {  // HRAM odczyt: CRC + dekompresja
              BlockHeader hdr{};
              std::memcpy(&hdr, dst, kHeaderSize);
              const uint8_t* payload = dst + kHeaderSize;
              const size_t plen = hdr.compSize ? hdr.compSize : hdr.rawSize;
              crc32(payload, std::min(plen, blockBytes));
              if (hdr.compSize) {
                LZ4_decompress_safe(reinterpret_cast<const char*>(payload),
                                    reinterpret_cast<char*>(decoded.data()),
                                    static_cast<int>(plen),
                                    static_cast<int>(decoded.size()));
              }
              break;
            }
          }
          const uint64_t b1 = nowNs();
          minBlk = std::min(minBlk, b1 - b0);
        }
        const uint64_t dt = nowNs() - t0;
        out->ns[c] = dt;
        out->nsPerBlk[c] = minBlk;
      });
    }
    while (ready.load() < channels) {
    }
    go.store(true, std::memory_order_release);
    for (auto& t : th) t.join();
  };

  Phase rawW, rawR, hramW, hramR;
  const uint64_t bytesPerChannel = static_cast<uint64_t>(blockBytes) * reps;
  const uint64_t totalBytes = bytesPerChannel * static_cast<uint64_t>(channels);

  runPhase(0, &rawW);
  msync(m, total, MS_SYNC);
  madvise(m, total, MADV_DONTNEED);
  runPhase(1, &rawR);
  runPhase(2, &hramW);
  msync(m, total, MS_SYNC);
  madvise(m, total, MADV_DONTNEED);
  runPhase(3, &hramR);

  auto maxOf = [](const std::vector<uint64_t>& v) -> uint64_t {
    uint64_t m2 = 0;
    for (uint64_t x : v) m2 = std::max(m2, x);
    return m2;
  };
  auto avgOf = [](const std::vector<uint64_t>& v) -> uint64_t {
    if (v.empty()) return 0;
    uint64_t s = 0;
    for (uint64_t x : v) s += x;
    return s / static_cast<uint64_t>(v.size());
  };

  const uint64_t tRawW = std::max<uint64_t>(1, maxOf(rawW.ns));
  const uint64_t tRawR = std::max<uint64_t>(1, maxOf(rawR.ns));
  const uint64_t tHramW = std::max<uint64_t>(1, maxOf(hramW.ns));
  const uint64_t tHramR = std::max<uint64_t>(1, maxOf(hramR.ns));

  res.storageWriteMbps = throughputMbps(static_cast<double>(totalBytes), static_cast<double>(tRawW));
  res.storageReadMbps = throughputMbps(static_cast<double>(totalBytes), static_cast<double>(tRawR));
  res.writeMbps = throughputMbps(static_cast<double>(totalBytes), static_cast<double>(tHramW));
  res.readMbps = throughputMbps(static_cast<double>(totalBytes), static_cast<double>(tHramR));
  res.nsPerBlockAvg = static_cast<double>(tHramW) / static_cast<double>(reps);
  res.nsPerBlockMin = static_cast<double>(avgOf(hramW.nsPerBlk));
  res.nsRawPerBlockAvg = static_cast<double>(tRawW) / static_cast<double>(reps);
  res.blocks = reps * static_cast<uint64_t>(channels);

  res.channelWriteMbps.resize(channels);
  for (int c = 0; c < channels; ++c) {
    res.channelWriteMbps[c] = throughputMbps(
        static_cast<double>(bytesPerChannel), static_cast<double>(std::max<uint64_t>(1, hramW.ns[c])));
  }

  const ArenaStats s = stats();
  res.compressRatio =
      s.bytesIn ? static_cast<double>(s.bytesStored) / static_cast<double>(s.bytesIn) : 0.0;

  munmap(m, total);
  ::close(fd);
  ::unlink(hpath.c_str());
  return res;
}

}  // namespace hram
