// Mostek JNI: Kotlin <-> warstwa natywna HRAM.
// Uchwyty są wskaźnikami na hram::Arena rzutowanymi na jlong.
#include <jni.h>

#include <string>
#include <vector>

#include "hram_core.h"

namespace {

std::string gLastError;

hram::Arena* asArena(jlong h) { return reinterpret_cast<hram::Arena*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_pl_hram_app_HramNative_nativeVersion(JNIEnv* env, jclass) {
  return env->NewStringUTF("HRAM native 1.0 (LZ4 block arena)");
}

JNIEXPORT jstring JNICALL
Java_pl_hram_app_HramNative_nativeLastError(JNIEnv* env, jclass) {
  return env->NewStringUTF(gLastError.c_str());
}

JNIEXPORT jlong JNICALL
Java_pl_hram_app_HramNative_nativeOpen(JNIEnv* env, jclass, jstring path,
                                       jlong blockSize, jlong blockCount,
                                       jlong cacheBytes, jboolean compress) {
  const char* p = env->GetStringUTFChars(path, nullptr);
  if (!p) return 0;
  hram::ArenaConfig cfg;
  cfg.path = p;
  cfg.blockSize = static_cast<size_t>(blockSize);
  cfg.blockCount = static_cast<size_t>(blockCount);
  cfg.cacheBytes = static_cast<size_t>(cacheBytes);
  cfg.compress = compress == JNI_TRUE;
  env->ReleaseStringUTFChars(path, p);

  auto* arena = new hram::Arena();
  std::string err;
  if (!arena->open(cfg, &err)) {
    gLastError = err;
    delete arena;
    return 0;
  }
  gLastError.clear();
  return reinterpret_cast<jlong>(arena);
}

JNIEXPORT void JNICALL
Java_pl_hram_app_HramNative_nativeClose(JNIEnv*, jclass, jlong h) {
  if (h == 0) return;
  delete asArena(h);
}

JNIEXPORT jboolean JNICALL
Java_pl_hram_app_HramNative_nativeWriteBlock(JNIEnv* env, jclass, jlong h, jlong idx,
                                             jbyteArray data, jint len) {
  if (h == 0 || data == nullptr) return JNI_FALSE;
  hram::Arena* a = asArena(h);
  jsize cap = env->GetArrayLength(data);
  const jsize n = len < 0 ? cap : (len < cap ? len : cap);
  std::vector<uint8_t> buf(static_cast<size_t>(n));
  env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(buf.data()));
  return a->writeBlock(static_cast<uint64_t>(idx), buf.data(), buf.size()) ? JNI_TRUE
                                                                          : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_pl_hram_app_HramNative_nativeReadBlock(JNIEnv* env, jclass, jlong h, jlong idx,
                                            jbyteArray out) {
  if (h == 0 || out == nullptr) return -1;
  hram::Arena* a = asArena(h);
  const jsize cap = env->GetArrayLength(out);
  std::vector<uint8_t> buf(static_cast<size_t>(cap));
  size_t got = 0;
  if (!a->readBlock(static_cast<uint64_t>(idx), buf.data(), buf.size(), &got)) return -1;
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(got),
                          reinterpret_cast<const jbyte*>(buf.data()));
  return static_cast<jint>(got);
}

JNIEXPORT jlongArray JNICALL
Java_pl_hram_app_HramNative_nativeStats(JNIEnv* env, jclass, jlong h) {
  // Kolejność pól musi się zgadzać z HramNative.STATS_LEN.
  const int N = 12;
  jlong v[N] = {0};
  if (h != 0) {
    const hram::ArenaStats s = asArena(h)->stats();
    v[0] = static_cast<jlong>(s.reads);
    v[1] = static_cast<jlong>(s.writes);
    v[2] = static_cast<jlong>(s.ramHits);
    v[3] = static_cast<jlong>(s.diskReads);
    v[4] = static_cast<jlong>(s.bytesIn);
    v[5] = static_cast<jlong>(s.bytesStored);
    v[6] = static_cast<jlong>(s.evictions);
    v[7] = static_cast<jlong>(s.cacheBytes);
    v[8] = static_cast<jlong>(s.fileAllocated);
    v[9] = static_cast<jlong>(s.fileLogical);
    v[10] = static_cast<jlong>(s.blockSize);
    v[11] = static_cast<jlong>(s.blockCount);
  }
  jlongArray arr = env->NewLongArray(N);
  if (arr) env->SetLongArrayRegion(arr, 0, N, v);
  return arr;
}

JNIEXPORT void JNICALL
Java_pl_hram_app_HramNative_nativeFlush(JNIEnv*, jclass, jlong h) {
  if (h) asArena(h)->flush();
}

JNIEXPORT void JNICALL
Java_pl_hram_app_HramNative_nativeDropCache(JNIEnv*, jclass, jlong h) {
  if (h) asArena(h)->dropCache();
}

JNIEXPORT jint JNICALL
Java_pl_hram_app_HramNative_nativeSelfTest(JNIEnv*, jclass, jlong h, jint blocks) {
  if (h == 0) return -1;
  return asArena(h)->selfTest(static_cast<uint64_t>(blocks));
}

JNIEXPORT jdoubleArray JNICALL
Java_pl_hram_app_HramNative_nativeBench(JNIEnv* env, jclass, jlong h, jint megabytes) {
  // seqWrite, seqReadRAM, seqReadSSD, randRAM, randSSD, rawWrite, ratio
  const int N = 7;
  jdouble v[N] = {0};
  if (h != 0) {
    const hram::BenchResult r = asArena(h)->runBenchmark(static_cast<uint64_t>(megabytes));
    v[0] = r.seqWriteMbps;
    v[1] = r.seqReadRamMbps;
    v[2] = r.seqReadDiskMbps;
    v[3] = r.randReadRamMbps;
    v[4] = r.randReadDiskMbps;
    v[5] = r.rawWriteMbps;
    v[6] = r.compressRatio;
  }
  jdoubleArray arr = env->NewDoubleArray(N);
  if (arr) env->SetDoubleArrayRegion(arr, 0, N, v);
  return arr;
}

JNIEXPORT jdoubleArray JNICALL
Java_pl_hram_app_HramNative_nativeHyper(JNIEnv* env, jclass, jlong h, jlong blockBytes,
                                        jint channels, jint megabytes) {
  // [0] writeMbps        [1] readMbps        [2] storageWriteMbps [3] storageReadMbps
  // [4] nsPerBlockAvg    [5] nsPerBlockMin   [6] nsRawPerBlockAvg [7] blocks
  // [8] blockBytes       [9] channels        [10] compressRatio  [11] channel count (kopie od 12)
  const int N = 12;
  std::vector<jdouble> v(N, 0.0);
  std::vector<double> ch;
  if (h != 0) {
    const hram::HyperResult r = asArena(h)->runHyper(static_cast<size_t>(blockBytes),
                                                     static_cast<int>(channels),
                                                     static_cast<uint64_t>(megabytes));
    v[0] = r.writeMbps;
    v[1] = r.readMbps;
    v[2] = r.storageWriteMbps;
    v[3] = r.storageReadMbps;
    v[4] = r.nsPerBlockAvg;
    v[5] = r.nsPerBlockMin;
    v[6] = r.nsRawPerBlockAvg;
    v[7] = static_cast<double>(r.blocks);
    v[8] = static_cast<double>(r.blockBytes);
    v[9] = static_cast<double>(r.channels);
    v[10] = r.compressRatio;
    ch = r.channelWriteMbps;
    v[11] = static_cast<double>(ch.size());
  }
  const int total = N + static_cast<int>(ch.size());
  std::vector<jdouble> all(total, 0.0);
  for (int i = 0; i < N; ++i) all[i] = v[i];
  for (size_t i = 0; i < ch.size(); ++i) all[N + i] = ch[i];
  jdoubleArray arr = env->NewDoubleArray(total);
  if (arr) env->SetDoubleArrayRegion(arr, 0, total, all.data());
  return arr;
}

}  // extern "C"
