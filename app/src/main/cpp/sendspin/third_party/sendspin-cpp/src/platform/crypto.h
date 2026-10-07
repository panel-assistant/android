// Copyright 2026 Sendspin Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// @file crypto.h
/// @brief Portable cryptographic primitives (SHA-256, SHA-512, HMAC-SHA-512, CSPRNG, and an
/// X25519 DH-state RAII wrapper), routed through noise-c on both host and ESP so both platforms
/// use identical code paths with no extra dependencies.

#pragma once

#include "platform/secure_zero.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <string>
#include <vector>

// noise-c is a C library; wrap the includes in extern "C" to avoid C++
// name-mangling issues when compiling under -Wpedantic.
extern "C" {
#include <noise/protocol/cipherstate.h>
#include <noise/protocol/constants.h>
#include <noise/protocol/dhstate.h>
#include <noise/protocol/hashstate.h>
#include <noise/protocol/randstate.h>
}

namespace sendspin {

/// @brief Number of bytes in a SHA-256 digest.
static constexpr size_t SHA256_DIGEST_SIZE = 32;

/// @brief Streaming SHA-256. For one or two buffers, use the sha256_oneshot() overloads below.
class Sha256 {
public:
    Sha256() {
        int err = noise_hashstate_new_by_name(&this->state_, "SHA256");
        if (err != NOISE_ERROR_NONE) {
            this->state_ = nullptr;
        } else {
            noise_hashstate_reset(this->state_);
        }
    }

    ~Sha256() {
        if (this->state_ != nullptr) {
            noise_hashstate_free(this->state_);
        }
    }

    // Non-copyable
    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;

    /// @brief Feed bytes into the hash.
    void update(const uint8_t* data, size_t len) {
        if (this->state_ == nullptr) {
            return;
        }
        int err = noise_hashstate_update(this->state_, data, len);
        if (err != NOISE_ERROR_NONE) {
            this->failed_ = true;
        }
    }

    /// @brief Finalize and return the 32-byte digest. The digest is only meaningful if ok()
    /// is true after this call; on any failure (construction or mid-stream) this returns a
    /// zero-filled array and ok() reports false, so callers that skip the ok() check on a
    /// security-critical digest will trip an obviously-wrong (not attacker-chosen) value
    /// rather than silently trusting it.
    std::array<uint8_t, SHA256_DIGEST_SIZE> finalize() {
        std::array<uint8_t, SHA256_DIGEST_SIZE> digest{};
        if (this->state_ != nullptr && !this->failed_) {
            int err = noise_hashstate_finalize(this->state_, digest.data(), SHA256_DIGEST_SIZE);
            if (err != NOISE_ERROR_NONE) {
                this->failed_ = true;
                digest.fill(0);
            }
        }
        return digest;
    }

    /// @brief Return true if the hashstate was successfully initialized and no update()/
    /// finalize() call has failed so far.
    [[nodiscard]] bool ok() const {
        return this->state_ != nullptr && !this->failed_;
    }

private:
    // Pointer fields
    NoiseHashState* state_{nullptr};

    // 8-bit fields
    bool failed_{false};
};

/// @brief Compute SHA-256(data, len).
inline std::array<uint8_t, SHA256_DIGEST_SIZE> sha256_oneshot(const uint8_t* data, size_t len) {
    Sha256 h;
    h.update(data, len);
    return h.finalize();
}

/// @brief Compute SHA-256(prefix, prefix_len || suffix, suffix_len).
/// Used for psk_id derivation: SHA-256(PSK_ID_LABEL || psk).
inline std::array<uint8_t, SHA256_DIGEST_SIZE> sha256_oneshot(const uint8_t* prefix,
                                                              size_t prefix_len,
                                                              const uint8_t* suffix,
                                                              size_t suffix_len) {
    Sha256 h;
    h.update(prefix, prefix_len);
    h.update(suffix, suffix_len);
    return h.finalize();
}

// ============================================================================
// SHA-512
// ============================================================================

/// @brief Number of bytes in a SHA-512 digest.
static constexpr size_t SHA512_DIGEST_SIZE = 64;

/// @brief Self-contained streaming SHA-512 (FIPS 180-4).
///
/// Implemented here rather than through noise-c because neither noise-c path reaches SHA-512 on
/// ESP: the esphome-libs fork ships no SHA-512 hashstate backend, and its raw
/// src/crypto/sha2/sha512.c is gated behind NOISE_USE_REFERENCE_SHA512, which has no default and
/// which a REQUIRES'd ESP-IDF component offers no hook to define. This class can move onto the
/// noise-c primitive once the fork defines that macro for its component.
/// Uses only uint64_t arithmetic, so it builds on 32-bit targets (Xtensa/ESP32).
/// Validated against FIPS/RFC 4231 known-answer tests in tests/test_crypto.cpp.
class Sha512 {
public:
    Sha512() {
        this->reset();
    }

    // Non-copyable
    Sha512(const Sha512&) = delete;
    Sha512& operator=(const Sha512&) = delete;

    /// @brief Feed bytes into the hash.
    void update(const uint8_t* data, size_t len) {
        this->total_len_ += len;
        while (len > 0) {
            size_t take = BLOCK - this->buf_len_;
            if (take > len) {
                take = len;
            }
            std::memcpy(this->buf_ + this->buf_len_, data, take);
            this->buf_len_ += take;
            data += take;
            len -= take;
            if (this->buf_len_ == BLOCK) {
                this->process_block(this->buf_);
                this->buf_len_ = 0;
            }
        }
    }

    /// @brief Finalize and return the 64-byte digest.
    std::array<uint8_t, SHA512_DIGEST_SIZE> finalize() {
        // Message length in bits (128-bit big-endian field; the high 64 bits are always
        // zero for any input that fits in memory, so only the low 64 are non-trivial).
        const uint64_t bit_len = this->total_len_ * 8ULL;

        // Append 0x80 then pad with zeros until the buffer is 112 mod 128 bytes. Written
        // straight into buf_: the pad run is up to 127 bytes and total_len_ is already read.
        this->buf_[this->buf_len_++] = 0x80;
        if (this->buf_len_ > BLOCK - 16) {
            std::memset(this->buf_ + this->buf_len_, 0, BLOCK - this->buf_len_);
            this->process_block(this->buf_);
            this->buf_len_ = 0;
        }
        std::memset(this->buf_ + this->buf_len_, 0, (BLOCK - 16) - this->buf_len_);
        this->buf_len_ = BLOCK - 16;

        // Append the 128-bit big-endian bit length (high 64 bits are zero) and flush.
        uint8_t len_block[16] = {0};
        for (size_t i = 0; i < 8; ++i) {
            len_block[sizeof(len_block) - 1 - i] =
                static_cast<uint8_t>((bit_len >> (i * 8)) & 0xFF);
        }
        this->update(len_block, sizeof(len_block));
        // buf_len_ is now 0: that update completed the final block.

        std::array<uint8_t, SHA512_DIGEST_SIZE> digest{};
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 8; ++j) {
                digest[i * 8 + j] = static_cast<uint8_t>((this->h_[i] >> ((7 - j) * 8)) & 0xFF);
            }
        }
        return digest;
    }

private:
    static constexpr size_t BLOCK = 128;
    /// @brief FIPS 180-4 section 6.4.2: 80 rounds, over a message schedule kept as a rolling
    /// 16-word window (see process_block()).
    static constexpr int ROUNDS = 80;
    static constexpr int SCHEDULE_WINDOW = 16;
    static constexpr int SCHEDULE_MASK = SCHEDULE_WINDOW - 1;

    void reset() {
        static const uint64_t IV[8] = {
            0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL, 0x3c6ef372fe94f82bULL,
            0xa54ff53a5f1d36f1ULL, 0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL,
            0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL,
        };
        for (int i = 0; i < 8; ++i) {
            this->h_[i] = IV[i];
        }
        this->buf_len_ = 0;
        this->total_len_ = 0;
    }

    static uint64_t rotr(uint64_t x, unsigned n) {
        return (x >> n) | (x << (64 - n));
    }

    void process_block(const uint8_t* p) {
        static const uint64_t K[ROUNDS] = {
            0x428a2f98d728ae22ULL, 0x7137449123ef65cdULL, 0xb5c0fbcfec4d3b2fULL,
            0xe9b5dba58189dbbcULL, 0x3956c25bf348b538ULL, 0x59f111f1b605d019ULL,
            0x923f82a4af194f9bULL, 0xab1c5ed5da6d8118ULL, 0xd807aa98a3030242ULL,
            0x12835b0145706fbeULL, 0x243185be4ee4b28cULL, 0x550c7dc3d5ffb4e2ULL,
            0x72be5d74f27b896fULL, 0x80deb1fe3b1696b1ULL, 0x9bdc06a725c71235ULL,
            0xc19bf174cf692694ULL, 0xe49b69c19ef14ad2ULL, 0xefbe4786384f25e3ULL,
            0x0fc19dc68b8cd5b5ULL, 0x240ca1cc77ac9c65ULL, 0x2de92c6f592b0275ULL,
            0x4a7484aa6ea6e483ULL, 0x5cb0a9dcbd41fbd4ULL, 0x76f988da831153b5ULL,
            0x983e5152ee66dfabULL, 0xa831c66d2db43210ULL, 0xb00327c898fb213fULL,
            0xbf597fc7beef0ee4ULL, 0xc6e00bf33da88fc2ULL, 0xd5a79147930aa725ULL,
            0x06ca6351e003826fULL, 0x142929670a0e6e70ULL, 0x27b70a8546d22ffcULL,
            0x2e1b21385c26c926ULL, 0x4d2c6dfc5ac42aedULL, 0x53380d139d95b3dfULL,
            0x650a73548baf63deULL, 0x766a0abb3c77b2a8ULL, 0x81c2c92e47edaee6ULL,
            0x92722c851482353bULL, 0xa2bfe8a14cf10364ULL, 0xa81a664bbc423001ULL,
            0xc24b8b70d0f89791ULL, 0xc76c51a30654be30ULL, 0xd192e819d6ef5218ULL,
            0xd69906245565a910ULL, 0xf40e35855771202aULL, 0x106aa07032bbd1b8ULL,
            0x19a4c116b8d2d0c8ULL, 0x1e376c085141ab53ULL, 0x2748774cdf8eeb99ULL,
            0x34b0bcb5e19b48a8ULL, 0x391c0cb3c5c95a63ULL, 0x4ed8aa4ae3418acbULL,
            0x5b9cca4f7763e373ULL, 0x682e6ff3d6b2b8a3ULL, 0x748f82ee5defb2fcULL,
            0x78a5636f43172f60ULL, 0x84c87814a1f0ab72ULL, 0x8cc702081a6439ecULL,
            0x90befffa23631e28ULL, 0xa4506cebde82bde9ULL, 0xbef9a3f7b2c67915ULL,
            0xc67178f2e372532bULL, 0xca273eceea26619cULL, 0xd186b8c721c0c207ULL,
            0xeada7dd6cde0eb1eULL, 0xf57d4f7fee6ed178ULL, 0x06f067aa72176fbaULL,
            0x0a637dc5a2c898a6ULL, 0x113f9804bef90daeULL, 0x1b710b35131c471bULL,
            0x28db77f523047d84ULL, 0x32caab7b40c72493ULL, 0x3c9ebe0a15c9bebcULL,
            0x431d67c49c100d4cULL, 0x4cc5d4becb3e42b6ULL, 0x597f299cfc657e2aULL,
            0x5fcb6fab3ad6faecULL, 0x6c44198c4a475817ULL,
        };

        // FIPS 180-4 rolling message schedule: w[t] depends only on w[t-2], w[t-7], w[t-15]
        // and w[t-16], so a 16-word window updated in place stands in for the full 80-word
        // array and keeps 512 bytes off the frame of every SHA-512 on the pairing path.
        uint64_t w[SCHEDULE_WINDOW];
        for (int t = 0; t < SCHEDULE_WINDOW; ++t) {
            uint64_t v = 0;
            for (int j = 0; j < 8; ++j) {
                v = (v << 8) | p[t * 8 + j];
            }
            w[t] = v;
        }

        uint64_t a = this->h_[0], b = this->h_[1], c = this->h_[2], d = this->h_[3];
        uint64_t e = this->h_[4], f = this->h_[5], g = this->h_[6], h = this->h_[7];
        for (int t = 0; t < ROUNDS; ++t) {
            if (t >= SCHEDULE_WINDOW) {
                const uint64_t wm15 = w[(t - 15) & SCHEDULE_MASK];
                const uint64_t wm2 = w[(t - 2) & SCHEDULE_MASK];
                const uint64_t s0 = rotr(wm15, 1) ^ rotr(wm15, 8) ^ (wm15 >> 7);
                const uint64_t s1 = rotr(wm2, 19) ^ rotr(wm2, 61) ^ (wm2 >> 6);
                w[t & SCHEDULE_MASK] += s0 + w[(t - 7) & SCHEDULE_MASK] + s1;
            }
            const uint64_t big_s1 = rotr(e, 14) ^ rotr(e, 18) ^ rotr(e, 41);
            const uint64_t ch = (e & f) ^ (~e & g);
            const uint64_t t1 = h + big_s1 + ch + K[t] + w[t & SCHEDULE_MASK];
            const uint64_t big_s0 = rotr(a, 28) ^ rotr(a, 34) ^ rotr(a, 39);
            const uint64_t maj = (a & b) ^ (a & c) ^ (b & c);
            const uint64_t t2 = big_s0 + maj;
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        this->h_[0] += a;
        this->h_[1] += b;
        this->h_[2] += c;
        this->h_[3] += d;
        this->h_[4] += e;
        this->h_[5] += f;
        this->h_[6] += g;
        this->h_[7] += h;
    }

    // 64-bit fields
    uint64_t h_[8]{};
    uint64_t total_len_{0};  ///< Total message bytes fed so far.

    // size_t fields
    size_t buf_len_{0};  ///< Bytes currently in buf_.

    // 8-bit fields
    uint8_t buf_[BLOCK]{};  ///< Partial-block buffer.
};

/// @brief Compute SHA-512(data, len).
inline std::array<uint8_t, SHA512_DIGEST_SIZE> sha512_oneshot(const uint8_t* data, size_t len) {
    Sha512 h;
    h.update(data, len);
    return h.finalize();
}

// ============================================================================
// HMAC-SHA-512
// ============================================================================

/// @brief Compute HMAC-SHA-512(key, data) via the standard ipad/opad construction.
/// A key longer than the 128-byte SHA-512 block is first hashed down to 64 bytes.
inline std::array<uint8_t, SHA512_DIGEST_SIZE> hmac_sha512(const uint8_t* key, size_t key_len,
                                                           const uint8_t* data, size_t data_len) {
    static constexpr size_t BLOCK = 128;  // SHA-512 block size
    static constexpr uint8_t IPAD = 0x36;
    static constexpr uint8_t OPAD = 0x5C;

    // Derive the effective key (at most BLOCK bytes).
    std::array<uint8_t, BLOCK> k{};
    if (key_len > BLOCK) {
        // Hash the key down to 64 bytes then zero-pad to BLOCK.
        auto hk = sha512_oneshot(key, key_len);
        std::copy(hk.begin(), hk.end(), k.begin());
    } else {
        // key_len <= BLOCK here (the `if` above handles key_len > BLOCK); key+key_len is the
        // standard one-past-the-end pointer for the caller-supplied (key, key_len) buffer, valid
        // as long as the caller's buffer is at least key_len bytes: the same (ptr, len) contract
        // every function in this file relies on. cppcheck's range check can't see the caller's
        // actual buffer size and flags the key_len == BLOCK case as if it might overrun.
        // cppcheck-suppress pointerOutOfBoundsCond
        std::copy(key, key + key_len, k.begin());
    }

    // One pad buffer serves both passes, and scoping the inner Sha512 lets the compiler reuse
    // its slot for the outer one: together they keep ~336 bytes off this frame, which sits on
    // the loop thread under the pairing exchange.
    std::array<uint8_t, BLOCK> pad{};
    std::array<uint8_t, SHA512_DIGEST_SIZE> inner_hash{};
    {
        // Inner hash: SHA-512((k XOR ipad) || data)
        for (size_t i = 0; i < BLOCK; ++i) {
            pad[i] = k[i] ^ IPAD;
        }
        Sha512 inner;
        inner.update(pad.data(), BLOCK);
        inner.update(data, data_len);
        inner_hash = inner.finalize();
    }

    // Outer hash: SHA-512((k XOR opad) || inner_hash)
    for (size_t i = 0; i < BLOCK; ++i) {
        pad[i] = k[i] ^ OPAD;
    }
    Sha512 outer;
    outer.update(pad.data(), BLOCK);
    outer.update(inner_hash.data(), SHA512_DIGEST_SIZE);
    return outer.finalize();
}

// ============================================================================
// CSPRNG
// ============================================================================

/// @brief Fill a buffer with cryptographically secure random bytes.
/// Routes through noise-c's `noise_randstate_*` abstraction which uses the OS
/// entropy source on host (/dev/urandom) and the ESP32 hardware RNG on ESP.
inline void platform_random_bytes(uint8_t* out, size_t len) {
    NoiseRandState* rng = nullptr;
    int err = noise_randstate_new(&rng);
    if (err != NOISE_ERROR_NONE || rng == nullptr) {
        // Fail closed: there is no safe fallback for a CSPRNG failure. Returning without
        // writing `out` would leave a predictable (often all-zero) value, which is fatal for
        // key/nonce generation (e.g. a CPace scalar or a pairing-code nonce). Abort instead.
        abort();
    }
    noise_randstate_generate(rng, out, len);
    noise_randstate_free(rng);
}

/// @brief Generate `len` cryptographically secure random bytes.
inline std::vector<uint8_t> platform_random_bytes(size_t len) {
    std::vector<uint8_t> out(len);
    platform_random_bytes(out.data(), len);
    return out;
}

// ============================================================================
// Diffie-Hellman state (X25519)
// ============================================================================

/// @brief RAII wrapper around noise-c's NoiseDHState, so X25519 keypair generation/loading and
/// scalar multiplication do not hand-roll a noise_dhstate_new_by_name()/noise_dhstate_free()
/// pair across every error-return branch.
///
/// The wrapper only owns the state's lifetime; keypair setup (noise_dhstate_set_keypair_private,
/// noise_dhstate_generate_keypair, ...) and DH computation (noise_dhstate_calculate,
/// noise_dhstate_get_public_key, ...) remain the caller's responsibility via get().
class DhState {
public:
    /// @brief Allocate a DH state for the named curve ("25519" for every current call site).
    explicit DhState(const char* dh_name = "25519") {
        int err = noise_dhstate_new_by_name(&this->state_, dh_name);
        if (err != NOISE_ERROR_NONE) {
            this->state_ = nullptr;
        }
    }

    ~DhState() {
        if (this->state_ != nullptr) {
            noise_dhstate_free(this->state_);
        }
    }

    // Non-copyable
    DhState(const DhState&) = delete;
    DhState& operator=(const DhState&) = delete;

    /// @brief Return true if the underlying NoiseDHState was successfully allocated.
    [[nodiscard]] bool valid() const {
        return this->state_ != nullptr;
    }

    /// @brief Access the underlying NoiseDHState. Only meaningful when valid() is true.
    [[nodiscard]] NoiseDHState* get() const {
        return this->state_;
    }

private:
    NoiseDHState* state_{nullptr};
};

// ============================================================================
// AEAD (one-shot, explicit key + nonce)
// ============================================================================
//
// Used for pairing.md "Wrapping": the client seals the new PSK under a key derived from the
// CPace output using the AEAD of the connection's negotiated cipher suite (always ChaChaPoly;
// see NOISE_SUITE_CHACHAPOLY in crypto/constants.h), a 12-byte all-zero nonce, and empty
// associated data. This is independent of the Noise transport's own monotonic-counter nonces
// (see noise_transport.h); it is a single-shot construction with a fixed nonce, safe here only
// because K_wrap is used to encrypt exactly one message.

/// @brief Number of bytes of authentication-tag overhead added by the Sendspin AEAD cipher
/// (ChaChaPoly uses a 16-byte tag).
static constexpr size_t AEAD_TAG_SIZE = 16;

/// @brief Number of bytes in the fixed nonce used by aead_oneshot_encrypt/decrypt.
static constexpr size_t AEAD_ONESHOT_NONCE_SIZE = 12;

/// @brief Map a full Noise suite name (e.g. "Noise_KKpsk2_25519_ChaChaPoly_SHA256") to the
/// noise-c cipher name ("ChaChaPoly") for use with aead_oneshot_encrypt/decrypt. The client only
/// ever negotiates ChaChaPoly, so this validates the suite string rather than selecting between
/// alternatives. Returns nullptr if the suite name does not contain the expected cipher
/// component.
inline const char* aead_cipher_name_from_noise_suite(const std::string& noise_suite_name) {
    if (noise_suite_name.find("ChaChaPoly") != std::string::npos) {
        return "ChaChaPoly";
    }
    return nullptr;
}

/// @brief Encrypt `plaintext` with the named AEAD cipher ("ChaChaPoly"), key `key`, an all-zero
/// 12-byte nonce, and empty associated data. Returns ciphertext || tag (plaintext_len +
/// AEAD_TAG_SIZE bytes), or nullopt on any cipher-allocation or key-length failure.
///
/// The all-zero nonce is safe here because the Noise per-message counter n=0 encodes to an
/// all-zero 96-bit nonce regardless of ChaChaPoly's internal counter byte-order, and K_wrap is
/// a single-use key (see PSK Wrapping above).
inline std::optional<std::vector<uint8_t>> aead_oneshot_encrypt(const char* cipher_name,
                                                                const uint8_t* key, size_t key_len,
                                                                const uint8_t* plaintext,
                                                                size_t plaintext_len) {
    NoiseCipherState* cipher = nullptr;
    int err = noise_cipherstate_new_by_name(&cipher, cipher_name);
    if (err != NOISE_ERROR_NONE || cipher == nullptr) {
        return std::nullopt;
    }
    err = noise_cipherstate_init_key(cipher, key, key_len);
    if (err != NOISE_ERROR_NONE) {
        noise_cipherstate_free(cipher);
        return std::nullopt;
    }
    err = noise_cipherstate_set_nonce(cipher, 0);
    if (err != NOISE_ERROR_NONE) {
        noise_cipherstate_free(cipher);
        return std::nullopt;
    }

    const size_t mac_len = noise_cipherstate_get_mac_length(cipher);
    std::vector<uint8_t> buf(plaintext_len + mac_len);
    if (plaintext_len > 0) {
        std::memcpy(buf.data(), plaintext, plaintext_len);
    }

    NoiseBuffer nbuf;
    noise_buffer_set_inout(nbuf, buf.data(), plaintext_len, buf.size());
    err = noise_cipherstate_encrypt_with_ad(cipher, nullptr, 0, &nbuf);
    noise_cipherstate_free(cipher);
    if (err != NOISE_ERROR_NONE) {
        return std::nullopt;
    }
    buf.resize(nbuf.size);
    return buf;
}

/// @brief Decrypt `ciphertext` (ciphertext || tag) with the named AEAD cipher, key `key`, an
/// all-zero 12-byte nonce, and empty associated data. Returns the plaintext, or nullopt on any
/// cipher-allocation failure or AEAD authentication failure (wrong key or corrupted input).
inline std::optional<std::vector<uint8_t>> aead_oneshot_decrypt(const char* cipher_name,
                                                                const uint8_t* key, size_t key_len,
                                                                const uint8_t* ciphertext,
                                                                size_t ciphertext_len) {
    NoiseCipherState* cipher = nullptr;
    int err = noise_cipherstate_new_by_name(&cipher, cipher_name);
    if (err != NOISE_ERROR_NONE || cipher == nullptr) {
        return std::nullopt;
    }
    err = noise_cipherstate_init_key(cipher, key, key_len);
    if (err != NOISE_ERROR_NONE) {
        noise_cipherstate_free(cipher);
        return std::nullopt;
    }
    err = noise_cipherstate_set_nonce(cipher, 0);
    if (err != NOISE_ERROR_NONE) {
        noise_cipherstate_free(cipher);
        return std::nullopt;
    }

    std::vector<uint8_t> buf(ciphertext, ciphertext + ciphertext_len);
    NoiseBuffer nbuf;
    noise_buffer_set_inout(nbuf, buf.data(), buf.size(), buf.size());
    err = noise_cipherstate_decrypt_with_ad(cipher, nullptr, 0, &nbuf);
    noise_cipherstate_free(cipher);
    if (err != NOISE_ERROR_NONE) {
        return std::nullopt;
    }
    buf.resize(nbuf.size);
    return buf;
}

// ============================================================================
// Constant-time comparison
// ============================================================================

/// @brief Compare two equal-length byte buffers in constant time (XOR-accumulate, no
/// early exit), for authentication tags, commitments, and other secret-dependent
/// comparisons where a timing side channel would leak match position.
/// The caller must check lengths first; this only compares `len` bytes of each.
inline bool constant_time_equal(const uint8_t* a, const uint8_t* b, size_t len) {
    volatile uint8_t diff = 0;
    for (size_t i = 0; i < len; ++i) {
        diff |= static_cast<uint8_t>(a[i] ^ b[i]);
    }
    return diff == 0;
}

}  // namespace sendspin
