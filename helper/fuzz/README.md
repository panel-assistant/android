# Helper parser fuzzing

The existing deterministic sanitizer smoke and the clang libFuzzer target share `fuzz_parser.c`, the real production `CORE_SRCS`, `dispatch()` and `server_serve()`. libFuzzer uses sanitizer coverage feedback to retain inputs and mutate the corpus. Both modes enable AddressSanitizer, UndefinedBehaviorSanitizer and leak detection.

```bash
./helper/fuzz/run.sh                    # deterministic corpus, bounds and 100,000 random inputs
./helper/fuzz/run.sh 1000               # shorter smoke run
./helper/fuzz/run.sh --libfuzzer 30      # bounded coverage-guided run, seconds excluding compilation
CLANG=clang-18 ./helper/fuzz/run.sh --libfuzzer 300
make -C helper fuzz                     # deterministic smoke
```

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
