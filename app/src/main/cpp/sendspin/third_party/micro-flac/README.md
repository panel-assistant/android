# microFLAC - Embedded FLAC Decoder

[![CI](https://github.com/esphome-libs/micro-flac/actions/workflows/ci.yml/badge.svg)](https://github.com/esphome-libs/micro-flac/actions/workflows/ci.yml)

A FLAC (Free Lossless Audio Codec) decoder optimized for ESP32 embedded devices. Supports both native FLAC and Ogg FLAC containers with automatic format detection. Designed as an ESP-IDF component with PSRAM support and Xtensa assembly optimizations for ESP32/ESP32-S3.

[![A project from the Open Home Foundation](https://www.openhomefoundation.org/badges/ohf-project.png)](https://www.openhomefoundation.org/)

## Features

- **Native FLAC and Ogg FLAC**: Automatic container detection from first 4 bytes
- **Unified streaming API**: Single `decode()` method handles container detection, header parsing, and frame decoding
- **All FLAC bit depths**: 8-bit through 32-bit samples
- **CRC validation**: Optional frame integrity checking with CRC-8 (header) and CRC-16 (data)
- **PSRAM support**: Configurable memory placement with automatic fallback
- **Metadata extraction**: Album art, Vorbis comments, seektable, and more with configurable size limits
- **Xtensa optimizations**: LPC prediction with hardware multiply-accumulate on ESP32/ESP32-S3; C fallback on RISC-V chips (C3, C6, P4) and host

## Quick Start

### ESP-IDF Component

1. Add as a component to your project:

```bash
cd components
git clone https://github.com/esphome-libs/micro-flac.git
```

1. Configure via menuconfig:

```bash
pio run -e esp32s3 --target menuconfig
# Navigate to: Component config → microFLAC Decoder
```

1. Include and use:

```cpp
#include "micro_flac/flac_decoder.h"

using namespace micro_flac;

FLACDecoder decoder;

// Optional: configure metadata limits before first decode() call
decoder.set_max_metadata_size(FLAC_METADATA_TYPE_PICTURE, 50 * 1024);  // 50KB album art
decoder.set_max_metadata_size(FLAC_METADATA_TYPE_VORBIS_COMMENT, 4096);

// Decode in a loop (works with both .flac and .oga files automatically)
uint8_t* output = nullptr;
size_t output_size_bytes = 0;

while (have_data) {
    size_t bytes_consumed = 0, samples_decoded = 0;
    auto result = decoder.decode(input, input_len, output, output_size_bytes,
                                 bytes_consumed, samples_decoded);
    input += bytes_consumed;
    input_len -= bytes_consumed;

    if (result == FLAC_DECODER_HEADER_READY) {
        // Stream info now available, allocate output buffer
        const auto& info = decoder.get_stream_info();
        output_size_bytes = info.max_block_size() * info.num_channels()
                          * info.bytes_per_sample();  // or use get_output_buffer_size_samples() for int32_t* path
        output = new uint8_t[output_size_bytes];
    } else if (result == FLAC_DECODER_SUCCESS) {
        // Process samples_decoded interleaved PCM samples in output
    } else if (result == FLAC_DECODER_NEED_MORE_DATA) {
        // Read more data into buffer and try again
    } else if (result == FLAC_DECODER_END_OF_STREAM) {
        break;
    } else {
        break;  // Negative values are errors
    }
}
delete[] output;
```

### PlatformIO

Add to `platformio.ini`:

```ini
[env:esp32dev]
platform = espressif32
framework = espidf
lib_deps =
    https://github.com/esphome-libs/micro-flac.git

# Configure memory preference
build_flags =
    -DMICRO_FLAC_MEMORY_PREFER_PSRAM
```

### Host Build (Linux/macOS)

```bash
cd host_examples/flac_to_wav
cmake -B build && cmake --build build
./build/flac_to_wav input.flac output.wav       # Native FLAC
./build/flac_to_wav input.oga output.wav        # Ogg FLAC
```

## API Reference

### Key Methods

| Method | Description |
| ------ | ----------- |
| `decode(input, len, uint8_t* output, output_size_bytes, bytes_consumed, samples_decoded)` | Decode with native byte packing (e.g., 2 bytes for 16-bit, 3 for 24-bit). `output_size_bytes` in bytes (`max_block_size * channels * bytes_per_sample`). |
| `decode(input, len, int32_t* output, output_size_samples, bytes_consumed, samples_decoded)` | Decode with 32-bit left-justified output (all bit depths → 4 bytes). `output_size_samples` in samples (`max_block_size * channels` or `get_output_buffer_size_samples()`). |
| `get_stream_info()` | Get stream info struct (sample rate, channels, bit depth, etc.) after HEADER_READY. Use `get_stream_info().is_valid()` to check if parsed. |
| `get_output_buffer_size_samples()` | Get required output buffer size in samples (max_block_size * num_channels) |
| `reset()` | Reset decoder state for decoding a new stream (preserves configuration) |
| `set_crc_check_enabled(bool)` | Enable/disable CRC validation |
| `set_max_metadata_size(type, size)` | Set max stored size for a metadata type (call before first `decode()`) |
| `get_metadata_block(type)` | Get a specific metadata block by type (album art, tags, etc.) |
| `get_metadata_blocks()` | Get all stored metadata blocks |

> **Note:** The decoder always outputs **signed** samples at all bit depths, including 8-bit. WAV files require unsigned 8-bit samples - consumers writing WAV must add 128 to each byte themselves.

### FLACStreamInfo Methods

Available after `decode()` returns `FLAC_DECODER_HEADER_READY` via `decoder.get_stream_info()`:

| Method | Description |
| ------ | ----------- |
| `sample_rate()` | Sample rate in Hz (e.g., 44100, 48000) |
| `num_channels()` | Channel count (1=mono, 2=stereo, etc.) |
| `bits_per_sample()` | Bit depth (8, 16, 24, or 32) |
| `min_block_size()` / `max_block_size()` | Block size range in samples |
| `total_samples_per_channel()` | Total samples per channel (0 if unknown) |
| `bytes_per_sample()` | Bytes per sample, rounded up (e.g., 2 for 16-bit, 3 for 24-bit) |
| `md5_signature()` | Pointer to 16-byte MD5 signature of unencoded audio data |
| `is_valid()` | Whether STREAMINFO has been parsed |

### Result Codes

`decode()` returns `FLACDecoderResult`: non-negative values indicate success/informational states, negative values indicate errors. See `flac_decoder.h` for the full enum.

| Code | Value | Description |
| ---- | ----- | ----------- |
| `FLAC_DECODER_SUCCESS` | 0 | Frame decoded successfully |
| `FLAC_DECODER_HEADER_READY` | 1 | Header parsed, stream info available (allocate output buffer now) |
| `FLAC_DECODER_END_OF_STREAM` | 2 | No more frames to decode |
| `FLAC_DECODER_NEED_MORE_DATA` | 3 | Not enough input data; feed more and call `decode()` again |

## Configuration

Configure via ESP-IDF menuconfig (`Component config → microFLAC Decoder`) or compile flags.

| Option | Default | Kconfig / Compile Flag | Notes |
| ------ | ------- | ---------------------- | ----- |
| Memory preference | Prefer PSRAM | `CONFIG_MICRO_FLAC_PREFER_PSRAM` / `-DMICRO_FLAC_MEMORY_PREFER_PSRAM` | Also: prefer internal, PSRAM-only, internal-only |
| CRC checking | Enabled | Runtime: `set_crc_check_enabled(bool)` | CRC-8 (header) and CRC-16 (data) |
| Xtensa assembly | Enabled (ESP32/S3) | `CONFIG_MICRO_FLAC_ENABLE_XTENSA_ASM` | MULL/MULSH and hardware loops for LPC |
| Ogg FLAC support | Enabled | `CONFIG_MICRO_FLAC_ENABLE_OGG` / `-DMICRO_FLAC_DISABLE_OGG` | Disabling saves ~3-5 KB flash |

## Performance

Decoding performance for 48kHz stereo audio (full frame, CRC enabled):

| Chip | Clock | 16-bit | 24-bit |
| ---- | ----- | ------ | ------ |
| ESP32-S3 | 240 MHz | ~25x realtime | ~17x realtime |
| ESP32-P4 | 360 MHz | ~23x realtime | ~16x realtime |

Performance varies with block size, prediction order, and sample depth (24-bit requires 64-bit arithmetic). See [examples/decode_benchmark/README.md](examples/decode_benchmark/README.md) for detailed benchmarks, streaming overhead analysis, and instructions for running your own.

### Memory Usage

| Allocation | Size | Notes |
| ---------- | ---- | ----- |
| Decoder object | ~200 bytes | Stack or heap |
| Block samples buffer | `max_block_size × channels × 4` | Typically 16-64KB |
| Metadata blocks | Variable | Configurable per type |
| Output buffer | `max_block_size × channels × bytes_per_sample` | Allocated by user |

**PSRAM is recommended** for the block samples buffer to conserve internal RAM.

## Testing

The decoder is validated against the [FLAC test suite](https://github.com/ietf-wg-cellar/flac-test-files) with bit-perfect output compared to ffmpeg.

```bash
cd host_examples/flac_to_wav
cmake -B build && cmake --build build
python3 test_flac_decoder.py
```

Tests validate bit-perfect PCM output, MD5 signatures, various bit depths (8-32), sample rates, channel counts (1-8), embedded album art, byte-by-byte streaming, and Ogg FLAC containers. See [host_examples/flac_to_wav/TESTING.md](host_examples/flac_to_wav/TESTING.md) for full test suite details, test categories, and troubleshooting.

```bash
# ESP32 benchmark
cd examples/decode_benchmark
pio run -e esp32s3 -t upload -t monitor
```

## Advanced Features

### 32-bit Sample Output Mode

For embedded systems, unpacking 24-bit samples (3 bytes each) can be inefficient. Use the `int32_t*` overload of `decode()` to get left-justified (MSB-aligned) 32-bit samples:

```cpp
// Allocate a 32-bit output buffer after HEADER_READY
size_t output_size_samples = decoder.get_output_buffer_size_samples();
int32_t* output = new int32_t[output_size_samples];

// Use the int32_t* decode overload - 24-bit audio is shifted left by 8, 16-bit by 16, etc.
auto result = decoder.decode(input, input_len, output, output_size_samples,
                             bytes_consumed, samples_decoded);
```

### Metadata Extraction

Configure size limits before the first `decode()` call:

```cpp
// Enable album art up to 50KB
decoder.set_max_metadata_size(FLAC_METADATA_TYPE_PICTURE, 50 * 1024);

// Increase Vorbis comment storage
decoder.set_max_metadata_size(FLAC_METADATA_TYPE_VORBIS_COMMENT, 4096);

// After decode() returns FLAC_DECODER_HEADER_READY, access metadata:
const FLACMetadataBlock* art = decoder.get_metadata_block(FLAC_METADATA_TYPE_PICTURE);
if (art) {
    // art->data contains the raw FLAC PICTURE block; parse per the FLAC spec
    // to extract the embedded image (type, MIME, description, then image bytes)
    // art->length is the total block size in bytes
}

const FLACMetadataBlock* tags = decoder.get_metadata_block(FLAC_METADATA_TYPE_VORBIS_COMMENT);
if (tags) {
    // Parse Vorbis comments: ARTIST=..., ALBUM=..., etc.
}
```

Default limits (conservative for embedded): STREAMINFO is always stored; all others are 0 bytes (skipped by default).

### Custom Memory Allocation

Override `FLAC_MALLOC` and `FLAC_FREE` at compile time (`-DFLAC_MALLOC=my_custom_malloc -DFLAC_FREE=my_custom_free`). Custom functions must match `void* malloc(size_t)` and `void free(void*)` signatures.

## Known Limitations

- **No seeking support**: Decoder is designed for streaming, not random access
- **No automatic MD5 validation**: The MD5 signature is extracted from STREAMINFO and accessible via `get_stream_info().md5_signature()`, but not automatically validated during decoding

## License

Apache License 2.0

## Links

- [FLAC Format Specification](https://xiph.org/flac/format.html)
- [Nayuki's Simple FLAC Implementation](https://www.nayuki.io/res/simple-flac-implementation/) (original basis)
- [Mike Hansen's C++ FLAC decoder port](https://github.com/synesthesiam/flac-decoder)
- [FLAC Test Suite](https://github.com/ietf-wg-cellar/flac-test-files)
