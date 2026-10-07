# Third-party code in the Sendspin player

`libhapaneld_sendspin.so` is the synchronised-audio player behind `media/SendspinNative.kt`. It is built from this directory only: `CMakeLists.txt` points FetchContent at the vendored trees below with `FETCHCONTENT_FULLY_DISCONNECTED`, so nothing is fetched at build time. Each subset keeps its upstream licence text verbatim. Changes to the vendored sources are listed in [`PATCHES.md`](PATCHES.md); apart from those, the copies are unmodified upstream files with unused trees removed.

`sendspin_jni.cpp` and `CMakeLists.txt` in this directory are ha-paneld code (Apache-2.0).

## Vendored subsets (`third_party/`)

| Subset | Upstream | Version / commit | Licence |
| --- | --- | --- | --- |
| `sendspin-cpp/` | https://github.com/Sendspin/sendspin-cpp | `main` at `1d9ef34a97e5422ce634b162910e60001d213ced` | Apache-2.0 (`sendspin-cpp/LICENSE`) |
| `arduinojson/` | https://github.com/bblanchon/ArduinoJson | `v7.4.1`, `32520135092970120a5ac165cf45f48e658c421d` | MIT (`arduinojson/LICENSE.txt`) |
| `micro-flac/` | https://github.com/esphome-libs/micro-flac | `v0.1.1`, `9f8bfe5c9ee46cea175084b49ae8ac95545705b5` | Apache-2.0 (`micro-flac/LICENSE`) |
| `micro-flac/lib/micro-ogg-demuxer/` | https://github.com/esphome-libs/micro-ogg-demuxer (micro-flac submodule) | `v1.2.0`, `865ad9d831e7dc76bb9c142607bae33fc75648e7` | Apache-2.0 (`micro-flac/lib/micro-ogg-demuxer/LICENSE`) |
| `ixwebsocket/` | https://github.com/machinezone/IXWebSocket | `v12.0.1`, `64fae7676bd8fe31f7cb4bcde7a6841892dad65e` | BSD-3-Clause (`ixwebsocket/LICENSE.txt`) |
| `noise-c/` | https://github.com/esphome-libs/noise-c (fork of rweather/noise-c) | `v0.1.13`, `a1e08809a1b8f65cd91765ba7d68a6d00648ad61` | MIT (`noise-c/LICENSE`, `noise-c/COPYING`) |

What each subset contains:

- `sendspin-cpp/`: `CMakeLists.txt`, `cmake/`, `include/`, `src/` without `src/esp/` (ESP-IDF only), and `LICENSE`. Tests, examples, docs and tools are omitted. Only the player role is compiled; the optional third codec is disabled (`SENDSPIN_ENABLE_OPUS=OFF`) and its library is not vendored.
- `arduinojson/`: the header-only library (`src/`, `ArduinoJson.h`), its `CMakeLists.txt` and `extras/ArduinoJsonConfig.cmake.in` (read at configure time). Examples, tests and fuzzing are omitted.
- `micro-flac/`: `CMakeLists.txt`, `cmake/`, `include/`, `src/` and the `lib/micro-ogg-demuxer` submodule (`CMakeLists.txt`, `include/`, `src/`). Built only because sendspin-cpp's decoder references it unconditionally; the app advertises PCM only. Examples and test audio are omitted.
- `ixwebsocket/`: `CMakeLists.txt`, `CMake/`, the `ixwebsocket/` sources and the `*.in` templates its CMake reads. Built with `USE_TLS=OFF` and `USE_ZLIB=OFF`. The `ws` tool, tests, docs, Docker files and bundled `third_party/` are omitted.
- `noise-c/`: `include/` and `src/`. sendspin-cpp builds its own `noise_c` target from the reference backend (ChaChaPoly, Curve25519, SHA-256); the upstream build files, docs, examples and tests are omitted.

## Normalisation

The copies are otherwise unmodified apart from line-ending and trailing-whitespace normalisation (CRLF to LF, trailing blanks and blank lines at end of file dropped), and noise-c's unused AES sources (`src/crypto/aes/`), which are dropped because the build sets `NOISE_USE_AES=0`.
