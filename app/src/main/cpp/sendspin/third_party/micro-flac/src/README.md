# microFLAC - Internal Architecture

Internal documentation for developers working on the decoder internals. For the public API and usage guide, see the [root README](../README.md).

## Origins

Based on [Nayuki's Simple FLAC Implementation](https://www.nayuki.io/res/simple-flac-implementation/) and [Mike Hansen's C++ FLAC decoder port](https://github.com/synesthesiam/flac-decoder), rewritten and extensively optimized for embedded systems.

## File Organization

### Public API

- `include/micro_flac/flac_decoder.h` - Public API: `FLACDecoder` class, `FLACStreamInfo`, `FLACMetadataBlock`, result codes

### Core Decoder

- `flac_decoder.cpp` - Main decoder: state machine, container detection, header/metadata parsing, subframe decoding, residual decoding, bitstream reading
- `frame_header.h` / `frame_header.cpp` - Frame header parsing: `compute_frame_header_length()`, `parse_frame_header()` (sync validation, field extraction, CRC-8 check, STREAMINFO validation)
- `decorrelation.h` / `decorrelation.cpp` - Stereo channel decorrelation: `apply_channel_decorrelation()` for LEFT_SIDE, RIGHT_SIDE, and MID_SIDE joint stereo modes

### Output Packing

- `pcm_packing.h` / `pcm_packing.cpp` - Interleaved PCM output with optimized fast paths (see [Output Packing](#output-packing))

### Linear Predictive Coding (LPC)

- `lpc.h` / `lpc.cpp` - Overflow detection and 32-bit/64-bit LPC restoration

### Assembly Optimizations (ESP32 only)

- `xtensa/lpc_xtensa.h` - Platform detection and assembly function declarations
- `xtensa/lpc_32_xtensa.S` - 32-bit LPC restoration (Xtensa assembly)
- `xtensa/lpc_64_xtensa.S` - 64-bit LPC restoration (Xtensa assembly)

### Utilities

- `crc.h` / `crc.cpp` - CRC-8 and CRC-16 lookup tables and functions
- `alloc.h` - Memory allocation macros (`FLAC_MALLOC` / `FLAC_FREE`) with ESP-IDF PSRAM support
- `compiler.h` - Compiler hints (optimization, inlining, branch prediction) and bit buffer constants
- `wrapping_arithmetic.h` - uint32_t-based wrapping arithmetic helpers (`wadd32`, `wsub32`, `wshl32`, `wshr32`, `u32`) for FLAC's modulo-2^bps semantics

## Decode State Machine

The decoder uses a multi-level state machine that makes all parsing resumable across `decode()` calls, enabling streaming with arbitrarily small input buffers.

### Top-Level Phases (`DecodePhase`)

```text
DETECT_CONTAINER ──→ HEADER ──→ AUDIO ──→ DONE
```

- **DETECT_CONTAINER**: Accumulates the first 4 bytes to identify `"fLaC"` (native FLAC) or `"OggS"` (Ogg FLAC), then delegates to the appropriate decode path
- **HEADER**: Parses STREAMINFO and other metadata blocks. Returns `HEADER_READY` when complete
- **AUDIO**: Decodes frames until end of stream
- **DONE**: Terminal state, always returns `END_OF_STREAM`

### Container Paths

```text
                    ┌─────────────────────┐
                    │  Container Detect    │
                    │  (first 4 bytes)     │
                    └──────────┬──────────┘
                               │
                ┌──────────────┴──────────────┐
                │                             │
                ▼                             ▼
       ┌────────────────┐           ┌─────────────────┐
       │  decode_native │           │   decode_ogg    │
       │  ("fLaC")      │           │   ("OggS")      │
       └────────┬───────┘           └────────┬────────┘
                │                            │
                │                   ┌────────┴────────┐
                │                   │  Ogg Demuxer    │
                │                   │  (strips pages, │
                │                   │   BOS prefix)   │
                │                   └────────┬────────┘
                │                            │
                └──────────┬─────────────────┘
                           │
                           ▼
                  ┌────────────────┐
                  │ Header/Frame   │
                  │ Parsing        │
                  │ (shared path)  │
                  └────────────────┘
```

The Ogg path uses `micro_ogg::OggDemuxer` to strip Ogg page framing and the 9-byte BOS prefix (`0x7F "FLAC" 0x01 ...`), then delegates the raw FLAC data to `decode_native` for the actual header/frame parsing.

### Frame Decode Stages (`FrameDecodeStage`)

```text
IDLE ──→ FRAME_HEADER ──→ SUBFRAME ──→ FRAME_FOOTER ──→ IDLE
```

Each stage is resumable. The decoder saves its position and returns `NEED_MORE_DATA` if the input is exhausted mid-stage.

- **FRAME_HEADER**: Accumulates header bytes, then delegates to `parse_frame_header()` (in `frame_header.cpp`) for parsing and validation
- **SUBFRAME**: Decodes all channels' subframes (constant, verbatim, fixed, LPC + residuals)
- **FRAME_FOOTER**: Reads CRC-16 and validates frame integrity

### Subframe Decode Stages (`SubframeDecodeStage`)

```text
SUBFRAME_HEADER ──→ SUBFRAME_CONSTANT
                  │  SUBFRAME_VERBATIM
                  │  SUBFRAME_WARMUP ──→ LPC_PARAMS ──→ RESIDUAL_HEADER
                  │                  └──→ RESIDUAL_HEADER
                  │
                  └──→ RESIDUAL_HEADER ──→ RESIDUAL_PARTITION_PARAM ──→ RESIDUAL_SAMPLES
                                                                     └→ RESIDUAL_ESCAPE_BITS ──→ RESIDUAL_SAMPLES
```

Subframe types:

- **Constant**: Single value repeated for entire block
- **Verbatim**: Raw uncompressed samples
- **Fixed**: Fixed-order linear prediction (orders 0-4)
- **LPC**: Arbitrary-order linear prediction with encoded coefficients

After all subframes are decoded, channel decorrelation is applied via `apply_channel_decorrelation()` (in `decorrelation.cpp`) for mid-side, left-side, or right-side stereo, followed by CRC-16 validation of the frame.

## Optimizations

### Bitstream Reading

The decoder uses a platform-sized bit buffer: 64-bit on host/64-bit platforms (refilled 8 bytes at a time) and 32-bit on ESP32/32-bit platforms (refilled 4 bytes at a time). This avoids unnecessary 64-bit arithmetic on embedded targets while reducing refill frequency on desktop. Read functions are inlined.

### LPC Accumulator Type Selection

The public `restore_lpc()` function dispatches to a specialized implementation based on overflow analysis and buffer type:

1. **Overflow Detection** (`can_use_lpc_32bit()`):
   - Analyzes sample depth, LPC coefficients, order, and quantization shift
   - Calculates maximum possible values before and after shift
   - Determines if 32-bit arithmetic will overflow

2. **32-bit Fast Path** (`restore_lpc_32bit()`):
   - Dispatched by `restore_lpc()` when overflow is impossible (most 16-bit audio)
   - ~2x faster on ESP32-S3
   - C++ implementation uses unrolled loops for orders 1-12
   - Xtensa assembly implementation with loop unrolling for orders 1-12

3. **64-bit Safe Path** (`restore_lpc_64bit()`):
   - Dispatched by `restore_lpc()` when 32-bit arithmetic may overflow
   - Used for high-resolution audio (e.g., 24-bit) with large coefficients
   - Prevents overflow in all valid FLAC streams
   - C++ implementation uses unrolled loops for orders 1-12
   - Xtensa assembly implementation uses MULL/MULSH instructions on ESP32 with loop unrolling for orders 1-12

4. **33-bit MID_SIDE Path** (`restore_lpc()` with `int64_t*` buffer):
   - Dispatched via the `int64_t*` overload of `restore_lpc()`
   - Used for the side channel in MID_SIDE stereo when the output sample depth is 32 bits
   - Side channel samples can be up to 33 bits wide, requiring `int64_t` buffers
   - Always uses 64-bit accumulation (no Xtensa assembly dispatch)

### Output

Output packing converts the internal planar 32-bit representation to interleaved PCM in the user's output buffer. The implementation is in `pcm_packing.cpp` with optimized fast paths dispatched by `write_samples()`:

**Native output mode** (bytes per sample matches bit depth):

- **16-bit mono**: Pointer-cast to `int16_t*`, 4-sample unrolled loop
- **16-bit stereo**: Pointer-cast to `int16_t*`, 4-sample unrolled loop
- **24-bit stereo**: Byte-by-byte little-endian packing, 2-sample unrolled loop
- **General fallback**: Handles all other bit depths and channel counts (non-byte-aligned with LSB zero-padding)

**32-bit output mode** (all samples left-justified to 32 bits):

- **32-bit mono**: Pointer-cast to `int32_t*`, shift in place
- **32-bit stereo**: Pointer-cast to `int32_t*`, shift-and-interleave
- **32-bit general**: Handles arbitrary channel counts

## References

- [FLAC Format Specification](https://xiph.org/flac/format.html)
- [Nayuki's Simple FLAC Implementation](https://www.nayuki.io/res/simple-flac-implementation/)
- [Mike Hansen's C++ FLAC decoder port](https://github.com/synesthesiam/flac-decoder)
- [Xtensa ISA Reference](https://www.cadence.com/content/dam/cadence-www/global/en_US/documents/tools/ip/tensilica-ip/isa-summary.pdf)
