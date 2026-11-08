# Helper parser fuzzing

The existing deterministic sanitizer smoke and the clang libFuzzer target share `fuzz_parser.c`, the real production `CORE_SRCS`, `dispatch()` and `server_serve()`. libFuzzer uses sanitizer coverage feedback to retain inputs and mutate the corpus. Both modes enable AddressSanitizer, UndefinedBehaviorSanitizer and leak detection.

```bash
./helper/fuzz/run.sh                    # deterministic corpus, bounds and 100,000 random inputs
./helper/fuzz/run.sh 1000               # shorter smoke run
./helper/fuzz/run.sh --libfuzzer 30      # bounded coverage-guided run, seconds excluding compilation
CLANG=clang-18 ./helper/fuzz/run.sh --libfuzzer 300
make -C helper fuzz                     # deterministic smoke
```

`--target` selects one of three harnesses (default `parser`): `parser` is the main socket's command parser and every handler behind `dispatch()`; `recovery` is the lifecycle owner's own request parser in `src/adb_recovery.c` (`STATUS`, `ARM`, `BEAT`, `RECOVER` and the `SHELL` header and body), driven through its production line reader; `cdprelay` is the CDP relay's argument parser and LAN peer filter in `cdprelay.c`. Each target keeps its seeds, corpus and artifacts under `$HAPANELD_FUZZ_OUT/<target>/` (default `build/`). `HAPANELD_FUZZ_PHASE=build` writes seeds and compiles only; `=run` reuses that binary.

## Every change: `make test`

`make -C helper test FUZZ_SECONDS=35 CLANG=clang-18` builds the fuzz drivers with the unit binaries and then runs two slots beside the unit targets: the parser target for `FUZZ_SECONDS`, and in the other slot the deterministic parser corpus followed by the recovery target (two thirds of the budget) and the CDP relay (one third). `FUZZ_OUT` keeps the evolved corpus between runs and `FUZZ_FLAGS` passes libFuzzer options (CI passes `-seed=<run number>`). Without `FUZZ_SECONDS`, `make test` runs no fuzzing. The CI "Helper units" job does this on every helper change, restores and saves the corpus with the Actions cache, and uploads `build/*/artifacts` when it fails. CI uses 35 s (repository variable `HELPER_FUZZ_SECONDS` overrides it): measured on four CPUs, the step goes from about 58 s to 61–63 s warm, while 60 s would take it to 81–90 s. The nightly campaign runs far longer, so anything the short run misses surfaces there within a day. Each share is at least one second, durations are read as decimal (`00` is 0, `035` is 35, anything else stops make), and `run.sh` refuses every zero form for a campaign, because libFuzzer reads `-max_total_time=0` as no limit. Below four CPUs the fuzz slots starve the recovery tests' real deadlines, so CI skips fuzzing there and the private nightly campaign covers it.

## Rule checks

A sanitizer only catches memory and undefined-behaviour faults. `oracles.c` wraps the production `dispatch()`, `reply()` and every `sysexec_*` entry point to fail the run on wrong behaviour that crashes nothing:

- **critical-package**: no executed command names `android`, `com.android.systemui`, `com.android.settings`, `com.android.phone` or any of the app's own package ids, except the three harmless forms `pm enable`, `pm grant <own app> <permission>` and `appops set <pkg> <op> allow|default`. Every other verb, including one added later, is checked by default.
- **caller-shell**: nothing runs `sh`, `bash`, `dash`, `su`, `toybox` or `busybox` through argv execution, and the one constant shell script (`ZIGBEECONTAIN`) must hash to its audited bytes, so interpolating caller bytes into it fails.
- **reply-line**: every verb outside the explicitly framed ones (streams, binary payloads, recovery verbs) replies exactly one line of printable text ending in a newline. The recovery target applies the same rule to every lifecycle-owner request.
- **argument-gate** (cdprelay): a valid port and socket name reach exactly one socket call; anything invalid is refused before any socket exists.

The critical system packages are listed in the oracle itself, independent of the guard it checks; the app's own ids come from the identity table. `./fuzz/oracle_proof.sh` proves each rule can fail: it builds deliberately broken copies of `helper/` (RELOAD without its critical-package refusal, a shell given a caller argument, a changed constant script, a two-line and an unterminated reply, a silent recovery request, a widened relay port range), requires each to report its rule, and requires the unmodified helper to pass the same corpora. Run it after changing `oracles.c` or the harnesses. A rule check fails with `abort()`, so libFuzzer saves the input to `artifacts/`.

The rules see what the harness reaches: they do not cover a recovery `SHELL` worker's execution (refused here; the native recovery process tests own it), real subprocess behaviour, or replies a handler writes without `reply()`.

Smoke needs GCC (or `CC=clang`); coverage-guided execution needs a host clang with compiler-rt/libFuzzer. Both need Python 3 and Linux. `CLANG` selects the coverage-guided compiler; the Android NDK compiler is not a substitute for a matching host toolchain.

`seeds.py` builds seeds for every verb in `src/commands.def`, refusing a new verb until its syntax seed or no-argument declaration is supplied. Each verb gets syntax, missing/extra arguments, NUL, CRLF, numeric overflow, shell metacharacter and line-boundary cases. Additional valid variants and multiline Guard prepare/define/stream/cancel seeds reach beyond empty-argument rejection. The generated `build/corpus-summary.tsv` lists the seed count and classes per verb; this measures corpus construction, not reached behavior. `build/commands.dict` provides verbs and structured tokens to libFuzzer. The deterministic driver also retains its original adversarial corpus and exercises every manifest verb around `MAX_LINE` and at 4096/65536 bytes.

libFuzzer writes evolved inputs under `build/corpus/` and crash/timeout artifacts under `build/artifacts/`. Preserve those files with the compiler version, source revision and sanitizer diagnostic when reporting a failure. Reproduce an artifact without a timed mutation campaign:

```bash
./helper/fuzz/run.sh --replay helper/fuzz/build/artifacts/crash-<hash>
```

Additional libFuzzer flags may follow the seconds limit, such as `-runs=10000`, `-seed=1234` or `-minimize_crash=1`. A seed makes a fixed binary/corpus replayable; compiler changes and previously evolved corpus entries change exploration. The time limit applies to the fuzz engine, including initial corpus loading, and permits completion of the current input. The per-input timeout is ten seconds.

## Host isolation and scope

`test/sysexec_stub.c` replaces command execution, captured commands, streaming subprocesses, thread spawning and reboot. That stub alone is insufficient: the real handlers also perform filesystem operations directly. `sandbox.c` therefore interposes those calls while the target runs. Fixed `/tmp` test paths map into a fresh private directory beneath `build/sandbox.*`; relative `*at` operations require a directory fd inside that root and reject parent traversal. Other filesystem opens and directory walks fail closed. Device ioctls, peer sockets, process signalling (except the signal-0 liveness probe of the parent, which the Guard supervision gate needs), process/thread creation, executable launch, hard links and ownership/xattr changes are refused. This prevents Guard, install staging, GPIO, sysfs and device paths from mutating shared host fixtures or hardware. Startup verifies refusal boundaries and requires successful private Guard initialization.

The sandbox resets between direct dispatch and socket execution and between inputs, so persistent state from one input cannot make a saved crash depend on a previous input. Multiline socket inputs can create and read private Guard records within that one input. An input that starts with a NUL byte instead carries persisted Guard records (`[selector][length, 2 bytes big-endian][body]` repeated, ended by selector `0xFF`, then command lines). The harness appends the production checksum trailer, writes the records into the private Guard directory, reruns startup reconciliation and serves the commands, so mutation reaches the record deserializers instead of stopping at checksum verification. `record-*` seeds are written at startup from records the real handlers produced. Manifest-backed states additionally need staged APK, baseline and settings files with matching hashes, which this mode does not synthesize; the Guard boundary tests own those states. The prefilled socket and nonblocking replies avoid harness deadlocks from stream commands, reply floods and overlong inputs. This deliberately does not model backpressure or concurrent clients. The syscall boundary applies only while running the target; libFuzzer retains normal access to its corpus and artifacts. The command-capture stub may use its own anonymous temporary streams.

A clean run reports only that exercised paths produced no sanitizer-visible fault. It does not establish semantic correctness, authentication, safe real subprocesses, concurrency, Android behavior or hardware effects. The controlled filesystem is a testing seam, not an operating-system security sandbox for untrusted binaries. Parsers requiring real device/sysfs/procfs data, authenticated accept-loop behavior, and later Guard installation/health/replacement states still need their dedicated native and integration tests. Per-verb seeds and increased sanitizer coverage do not prove all paths were reached.

Protocol 1.16 seeds include all five main-socket recovery verbs, valid lowercase hexadecimal nonces, exact SHELL bodies, binary/NUL payloads, ordinary verb lines inside bodies, missing body bytes, excessive timeout/count and extra header tokens. The direct-dispatch driver supplies the same raw-header metadata as the real socket reader. Raw header admission remains production code. No lifecycle owner is initialized in this parser target, and descriptor transfer is refused, so a valid SHELL seed cannot execute on the host. Accepted IPC records, full 65536-byte body boundaries, shell execution and process cleanup belong to the native recovery process tests; seed coverage does not establish that those paths ran under fuzzing.
