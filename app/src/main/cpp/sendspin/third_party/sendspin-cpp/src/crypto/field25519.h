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

/// @file field25519.h
/// @brief Self-contained 256-bit modular arithmetic over GF(2^255-19), providing only what
/// CPace-X25519-SHA512 needs. No external dependencies; one code path on host and ESP32.

#pragma once

#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>

namespace sendspin {
namespace field25519 {

// p = 2^255 - 19, stored as four 64-bit limbs in little-endian order.
// Verified against Python: (2**255-19).to_bytes(32,'little') split into 8-byte chunks.

/// @brief A field element mod p = 2^255-19, stored as four 64-bit limbs (little-endian).
struct Fp {
    uint64_t limbs[4]{};  // limbs[0] = least significant 64 bits
};

// ============================================================================
// Internal helpers
// ============================================================================

// p = 2^255 - 19
static constexpr Fp FP_P = {{
    0xFFFFFFFFFFFFFFEDULL,
    0xFFFFFFFFFFFFFFFFULL,
    0xFFFFFFFFFFFFFFFFULL,
    0x7FFFFFFFFFFFFFFFULL,
}};

// 2^256 mod p = 38 (because 2^255 = p + 19): the multiplier that folds bits past 2^256 back in
static constexpr uint64_t FP_2_256_MOD_P = 38;

// p - 2 (for Fermat inverse: a^(p-2) mod p)
static constexpr Fp FP_P_MINUS_2 = {{
    0xFFFFFFFFFFFFFFEBULL,
    0xFFFFFFFFFFFFFFFFULL,
    0xFFFFFFFFFFFFFFFFULL,
    0x7FFFFFFFFFFFFFFFULL,
}};

// (p - 1) / 2 (for Legendre symbol exponentiation)
// (2^255 - 20) / 2 = 2^254 - 10
static constexpr Fp FP_LEGENDRE = {{
    0xFFFFFFFFFFFFFFF6ULL,
    0xFFFFFFFFFFFFFFFFULL,
    0xFFFFFFFFFFFFFFFFULL,
    0x3FFFFFFFFFFFFFFFULL,
}};

/// @brief Decode 32 little-endian bytes into a Fp.
/// The top bit is not cleared here; callers (Elligator2) clear it themselves.
inline Fp fp_from_le(const uint8_t* b) {
    Fp r;
    for (int i = 0; i < 4; ++i) {
        r.limbs[i] = 0;
        for (int j = 0; j < 8; ++j) {
            r.limbs[i] |= static_cast<uint64_t>(b[i * 8 + j]) << (j * 8);
        }
    }
    return r;
}

/// @brief Encode a Fp into 32 little-endian bytes.
inline std::array<uint8_t, 32> fp_to_le(const Fp& a) {
    std::array<uint8_t, 32> out{};
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 8; ++j) {
            out[i * 8 + j] = static_cast<uint8_t>((a.limbs[i] >> (j * 8)) & 0xFF);
        }
    }
    return out;
}

// ============================================================================
// Portable wide-arithmetic helpers.
//
// The field arithmetic below works on four 64-bit limbs and needs 64x64->128
// products plus add/subtract carry propagation. A 64-bit host could express these
// with `unsigned __int128`, but 32-bit targets (e.g. Xtensa/ESP32) have no such
// type. These helpers implement the wide operations using only uint64_t, so there
// is a single code path on every platform and the host tests validate exactly what
// runs on the device.
// ============================================================================

/// @brief 64x64 -> 128 bit unsigned multiply. Sets hi:lo to the full product.
inline void mul64_wide(uint64_t a, uint64_t b, uint64_t& hi, uint64_t& lo) {
    constexpr uint64_t M = 0xFFFFFFFFULL;
    const uint64_t a_lo = a & M, a_hi = a >> 32;
    const uint64_t b_lo = b & M, b_hi = b >> 32;
    const uint64_t ll = a_lo * b_lo;
    const uint64_t lh = a_lo * b_hi;
    const uint64_t hl = a_hi * b_lo;
    const uint64_t hh = a_hi * b_hi;
    const uint64_t cross = (ll >> 32) + (lh & M) + (hl & M);
    lo = (ll & M) | (cross << 32);
    hi = hh + (lh >> 32) + (hl >> 32) + (cross >> 32);
}

/// @brief Add a + b + carry_in. Returns the low 64 bits; sets carry_out to 0/1.
inline uint64_t adc64(uint64_t a, uint64_t b, uint64_t carry_in, uint64_t& carry_out) {
    const uint64_t s1 = a + b;
    const uint64_t c1 = (s1 < a) ? 1ULL : 0ULL;
    const uint64_t s2 = s1 + carry_in;
    const uint64_t c2 = (s2 < s1) ? 1ULL : 0ULL;
    carry_out = c1 + c2;  // 0 or 1: a+b and +carry_in cannot both overflow.
    return s2;
}

/// @brief Subtract a - b - borrow_in. Returns the low 64 bits; sets borrow_out to 0/1.
inline uint64_t sbb64(uint64_t a, uint64_t b, uint64_t borrow_in, uint64_t& borrow_out) {
    const uint64_t d1 = a - b;
    const uint64_t b1 = (a < b) ? 1ULL : 0ULL;
    const uint64_t d2 = d1 - borrow_in;
    const uint64_t b2 = (d1 < borrow_in) ? 1ULL : 0ULL;
    borrow_out = b1 + b2;  // 0 or 1.
    return d2;
}

// ============================================================================
// Fully reduce a value that may be in [0, 2p-1] to [0, p-1].
//
// Uses a conditional subtraction: try to subtract p; if no borrow, the
// subtracted result is correct; otherwise keep the original.
// Called after add, sub, and mul operations.
// ============================================================================

inline Fp fp_reduce(const Fp& a) {
    Fp r;
    uint64_t borrow = 0;
    for (int i = 0; i < 4; ++i) {
        r.limbs[i] = sbb64(a.limbs[i], FP_P.limbs[i], borrow, borrow);
    }
    if (borrow == 0) {
        return r;
    }
    return a;
}

// ============================================================================
// Fully reduce a value anywhere in [0, 2^256) to [0, p-1].
//
// The single conditional subtraction above is only enough for inputs below 2p.
// The 38-fold in fp_mul/fp_scale leaves a value that is merely below 2^256, and
// 2^256 = 2p + 38, so an input in [2p, 2^256) needs a second subtraction: one
// pass would leave it in [p, p+38), i.e. still non-canonical. That band is not
// exotic: it is reachable whenever the true result is congruent to a value
// below 38, which is exactly what an inverse or a Legendre exponentiation
// produces (fp_mul(x, fp_inv(x)) hit it for ~25% of random x, returning p+1
// instead of 1). A non-canonical Fp then encodes to the wrong 32 bytes through
// fp_to_le(), which does not reduce.
//
// Two passes always suffice because 2^256 < 3p.
// ============================================================================

inline Fp fp_reduce_full(const Fp& a) {
    return fp_reduce(fp_reduce(a));
}

// ============================================================================
// Montgomery-free 256-bit mod-p multiplication using schoolbook 128-bit math.
//
// We compute a * b as a 512-bit product then reduce mod p.
// Reduction of a 512-bit number n mod (2^255-19):
//   n = n_lo + n_hi * 2^256
//   n mod p = n_lo + n_hi * 38   (because 2^255 = p + 19 => 2^256 mod p = 38)
// The fold leaves a value below 2^256, which is 2p + 38, so the final step must be the
// two-subtraction fp_reduce_full(), not a single conditional subtraction.
// ============================================================================

inline Fp fp_mul(const Fp& a, const Fp& b) {
    // lo[0..7]: 512-bit product limbs (lo[0] = least significant 64 bits)
    uint64_t lo[8]{};

    for (int i = 0; i < 4; ++i) {
        uint64_t carry = 0;
        for (int j = 0; j < 4; ++j) {
            // prod = a[i]*b[j] + lo[i+j] + carry, spread across (phi:plo).
            uint64_t phi = 0;
            uint64_t plo = 0;
            mul64_wide(a.limbs[i], b.limbs[j], phi, plo);
            uint64_t c1 = 0, c2 = 0;
            uint64_t t = adc64(plo, lo[i + j], 0, c1);
            t = adc64(t, carry, 0, c2);
            lo[i + j] = t;
            // Equals the full 128-bit prod >> 64; provably <= 2^64-1, so no overflow.
            carry = phi + c1 + c2;
        }
        lo[i + 4] += carry;
    }

    // Pass 1: fold upper 256 bits (lo[4..7]) using multiplier 38 (see the banner above).
    Fp r;
    {
        uint64_t carry = 0;
        for (int i = 0; i < 4; ++i) {
            // v = lo[i] + lo[i+4]*38 + carry, spread across (mhi:mlo) for the product.
            uint64_t mhi = 0;
            uint64_t mlo = 0;
            mul64_wide(lo[i + 4], FP_2_256_MOD_P, mhi, mlo);  // mhi <= 37
            uint64_t c1 = 0, c2 = 0;
            uint64_t t = adc64(lo[i], mlo, 0, c1);
            t = adc64(t, carry, 0, c2);
            r.limbs[i] = t;
            carry = mhi + c1 + c2;  // small (<= ~38)
        }
        // `carry` here is the carry out of limb 3 from the loop above, i.e. the true
        // overflow past 2^256 (bounded by ~38): its positional value is 2^256, and
        // 2^256 mod p = 38, so folding it back in as carry*38 (added into limb 0) is
        // correct. That addition can itself carry out of limb 0, though (a carry with
        // positional value 2^64, not 2^256), so it must be propagated limb-by-limb
        // (0 -> 1 -> 2 -> 3), not folded by 38 again. Only a carry that makes it all
        // the way out of limb 3 a second time has positional value 2^256 and is
        // legitimately foldable by 38.
        uint64_t addend = carry * FP_2_256_MOD_P;
        uint64_t prop = 0;
        r.limbs[0] = adc64(r.limbs[0], addend, 0, prop);
        for (int i = 1; i < 4 && prop; ++i) {
            r.limbs[i] = adc64(r.limbs[i], prop, 0, prop);
        }
        if (prop) {
            // Carry made it out of limb 3: limbs[1..3] just wrapped to zero and limbs[0]
            // holds a value <= addend (a few thousand at most), so adding 38 more cannot
            // overflow limb 0 again. A single non-looping add is provably sufficient.
            r.limbs[0] = r.limbs[0] + FP_2_256_MOD_P;
        }
    }

    // Pass 2: fully reduce (the fold is only bounded by 2^256 = 2p + 38).
    return fp_reduce_full(r);
}

/// @brief Modular addition: (a + b) mod p.
inline Fp fp_add(const Fp& a, const Fp& b) {
    // Compute 256-bit sum (no overflow out of the top since a, b < p < 2^255).
    Fp r;
    uint64_t carry = 0;
    for (int i = 0; i < 4; ++i) {
        r.limbs[i] = adc64(a.limbs[i], b.limbs[i], carry, carry);
    }
    // Result in [0, 2p-2]; conditionally subtract p.
    return fp_reduce(r);
}

/// @brief Modular subtraction: (a - b) mod p.
inline Fp fp_sub(const Fp& a, const Fp& b) {
    // a - b mod p = a + (p - b) mod p
    // Compute p - b (no underflow if b < p).
    Fp p_minus_b;
    {
        uint64_t borrow = 0;
        for (int i = 0; i < 4; ++i) {
            p_minus_b.limbs[i] = sbb64(FP_P.limbs[i], b.limbs[i], borrow, borrow);
        }
    }
    return fp_add(a, p_minus_b);
}

/// @brief Modular exponentiation: base^exp mod p using square-and-multiply.
inline Fp fp_pow(const Fp& base, const Fp& exp) {
    Fp result = {{1, 0, 0, 0}};
    Fp cur = base;
    for (uint64_t e : exp.limbs) {
        for (int bit = 0; bit < 64; ++bit) {
            if ((e >> bit) & 1) {
                result = fp_mul(result, cur);
            }
            cur = fp_mul(cur, cur);
        }
    }
    return result;
}

/// @brief Modular inverse: a^(p-2) mod p (Fermat's little theorem).
inline Fp fp_inv(const Fp& a) {
    return fp_pow(a, FP_P_MINUS_2);
}

/// @brief Legendre symbol exponentiation: a^((p-1)/2) mod p.
/// Returns Fp with value 0, 1, or p-1.
inline Fp fp_legendre_pow(const Fp& a) {
    return fp_pow(a, FP_LEGENDRE);
}

/// @brief Scale a Fp by a small integer constant (< 2^26) without full multiplication.
inline Fp fp_scale(const Fp& a, uint64_t s) {
    // The single-step carry fold below is only exact for small scalars: the loop carry can
    // reach ~s, and it is folded back with multiplier 38 assuming it fits alongside limb 0.
    // Callers only pass tiny constants (2 and 486662). Guard against a future large-s caller.
    assert(s < (static_cast<uint64_t>(1) << 26));
    uint64_t carry = 0;
    Fp r;
    for (int i = 0; i < 4; ++i) {
        // prod = a[i]*s + carry, spread across (phi:plo).
        uint64_t phi = 0;
        uint64_t plo = 0;
        mul64_wide(a.limbs[i], s, phi, plo);
        uint64_t c1 = 0;
        r.limbs[i] = adc64(plo, carry, 0, c1);
        carry = phi + c1;  // small: s < 2^26 so phi < 2^26
    }
    // Same carry fold as fp_mul: positional value 2^256, folded by 38 into limb 0, then the
    // resulting 2^64-valued carry propagated limb-by-limb (the bound here is s < 2^26).
    {
        uint64_t addend = carry * FP_2_256_MOD_P;
        uint64_t prop = 0;
        r.limbs[0] = adc64(r.limbs[0], addend, 0, prop);
        for (int i = 1; i < 4 && prop; ++i) {
            r.limbs[i] = adc64(r.limbs[i], prop, 0, prop);
        }
        if (prop) {
            r.limbs[0] = r.limbs[0] + FP_2_256_MOD_P;
        }
    }
    return fp_reduce_full(r);
}

}  // namespace field25519
}  // namespace sendspin
