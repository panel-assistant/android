// JNI bridge for media/SendspinNative.kt: one sendspin-cpp player client per handle.
//
// Threads: nCreate, nClientId, nConnect, nLoop, nNextEvent, nServerId and nDestroy run on one
// dedicated Kotlin thread (the library's main-loop thread). nRead, nPlayed and nNowUs run on the
// audio writer thread. nWriteRecord runs while no instance is alive for its directory.

#include <android/log.h>
#include <jni.h>

#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "sendspin/client.h"
#include "sendspin/persistence_codec.h"
#include "sendspin/persistence_keys.h"
#include "sendspin/player_role.h"

using namespace sendspin;

namespace {

constexpr const char* kTag = "ha-paneld/sendspin";
#define SHIM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

constexpr int kEventStreamStart = 1;
constexpr int kEventStreamEnd = 2;
constexpr int kEventTrustUser = 3;
constexpr int kEventTrustNone = 4;

std::string join_path(const std::string& dir, const std::string& name) {
    return dir + "/" + name;
}

bool ensure_dir(const std::string& dir) {
    if (mkdir(dir.c_str(), 0700) == 0 || errno == EEXIST) return true;
    SHIM_LOGE("mkdir failed: %s", strerror(errno));
    return false;
}

std::optional<std::vector<uint8_t>> read_file(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    if (f == nullptr) return std::nullopt;
    std::vector<uint8_t> out;
    uint8_t chunk[4096];
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) out.insert(out.end(), chunk, chunk + n);
    const bool failed = ferror(f) != 0;
    fclose(f);
    if (failed) return std::nullopt;
    return out;
}

// Writes a sibling temp file, fsyncs it and renames it over path, so a reader never sees a torn blob.
bool write_file_atomic(const std::string& path, const uint8_t* data, size_t len) {
    const std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (f == nullptr) {
        SHIM_LOGE("open for write failed: %s", strerror(errno));
        return false;
    }
    bool ok = (len == 0 || fwrite(data, 1, len, f) == len) && fflush(f) == 0 && fsync(fileno(f)) == 0;
    ok = (fclose(f) == 0) && ok;
    if (ok && rename(tmp.c_str(), path.c_str()) != 0) {
        SHIM_LOGE("rename failed: %s", strerror(errno));
        ok = false;
    }
    if (!ok) unlink(tmp.c_str());
    return ok;
}

struct FileStore : SendspinPersistenceProvider {
    std::string dir;
    std::optional<std::vector<uint8_t>> load_blob(const std::string& key) override {
        return read_file(join_path(dir, key));
    }
    bool save_blob(const std::string& key, const uint8_t* data, size_t len) override {
        return write_file_atomic(join_path(dir, key), data, len);
    }
    bool commit() override { return true; }
};

struct Net : SendspinNetworkProvider {
    bool is_network_ready() override { return true; }
};

// Frame-aligned byte ring between the library's sync thread (writer) and the audio thread (reader).
struct Ring {
    std::vector<uint8_t> buf;
    size_t r = 0, w = 0, n = 0;
    size_t frame;
    bool closed = false;
    std::mutex m;
    std::condition_variable cv;

    Ring(size_t cap, size_t frame_bytes) : buf(std::max(cap - cap % frame_bytes, frame_bytes)), frame(frame_bytes) {}

    size_t write(const uint8_t* d, size_t len, uint32_t timeout_ms) {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
        size_t done = 0;
        std::unique_lock<std::mutex> lk(m);
        while (done < len && !closed) {
            size_t k = std::min(buf.size() - n, len - done);
            if (k < len - done) k -= k % frame;
            for (size_t i = 0; i < k; i++) {
                buf[w] = d[done + i];
                w = (w + 1) % buf.size();
            }
            n += k;
            done += k;
            if (k > 0) cv.notify_all();
            if (done < len && cv.wait_until(lk, deadline) == std::cv_status::timeout &&
                buf.size() - n < frame) {
                break;
            }
        }
        return closed ? 0 : done;
    }

    size_t read(uint8_t* d, size_t max, int wait_ms) {
        std::unique_lock<std::mutex> lk(m);
        if (n == 0 && !closed && wait_ms > 0) {
            cv.wait_for(lk, std::chrono::milliseconds(wait_ms), [&] { return n > 0 || closed; });
        }
        if (closed) return 0;
        size_t k = std::min(max, n);
        k -= k % frame;
        for (size_t i = 0; i < k; i++) {
            d[i] = buf[r];
            r = (r + 1) % buf.size();
        }
        n -= k;
        if (k > 0) cv.notify_all();
        return k;
    }

    void clear() {
        std::lock_guard<std::mutex> lk(m);
        r = w = n = 0;
        cv.notify_all();
    }

    void close() {
        std::lock_guard<std::mutex> lk(m);
        closed = true;
        cv.notify_all();
    }
};

struct Instance;

struct PlayerListener : PlayerRoleListener {
    Instance* inst = nullptr;
    size_t on_audio_write(uint8_t* data, size_t length, uint32_t timeout_ms) override;
    void on_stream_start() override;
    void on_stream_end() override;
};

struct ClientListener : SendspinClientListener {
    Instance* inst = nullptr;
    void on_trust_changed(ConnectionTrust trust) override;
};

struct Instance {
    FileStore store;
    Net net;
    Ring ring;
    PlayerListener player_listener;
    ClientListener client_listener;
    SendspinClient client;
    PlayerRole* player = nullptr;
    std::mutex events_mutex;
    std::deque<int> events;

    Instance(SendspinClientConfig cfg, size_t ring_bytes, size_t frame_bytes)
        : ring(ring_bytes, frame_bytes), client(std::move(cfg)) {}

    void push_event(int e) {
        std::lock_guard<std::mutex> lk(events_mutex);
        events.push_back(e);
    }
};

size_t PlayerListener::on_audio_write(uint8_t* data, size_t length, uint32_t timeout_ms) {
    return inst->ring.write(data, length, timeout_ms);
}

void PlayerListener::on_stream_start() {
    const auto& p = inst->player->get_current_stream_params();
    const int sr = static_cast<int>(p.sample_rate.value_or(0));
    const int ch = static_cast<int>(p.channels.value_or(0));
    inst->push_event(kEventStreamStart | ((ch & 0xF) << 4) | (sr << 8));
}

void PlayerListener::on_stream_end() {
    inst->ring.clear();
    inst->push_event(kEventStreamEnd);
}

void ClientListener::on_trust_changed(ConnectionTrust trust) {
    inst->push_event(trust == ConnectionTrust::USER ? kEventTrustUser : kEventTrustNone);
}

Instance* from(jlong handle) {
    return reinterpret_cast<Instance*>(handle);
}

std::string to_std(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (c == nullptr) return {};
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

#define JNI_FN(name) Java_io_panelassistant_android_media_SendspinNative_##name

extern "C" JNIEXPORT jlong JNICALL JNI_FN(nCreate)(JNIEnv* env, jclass, jstring jStateDir, jstring jName,
                                                   jstring jVersion, jint sampleRate, jint channels,
                                                   jint requiredLeadMs) {
    if (sampleRate <= 0 || channels <= 0 || channels > 15) {
        SHIM_LOGE("nCreate: bad format %d Hz x %d", sampleRate, channels);
        return 0;
    }
    const std::string dir = to_std(env, jStateDir);
    if (dir.empty() || !ensure_dir(dir)) return 0;

    SendspinClientConfig cfg;
    cfg.name = to_std(env, jName);
    cfg.product_name = "ha-paneld";
    cfg.software_version = to_std(env, jVersion);
    cfg.server_port = 0;  // no inbound listener (PATCHES.md): only connect_to() reaches the client

    const size_t frame = static_cast<size_t>(channels) * 2;
    const size_t ring_bytes = static_cast<size_t>(sampleRate) * frame / 2;  // 500 ms
    auto* inst = new Instance(std::move(cfg), ring_bytes, frame);
    inst->store.dir = dir;
    inst->player_listener.inst = inst;
    inst->client_listener.inst = inst;

    PlayerRoleConfig pc;
    pc.audio_formats.push_back({SendspinCodecFormat::PCM, static_cast<uint8_t>(channels),
                                static_cast<uint32_t>(sampleRate), 16});
    if (requiredLeadMs > 0) pc.required_lead_time_ms = static_cast<uint16_t>(std::min(requiredLeadMs, 65535));
    inst->player = &inst->client.add_player(std::move(pc));
    inst->player->set_listener(&inst->player_listener);
    inst->client.set_listener(&inst->client_listener);
    inst->client.set_network_provider(&inst->net);
    inst->client.set_persistence_provider(&inst->store);
    inst->client.set_unpaired_access_enabled(false);
    if (!inst->client.start()) {
        SHIM_LOGE("nCreate: start() failed");
        delete inst;
        return 0;
    }
    return reinterpret_cast<jlong>(inst);
}

extern "C" JNIEXPORT jstring JNICALL JNI_FN(nClientId)(JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) return nullptr;
    return env->NewStringUTF(from(handle)->client.client_id().c_str());
}

extern "C" JNIEXPORT void JNICALL JNI_FN(nConnect)(JNIEnv* env, jclass, jlong handle, jstring jUrl) {
    if (handle == 0) return;
    from(handle)->client.connect_to(to_std(env, jUrl));
}

extern "C" JNIEXPORT void JNICALL JNI_FN(nLoop)(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    from(handle)->client.loop();
}

extern "C" JNIEXPORT jint JNICALL JNI_FN(nNextEvent)(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return 0;
    Instance* inst = from(handle);
    std::lock_guard<std::mutex> lk(inst->events_mutex);
    if (inst->events.empty()) return 0;
    const int e = inst->events.front();
    inst->events.pop_front();
    return e;
}

extern "C" JNIEXPORT jstring JNICALL JNI_FN(nServerId)(JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) return nullptr;
    Instance* inst = from(handle);
    if (!inst->client.is_connected()) return nullptr;
    auto info = inst->client.get_server_information();
    if (!info.has_value()) return nullptr;
    return env->NewStringUTF(info->server_id.c_str());
}

extern "C" JNIEXPORT jint JNICALL JNI_FN(nRead)(JNIEnv* env, jclass, jlong handle, jobject buffer, jint maxBytes,
                                                jint waitMs) {
    if (handle == 0 || buffer == nullptr || maxBytes <= 0) return 0;
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong cap = env->GetDirectBufferCapacity(buffer);
    if (dst == nullptr || cap <= 0) return 0;
    const size_t max = std::min(static_cast<size_t>(maxBytes), static_cast<size_t>(cap));
    return static_cast<jint>(from(handle)->ring.read(dst, max, waitMs));
}

extern "C" JNIEXPORT void JNICALL JNI_FN(nPlayed)(JNIEnv*, jclass, jlong handle, jint frames, jlong finishUs) {
    if (handle == 0 || frames <= 0) return;
    from(handle)->player->notify_audio_played(static_cast<uint32_t>(frames), static_cast<int64_t>(finishUs));
}

extern "C" JNIEXPORT jlong JNICALL JNI_FN(nNowUs)(JNIEnv*, jclass) {
    // steady_clock is CLOCK_MONOTONIC on bionic, the same base as System.nanoTime().
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

extern "C" JNIEXPORT void JNICALL JNI_FN(nDestroy)(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    Instance* inst = from(handle);
    inst->ring.close();  // releases a sync thread blocked in on_audio_write before stop() joins it
    inst->client.stop();
    delete inst;
}

extern "C" JNIEXPORT jboolean JNICALL JNI_FN(nWriteRecord)(JNIEnv* env, jclass, jstring jStateDir,
                                                           jstring jServerId, jbyteArray jPsk) {
    const std::string dir = to_std(env, jStateDir);
    const std::string server_id = to_std(env, jServerId);
    if (dir.empty() || jPsk == nullptr) return JNI_FALSE;
    auto key = base64url_decode(server_id);
    if (!key.has_value() || key->size() != 32) {
        SHIM_LOGE("nWriteRecord: server id is not a 32-byte base64url key");
        return JNI_FALSE;
    }
    if (env->GetArrayLength(jPsk) != 32) {
        SHIM_LOGE("nWriteRecord: psk must be 32 bytes");
        return JNI_FALSE;
    }
    SendspinPairingRecord rec;
    env->GetByteArrayRegion(jPsk, 0, 32, reinterpret_cast<jbyte*>(rec.psk.data()));
    rec.server_id = server_id;
    auto blob = encode_pairing_record(rec);
    if (!blob.has_value()) {
        SHIM_LOGE("nWriteRecord: encode failed");
        return JNI_FALSE;
    }
    if (!ensure_dir(dir)) {
        std::fill(blob->begin(), blob->end(), 0);
        return JNI_FALSE;
    }
    const std::string keep = persistence_keys::record_slot_key(0);
    const bool ok = write_file_atomic(join_path(dir, keep), blob->data(), blob->size());
    std::fill(blob->begin(), blob->end(), 0);
    if (!ok) return JNI_FALSE;

    bool cleaned = true;
    const std::string prefix = persistence_keys::RECORD_SLOT_PREFIX;
    if (DIR* d = opendir(dir.c_str())) {
        while (dirent* e = readdir(d)) {
            const std::string name = e->d_name;
            if (name == keep) continue;
            if (name == persistence_keys::RECORD_ORDER || name.compare(0, prefix.size(), prefix) == 0) {
                if (unlink(join_path(dir, name).c_str()) != 0 && errno != ENOENT) cleaned = false;
            }
        }
        closedir(d);
    } else {
        cleaned = false;
    }
    if (!cleaned) SHIM_LOGE("nWriteRecord: could not remove a stale record slot");
    return cleaned ? JNI_TRUE : JNI_FALSE;
}
