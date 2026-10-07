# microOggDemuxer

[![CI](https://github.com/esphome-libs/micro-ogg-demuxer/actions/workflows/ci.yml/badge.svg)](https://github.com/esphome-libs/micro-ogg-demuxer/actions/workflows/ci.yml)

A lightweight, platform-agnostic Ogg container demuxer for embedded systems and general use.

## Features

- **Zero-copy optimization**: Returns pointers directly to input buffer when possible
- **Streaming demux**: Feed data in chunks, demuxer handles internal buffering
- **Raw streaming mode**: `get_next_data()` skips packet assembly entirely for byte-level streaming to decoders
- **Dynamic buffer growth**: Starts small (1KB), grows as needed (configurable max)
- **Custom allocators**: Bring your own malloc/free/realloc or use defaults
- **Platform-agnostic**: No dependencies on ESP-IDF, libogg, or any platform-specific code
- **RFC 3533 compliant**: Single logical bitstream Ogg files with optional CRC validation
- **Clean C++ API**: Simple, well-documented interface

## Overview

microOggDemuxer extracts packets from Ogg container streams. It handles:

- Page header parsing
- Segment table navigation
- Packet boundary detection
- Multi-page packet reassembly
- CRC validation (optional)
- Stream sequence validation

The demuxer is designed for memory-constrained embedded systems but works equally well on host platforms.

## Building

```bash
mkdir build && cd build
cmake ..
cmake --build .
```

### As a Git Submodule

This library is designed to be included as a submodule in codec wrapper projects:

```bash
git submodule add https://github.com/esphome-libs/micro-ogg-demuxer.git
```

Then in your CMakeLists.txt:

```cmake
add_subdirectory(micro-ogg-demuxer)
target_link_libraries(your_target PRIVATE micro_ogg_demuxer)
```

## Usage

### Basic Example

```cpp
#include <micro_ogg/ogg_demuxer.h>

micro_ogg::OggDemuxer demuxer;

while (have_data) {
    micro_ogg::OggDemuxState state = demuxer.get_next_packet(input_ptr, input_len);

    if (state.result == micro_ogg::OGG_OK) {
        // Process packet
        processPacket(state.packet.data, state.packet.length);
    }

    input_ptr += state.bytes_consumed;
    input_len -= state.bytes_consumed;
}
```

### Custom Configuration

```cpp
micro_ogg::OggDemuxerConfig config;
config.min_buffer_size = 512;      // Start with 512 bytes
config.max_buffer_size = 32768;    // Grow up to 32KB
config.enable_crc = true;          // Enable CRC validation (see caveats below)

micro_ogg::OggDemuxer demuxer(config);
```

### Custom Memory Allocators

```cpp
micro_ogg::OggDemuxerConfig config;

config.alloc = [](size_t size) -> void* {
    return my_malloc(size);
};

config.realloc = [](void* ptr, size_t size) -> void* {
    return my_realloc(ptr, size);
};

config.free = [](void* ptr) {
    my_free(ptr);
};

micro_ogg::OggDemuxer demuxer(config);
```

## API Reference

### OggDemuxerConfig

Configuration structure for customizing demuxer behavior:

| Field | Type | Default | Description |
| ----- | ---- | ------- | ----------- |
| `min_buffer_size` | `size_t` | 1024 | Initial buffer size in bytes |
| `max_buffer_size` | `size_t` | 8192 | Maximum buffer size (conservative default) |
| `enable_crc` | `bool` | false | Enable CRC32 validation (see [CRC Validation](#crc-validation)) |
| `alloc` | function pointer | nullptr | Custom allocator (nullptr = use malloc) |
| `realloc` | function pointer | nullptr | Custom reallocator (nullptr = use realloc) |
| `free` | function pointer | nullptr | Custom deallocator (nullptr = use free) |

### OggDemuxResult

Result codes returned by demuxer methods:

| Code | Value | Description |
| ---- | ----- | ----------- |
| `OGG_OK` | 0 | Success - packet available |
| `OGG_NEED_MORE_DATA` | 1 | Need more input data |
| `OGG_PACKET_SKIPPED` | 2 | Packet too large, was skipped |
| `OGG_INVALID_CAPTURE` | -1 | Invalid "OggS" magic bytes |
| `OGG_INVALID_VERSION` | -2 | Unsupported Ogg version |
| `OGG_CRC_FAILED` | -3 | CRC checksum mismatch |
| `OGG_STREAM_SEQUENCE_ERROR` | -4 | Page sequence error |
| `OGG_STREAM_BOS_ERROR` | -5 | BOS flag violation |
| `OGG_STREAM_EOS_ERROR` | -6 | EOS flag violation |
| `OGG_STREAM_SERIAL_MISMATCH` | -7 | New stream (call reset() to continue) |
| `OGG_ALLOCATION_FAILED` | -8 | Memory allocation failed |

### OggPacket

Packet data structure:

```cpp
struct OggPacket {
    const uint8_t* data;        // Packet payload (zero-copy when possible)
    size_t length;              // Packet length in bytes
    int64_t granule_position;   // Codec-specific position
    bool is_bos;                // Beginning of stream flag
    bool is_eos;                // End of stream flag
    bool is_last_on_page;       // Last packet on current page
};
```

**Important**: `data` pointer is only valid until the next demuxer call. Copy the data if you need to keep it.

### Main Methods

#### `get_next_packet()`

```cpp
OggDemuxState get_next_packet(const uint8_t* input, size_t input_len);
```

Demux input and return next complete packet. Returns struct containing:

- `result`: Demux result code
- `bytes_consumed`: How many input bytes were consumed
- `packet`: Packet data (only valid if result == OGG_OK)

#### `get_next_data()` (Streaming Mode)

```cpp
OggDemuxState get_next_data(const uint8_t* input, size_t input_len);
```

Streaming mode that skips full packet assembly and internal buffering entirely. `get_next_data()` strips Ogg framing and returns raw body bytes as a zero-copy pointer, capped at the current packet boundary. Segment tracking, CRC accumulation, and page finalization are handled automatically.

No heap allocation is performed — only the inline header staging buffer (282 bytes) is used. No internal packet buffer is needed.

- `bytes_consumed` includes both header and body bytes; advance the input pointer by this amount.
- `is_end_of_packet` is true when the offered data reaches a packet boundary, making it safe to switch to `get_next_packet()`.

```cpp
micro_ogg::OggDemuxer demuxer;

while (have_data) {
    micro_ogg::OggDemuxState state = demuxer.get_next_data(input, len);

    if (state.result == micro_ogg::OGG_OK) {
        // Use packet.data before advancing input (it points into the input buffer)
        decoder.decode(state.packet.data, state.packet.length);
    }

    input += state.bytes_consumed;
    len -= state.bytes_consumed;
}
```

#### `reset()`

```cpp
void reset();
```

Reset demuxer state. Does not deallocate buffers.

## Memory Usage

The header staging buffer is an inline member (no heap allocation). The internal packet assembly buffer is allocated on first call to `get_next_packet()` (`get_next_data()` requires no heap allocation):

| Buffer                | Size       | Description                           |
| --------------------- | ---------- | ------------------------------------- |
| `page_header_staging` | 282 bytes  | Inline header accumulation and segment table |
| `internal_buffer`     | min -> max | Packet assembly, grows as needed      |

**Typical memory usage**: 1-4 KB per demuxer instance for most audio streams.

**Zero-copy optimization**: Packets are returned as zero-copy pointers when they fit entirely within the input buffer. Internal buffering only occurs when packets span page or input buffer boundaries.

## CRC Validation

CRC validation is **disabled by default** due to a fundamental limitation of the zero-copy architecture. Ogg pages contain a CRC32 checksum that covers the entire page (header + body). However, the demuxer returns packets as soon as they are found in the input buffer to enable zero-copy optimization. Since CRC validation requires the complete page, validation can only occur after the entire page body has been read.

This means:

1. Packets 1 through N-1 on a page are returned with `OGG_OK` before CRC validation
2. CRC validation occurs when the final packet (N) on the page is ready
3. If CRC fails, the final packet returns `OGG_CRC_FAILED` instead of `OGG_OK`
4. The caller may have already processed the earlier (potentially corrupted) packets

For most use cases, leave CRC disabled (the default). Consider enabling CRC only if:

- You need to detect data corruption and can handle discarding previously returned packets
- You're implementing a validation tool rather than real-time playback
- Your application can buffer and defer processing until page boundaries

### Using CRC Validation

If you enable CRC and want strict validation before processing packets, you must manually buffer packet data and track page boundaries:

1. **Copy packet data** - The `packet.data` pointer is only valid until the next `get_next_packet()` call, so you must copy the data to your own buffer
2. **Track page boundaries** - Use `packet.is_last_on_page` to know when a page ends
3. **Check for CRC errors** - CRC validation occurs when the last packet on a page is processed. If validation fails, `OGG_CRC_FAILED` is returned instead of `OGG_OK` for that final packet
4. **Discard on failure** - If CRC fails, discard all buffered packets from that page (the earlier packets that were returned successfully)

```cpp
std::vector<std::vector<uint8_t>> page_packets;

while (have_data) {
    auto state = demuxer.get_next_packet(input, len);

    if (state.result == OGG_OK) {
        // Copy packet data (pointer only valid until next call)
        page_packets.emplace_back(
            state.packet.data,
            state.packet.data + state.packet.length
        );

        if (state.packet.is_last_on_page) {
            // Page complete and CRC valid - process all packets from this page
            for (auto& pkt : page_packets) {
                processPacket(pkt.data(), pkt.size());
            }
            page_packets.clear();
        }
    } else if (state.result == OGG_CRC_FAILED) {
        // CRC failed on last packet - discard ALL packets from this page
        page_packets.clear();
        // Demuxer automatically moves to next page
    }

    input += state.bytes_consumed;
    len -= state.bytes_consumed;
}
```

## Thread Safety

Each `OggDemuxer` instance must be used from a single thread only. The demuxer maintains internal state that is not thread-safe.

For concurrent demuxing of multiple streams, create a separate `OggDemuxer` instance per thread:

```cpp
// Thread 1
micro_ogg::OggDemuxer demuxer1;
// ... use demuxer1

// Thread 2
micro_ogg::OggDemuxer demuxer2;
// ... use demuxer2
```

## Design Philosophy

microOggDemuxer is designed with these principles:

1. **Platform-agnostic**: Works on embedded systems, desktop, and anywhere C++11 runs
2. **Zero dependencies**: No libogg, no platform-specific code
3. **Memory efficient**: Minimal allocation, dynamic growth, zero-copy when possible
4. **Flexible**: Custom allocators, configurable buffer sizes, optional CRC
5. **RFC 3533 based**: Single logical bitstream Ogg page demuxing. For multiplexed files (grouped streams), callers must filter pages by serial number externally.

## Performance

Buffer growth strategy:

- Starts at `min_buffer_size` (default 1KB)
- Doubles in size when more space needed
- Caps at `max_buffer_size` (default 8KB)
- Packets exceeding max size are skipped (not an error)

For typical audio streams:

- Initial allocation: 1 KB
- Typical usage: 1-2 KB
- Maximum allocation: 8 KB (configurable)

### Zero-Copy Effectiveness

Zero-copy optimization occurs when a complete packet fits within your input buffer without spanning buffer or page boundaries. The actual zero-copy rate depends heavily on:

- **Input buffer size**: Larger buffers increase zero-copy likelihood
- **Codec packet sizes**: Opus packets (~50-300 bytes) are smaller than FLAC packets
- **Ogg page structure**: Packets spanning pages always require internal buffering

As a reference point: with a 4 KB input buffer and Ogg Opus audio, approximately 95-99% of packets use zero-copy. Your results will vary based on your specific buffer size, codec, and encoding settings.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions welcome! Please ensure:

- No platform-specific code
- No external dependencies
- Maintain zero-copy design
- Follow existing code style
- Add tests for new features

## References

- [RFC 3533: The Ogg Encapsulation Format](https://www.rfc-editor.org/rfc/rfc3533)
- [Ogg container format](https://xiph.org/ogg/)
