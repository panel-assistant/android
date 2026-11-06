# hapaneld-helper — root helper daemon for sandbox-walled panels

A tiny root daemon that gives ha-paneld a whitelisted, authenticated control surface for the things a sandboxed Android app can't reach itself: the RGB LED, screen-backlight power, hardware-button and GPIO instrumentation, display density / CPU governor / screencap / perf snapshots, and app reload/start/reboot. It began as an LED-only helper (the former `hapaneld-ledd`) and was **renamed `hapaneld-helper`** to match its broader role; the code is split by capability under [`src/`](#source-layout). The app installs and updates its bundled helper — see [Boot persistence](#boot-persistence-init-service).

## Why it exists

Panels expose their RGB LED one of two ways:

- **App-accessible char device** (e.g. rk3576 `/dev/ledjni`, world-rwx, SELinux `device` label). ha-paneld drives these **directly** from its own NDK — no helper, no root.
- **Root-only sysfs** (e.g. Tuya TPA10 `/sys/class/leds/avs-pwm-led/avsux_animation`, SELinux `sysfs_lights`, `system:system`). A normal Android app (`untrusted_app` domain) **cannot** write this node, and **cannot** exec `su` to escalate. On these panels the RGB capability lives behind the Android lights HAL / vendor system service and is reachable only by system/root code.

For the second class, `hapaneld-helper` runs **outside the app sandbox** in a root domain that *can* write the node, and exposes a minimal command surface on an **abstract-namespace UNIX socket** (`@hapaneld-helper`). ha-paneld connects to it and asks for colours. The app stays a single uniform API; the privilege is isolated here. The socket is authenticated by peer uid (see [Safety](#safety)), so no other app on the panel can reach it — unlike the earlier loopback-TCP listener.

## Protocol

Newline-terminated ASCII on the abstract UNIX socket `@hapaneld-helper`. One or more commands per connection.

| Command | Effect | Reply |
| --- | --- | --- |
| `VERSION` | read the helper identity without touching hardware | `HELPER version=1.9.0 proto=1.9` / `ERR` when arguments are supplied |
| `RGB <r> <g> <b>` | set LED colour (each 0..255) | `OK` / `ERR` |
| `OFF` | LED off | `OK` / `ERR` |
| `BTN <0..255>` | button-backlight brightness | `OK` / `ERR` |
| `LEDPROBE` | which RGB-LED backend this panel has (so the app gates the LED entity on a reachable node) | `ledjni` / `sysfs` / `none` |
| `SCREEN ON` / `SCREEN OFF` | screen backlight power (`bl_power` 0/4) | `OK` / `ERR` |
| `KEYEVENT SLEEP` / `KEYEVENT WAKEUP` | Android screen power for panels with no `/sys/class/backlight` device, by injecting the named key. Named keys only — there is no numeric form, so no caller or profile can select another keycode. `input` is an app_process wrapper and can wedge instead of failing, so the injection runs under a deadline; at it, the wrapper's whole process group is killed and reaped rather than holding the connection | `OK` (the request ran) / `ERR` |
| `BLPOWER` | read physical screen-backlight power (`bl_power`) | `0`–`4` / `ERR` |
| `BLREAD` | read effective and maximum backlight brightness | `<actual> <max>` / `ERR` |
| `BLSET <n>` | set hardware backlight brightness, clamped to its maximum | `OK` / `ERR` |
| `SCREENCAP` | capture the screen as PNG | raw PNG bytes, then EOF |
| `LOGCATCAPS` | probe the authenticated full-system log stream; older helpers return `ERR` | `LOGCATCAPS 1` / `ERR` for arguments |
| `LOGCAT` | follow all Android log buffers from the current time using fixed `logcat -b all -T 1 -v long -v epoch` arguments; the client closes the socket to stop the stream | `OK`, then raw long-format lines including blank entry separators / `ERR` for arguments or start failure |
| `LOGCATDUMP <n>` | one bounded backlog of the last `n` (1–1000) entries from all Android log buffers using fixed `logcat -b all -v long -v epoch -v printable -d -t <n>` arguments; no other argument is accepted. The child runs under a 3 s deadline and is killed and reaped at it, and output stops at 256 KiB, so the last entry may be cut. Protocol 1.4 and later | `OK`, then raw long-format lines, then EOF / `ERR` for a missing, out-of-range or malformed count or start failure |
| `RELOAD <pkg>` | force-stop + relaunch an app (dashboard reload); serialized with supported Companion data transactions | `OK` / `ERR` / `BUSY` |
| `START <pkg/cls>` | launch an activity by component (root, bypasses BAL limits); serialized with supported Companion data transactions | `OK` / `ERR` / `BUSY` |
| `SETHOME <pkg/cls>` | set the default home (launcher) to a component — re-asserts the dashboard app as home after a HOME-app install clears it | `OK` / `ERR` |
| `OVERLAY <pkg> [mode]` | read or set the package's `SYSTEM_ALERT_WINDOW` app-op; omit `mode` to query, or use `allow`, `deny`, `ignore`, `default`, or `foreground` to restore an exact prior state | `MODE=<mode>` / `OK` / `ERR` |
| `APPSTATE <pkg>` | app-watchdog probe: is the package alive and focused? (`pidof` + focused window) | `FG` / `BG` / `DEAD` / `ERR` |
| `COMPANIONCAPS` | exact Companion data-protocol compatibility probe | `COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL` |
| `COMPANIONSTATUS` | report whether the daemon-wide Companion transaction lane is available | `IDLE` / `BUSY` |
| `COMPANIONBACKUP <pkg>` | recover any interrupted transaction, force-stop a supported HA Companion package, descriptor-open and validate its fixed login files, stream a bounded raw snapshot including optional DB WAL/SHM, then relaunch only from a proven-safe state | `BACKUP <count> <bytes>` + framed `FILE <relative> <bytes>` payloads + `DONE [RELAUNCH_ERR]`, or `BUSY` / `ERR [RELAUNCH_ERR]` |
| `COMPANIONRESTORE <pkg> <db-bytes\|-> <session-bytes\|-> <integration-bytes\|->` | receive the fixed restore set into root-owned staging, recover any interrupted transaction, force-stop, replace the allowlisted files through descriptor-relative staging/rename with durable rollback/commit markers, remove stale DB WAL/SHM, then relaunch only after commit or confirmed rollback | `READY`, then `OK` / `COMMITTED RELAUNCH_ERR` / `ROLLED_BACK [RELAUNCH_ERR]` / `ROLLBACK_FAILED RELAUNCH_SUPPRESSED`; `BUSY` / `STREAMERR` before upload |
| `INPUTV2` | advertise truthful initial evdev open/grab acknowledgement semantics; older helpers return their normal unknown-verb `ERR` | `OK` |
| `WATCH <evdev> <0\|1>` | open a `/dev/input/eventN` node; `1` requires `EVIOCGRAB` (suppress the default Android action). Idempotent only for the same node and grab policy | `OK` only after the initial open, requested grab, and reader start succeed; otherwise `ERR` |
| `SUBSCRIBE` | acquire requested grabs and stream async `KEY <code> <value>` / `SW <code> <value>` lines from every `WATCH`ed node until disconnect | `OK` only after required grabs are active; `ERR` on grab failure or subscriber overflow |
| `GPIOV1` | advertise the generic GPIO watch/stream contract; older helpers return their normal unknown-verb `ERR` | `OK` / `ERR` when arguments are supplied |
| `GPIORESET` | clear the GPIO watch table before configuring a new sensor runtime; independent of evdev `WATCHRESET` | `OK`, or `ERR` while any GPIO subscriber is active / when arguments are supplied |
| `GPIOWATCH <n>` | hold and watch the already-exported `/sys/class/gpio/gpio<n>/value`, with `n` restricted to 0–65535 and at most eight distinct watches | `OK` only after the initial open/read and reader start succeed; otherwise `ERR` |
| `GPIOSUBSCRIBE` | stream current, changed, and unavailable GPIO status independently of evdev subscribers | `OK`, then `GPIO <n> <0\|1>` or `GPIOUNAVAILABLE <n>` records until disconnect; `ERR` on subscriber overflow / arguments |
| `DENSITY` / `DENSITY <n>\|reset` | get display density / set it (`wm density`) | `PHYS=<n> OVER=<n\|->` / `ERR` (get) · `OK` / `ERR` (set) |
| `FONTSCALE` / `FONTSCALE <n>\|reset` | get system font scale / set it (`settings system font_scale`) | `SCALE=<n\|null>` / `ERR` (get) · `OK` / `ERR` (set) |
| `GOVREAD CURRENT` / `GOVREAD AVAILABLE` | read the fixed cpu0 scaling governor / available governors directly, without subprocesses (protocol 1.5) | `GOVREAD <tokens>` / `ERR` |
| `GOV <name>` | set the CPU scaling governor on all cores | `OK` / `ERR` |
| `ZIGBEESTATE` | argument-free, fixed Sonoff gateway footprint and complete process observation (protocol 1.6) | ordered `ZIGBEESTATE 1` frame ending `END`, or `ERR` |
| `ZIGBEEHEALTH` | the same observation plus process start/jiffy samples, total CPU, bounded watchdog and network evidence (protocol 1.7) | ordered `ZIGBEEHEALTH 1` frame ending `END`, or `ERR` |
| `ZIGBEEROLE` | argument-free, one fixed local broker topic under a 3500 ms child deadline (protocol 1.8) | `ZIGBEEROLE <hex JSON bytes>` / `ERR` |
| `WIFISTATUS` | argument-free bounded current Wi-Fi fields from fixed Android commands (protocol 1.9) | `SSID=<hex or -> RSSI=<dBm or ->` / `ERR` |
| `ZIGBEECONTAIN` | argument-free containment for the exact vendor-native Sonoff `/vendor/bin/siliconlabs_host` layout; targets only its guard, gateway and matching broker, then demotes a surviving gateway | `OK` / `PARTIAL` / `ERR` |
| `PERFDUMP` | CPU/load/temp/gpu/process snapshot (for sandbox-walled apps) | marker-delimited stream, then EOF |
| `CHT8305` | room temp/humidity from exact allowlisted input layouts (`temperature`/`humidity` or ZX-SMT156 `sun-ths`/`sun-hum`) | `T=<centi> H=<centi>` / `ERR` |
| `REBOOT` / `REBOOT AWAIT` | reboot the panel through every mechanism in turn rather than trusting a zero exit status. Each mechanism gets a fixed window: it runs in its own process group under that window as a hard deadline, the whole group is killed and reaped if it has not returned by then (so a wrapper's forked worker dies with it), and any unused remainder is waited out, so a wedged actuator costs the same as a fast one and never blocks the mechanisms behind it. `REBOOT` accepts first and then goes down; `REBOOT AWAIT` answers only when the reboot demonstrably did not happen, so the client's EOF is the success signal | `OK` (then down) · `ERR` when every mechanism ran and the panel is still up, or the argument is not `AWAIT` |
| `GRANT <pkg> <capability>` | apply one grant from a fixed table to the app's own package: `NOTIFICATIONS`, `MICROPHONE`, `CAMERA` (runtime permissions), `WRITESETTINGS`, `OVERLAY` (app-ops), `BATTERY` (deviceidle whitelist, `cmd` with a `dumpsys` fallback), `ACCESSIBILITY` (append the panel service to the shared secure setting and enable accessibility, idempotently). No free-form permission, app-op or setting name is accepted, and no other package | `OK` / `ERR` |
| `PING` | liveness probe | `OK` |
| anything else | — | `ERR` |

`GOVREAD` accepts exactly `CURRENT` or `AVAILABLE`, with no extra arguments. It reads only
`/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor` or `scaling_available_governors` in the same
directory. Each returned token matches `[A-Za-z0-9_.-]{1,63}`; `CURRENT` requires exactly one token,
and `AVAILABLE` requires one or more in their original order. Spaces, tabs, CR and LF are normalized
to single spaces between tokens. The reply has one trailing newline. Empty input, other control bytes,
NUL, invalid tokens, open/read failure, or raw input reaching the 4096-byte overflow probe returns
`ERR`; at most 4095 raw bytes can succeed. There is no subprocess fallback. The broader read grammar
does not change the governor writer's allowlist.

`WIFISTATUS` executes only `/system/bin/cmd wifi status`, with `/system/bin/dumpsys wifi`
as a fallback after a complete nonzero exit. Both share one 2000 ms monotonic budget and a
128 KiB complete-capture bound per attempt (at most 256 KiB read across both attempts, with one
reused buffer and only the selected capture parsed). Timeout, overflow, I/O failure and embedded NUL never trigger
fallback; nonzero primary output is discarded, while failure prefixes in the selected successful
capture return `ERR`. Only one recognized current `WifiInfo:` or `mWifiInfo` record is admitted;
historical RSSI and the connection banner cannot supply fields. Multiple records are ambiguous.
The ordered reply is `SSID=<lowercase hex or -> RSSI=<-126..0 or ->` followed by one newline,
at most 80 bytes. SSID and RSSI can independently be unavailable. SSIDs must be 1–32 bytes of
printable UTF-8; quoted commas are supported. Unknown SSIDs, ambiguous quoting, hex/unsupported
representations, controls, malformed UTF-8 and oversized SSIDs are unavailable. No raw dump,
BSSID or MAC address is returned. These formats follow Android's WifiShellCommand/WifiInfo;
no vendor-specific stdout format is assumed.

`ZIGBEESTATE`, `ZIGBEEHEALTH` and `ZIGBEEROLE` accept no arguments. Paths are fixed to
`/vendor/bin/siliconlabs_host`, `/data/vendor/siliconlabs_host` and `/proc`; callers cannot select
another layout or target. A missing gateway directory alone never proves stopped: state and health
complete a bounded scan (at most 8192 numeric process entries), skip vanished entries, zombies and
kernel threads, and verify candidate executable inodes against the fixed gateway binary or platform
shell. An unreadable, malformed, oversized or ambiguous observation, duplicate exact process, or
symlinked/non-directory gateway tree returns `ERR`. No state or health read executes a subprocess.

The state frame contains these exact ordered lines after `ZIGBEESTATE 1`: `managed=`, `guard=`,
`run=`, `binary=`, `broker=`, `package_marker=`, `running=`, `guard_running=`, `package=`, then `END`.
Flags are `0` or `1`. Package is lowercase hex encoding at most 120 printable UTF-8 bytes (empty
allowed); the frame is at most 512 bytes. File flags describe regular, non-symlinked files; binary
and broker must also have an executable bit. Absence requires a complete process scan.

Health uses the same fields after `ZIGBEEHEALTH 1`, then `gateway_pid=`, `gateway_start=`,
`gateway_jiffies=`, `guard_pid=`, `guard_start=`, `guard_jiffies=`, `total_jiffies=`, `recursive=`,
`netinfo=`, and `END`. Process triplets are all canonical decimal values or all `-` when absent;
start time is Linux start ticks, and jiffies are user plus system ticks. Total CPU sums the first
eight aggregate counters (guest ticks are already included). Values fit signed 64-bit integers;
PIDs fit a positive signed 32-bit integer. `recursive` is a `0`/`1` flag for the exact legacy recursive
library-path assignment in a bounded 64 KiB guard script. `netinfo` is lowercase hex of at most 4096
opaque bytes, or `-` when unavailable. The authoritative canonical file wins; only an absent/empty
file permits the fixed gateway-log fallback (last 200 lines, bounded to 64 KiB per file). Nonregular,
unreadable or oversized canonical evidence stays unknown. The app retains JSON parsing and public
redaction; raw network evidence must never become public attributes. Health is at most 9216 bytes.

Role runs only `/vendor/bin/siliconlabs_host/mosquitto_sub` with fixed argv
`-h 127.0.0.1 -p 1883 -i hapaneld_zr -t zigbee/system/network-role/information -C 1 -W 3`.
Only this child receives `LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host`; the daemon environment is
unchanged. A failed, empty, NUL-containing, oversized or timed-out result returns `ERR`, without a
shell or fallback. Success returns complete broker JSON as lowercase hex (at most 4096 raw bytes,
8204 wire bytes); the app validates JSON and the printable role. Every frame ends with a newline.

`VERSION` is the stable compatibility bootstrap. `version` is the helper release version and `proto` is the wire protocol version; clients accept the same protocol major and treat later minor versions as additive. Helpers from before this command return the ordinary unknown-command `ERR`, so the app verifies them with `PING` and reports them as reachable but compatibility-unverified. Run `hapaneld-helper --version` to print the same identity without initializing hardware or binding the socket.

`WATCH`/`SUBSCRIBE` instrument physical buttons the Android input pipeline doesn't deliver to a sandboxed app — e.g. the WF1589T power key (grabbed so it no longer sleeps the panel) and the TPA10 orange button (an `EV_SW` switch, not a key). The **app** chooses which validated profile node to watch and whether to grab it; the daemon streams raw events and the app decides what each means. The app first probes `INPUTV2`, requires each `WATCH` acknowledgement before subscribing, and treats an older helper's stream as usable but diagnostically unverified. A current daemon never degrades a requested exclusive grab into a non-exclusive reader, holds a grab only while at least one subscriber owns delivery, and releases it when the last subscriber disconnects. If a node disappears after setup, its reader retries until it can reopen and, where requested, re-establish the grab.

`GPIOV1`/`GPIORESET`/`GPIOWATCH`/`GPIOSUBSCRIBE` form a separate sensor domain, so a proximity reporter can reconnect and reconfigure without interrupting an existing evdev button subscription. The app supplies the GPIO number from its `DeviceProfile`; the daemon derives only the fixed legacy-sysfs `value` and `edge` paths and never exports a GPIO or changes its direction. It holds the `value` descriptor for the watch lifetime, configures `edge=both` when the kernel exposes a recognised edge attribute, waits on `poll(POLLPRI)`, and restores the prior edge setting on reset. When edge configuration is unavailable, it falls back to a fixed 500 ms sample interval on the same descriptor—there is no shell command or process per sample. Edge mode also rechecks every five seconds so a missed vendor-driver notification cannot leave state stale indefinitely. The first `GPIO <n> <0|1>` record after subscribing is the current binary value; later values are emitted only on change. If the held descriptor becomes unreadable, the helper emits one `GPIOUNAVAILABLE <n>` status before its bounded reopen loop; a recovered descriptor emits its current value and restores availability. As with the evdev stream, a hardware/status event can race ahead of the subscription `OK`, so clients accept valid records during that handshake.

`SCREEN OFF` powers the display backlight down at the hardware level (true off) while leaving the device Awake — no keyguard, so it wakes without a PIN. This is why a panel with a device PIN needs the daemon for screen-off: a sandboxed app can only dim Settings brightness (clamped to a minimum on many panels) and `lockNow()` would force the keyguard.

## Safety

- **Peer-uid authentication.** The transport is an abstract-namespace UNIX socket, so the daemon reads the connecting process's credentials (`SO_PEERCRED`) and accepts **only** ha-paneld's current live uid plus root. ADB operators use a root shell; generic Android shell uid 2000 is rejected because Shizuku also runs authorized applications under that identity. The app's uid is resolved live by `stat`-ing `/data/data/io.panelassistant.android`, because it changes on every reinstall. Every other local app is rejected and the connection closed before a single command runs. (The earlier `127.0.0.1:8889` TCP listener had no auth — any app with `INTERNET` could `REBOOT`/`SCREENCAP` it.)
- **Airtight parsing.** A bounded per-connection line buffer (commands split across reads still parse; overlong lines are dropped, not mis-split or overflowed); every argument `sscanf` is width-bounded; unknown verbs return `ERR`.
- **Fixed Zigbee containment.** `ZIGBEECONTAIN` accepts no arguments and admits only the exact Sonoff vendor-native directory. It does not use broad process-name matching or accept a caller-selected path.
- **Resource limits.** Concurrent connections are capped, and an idle connection is dropped after a timeout — except long-lived `SUBSCRIBE`/`GPIOSUBSCRIBE` streams, which are meant to sit idle reading events. Evdev and GPIO each have fixed eight-watch and eight-subscriber registries, while the global 16-connection cap remains the outer bound. GPIO fallback sampling has fixed 500 ms and two-second reopen intervals, so unavailable or non-edge hardware cannot create a busy loop or a process storm.
- **Bounded GPIO mutation.** `GPIOWATCH` accepts only a decimal number from 0 to 65535. It derives fixed `/sys/class/gpio/gpio<n>/value` and `edge` paths, writes only the recognised `edge` attribute to request `both`, and restores its prior recognised value on reset. It never writes GPIO `export`, `unexport`, `direction`, or `value`.
- **Descriptor-anchored Companion files.** Backup and restore accept only the full/minimal HA Companion package names and the fixed login-file set; no caller pathname reaches the filesystem. Package, parent and file components are opened relative to trusted directory descriptors with `O_NOFOLLOW`, and live files must be app-owned, regular, single-link inodes within strict per-file and aggregate bounds. Restore uploads first enter a root-owned compartment, then a single helper-owned transaction stages replacement inodes, preserves owner and SELinux label, and fsyncs a fixed-format prepared marker before moving old live files. Old-to-rollback renames are directory-fsynced before new live files are installed; a separately named committed marker is atomically renamed and fsynced before rollback cleanup. After a daemon or power interruption, the next Companion data command rolls a prepared transaction back or finishes a committed transaction, and unexplained rollback artifacts fail closed rather than being deleted. Supported Companion `START`/`RELOAD` requests return `BUSY` while the mutex is held and while any durable marker or unexplained rollback remains after a daemon restart.
- The commands that shell out (`RELOAD`, `START`, `REBOOT` via `am`/`svc`) sanitise their argument against a strict char-whitelist; the LED/backlight writes touch **only** the whitelisted nodes (`avs-pwm-led/avsux_animation`, `button-backlight/brightness`).

> [!CAUTION]
> The daemon never writes `avs-pwm-led/avsux_select` or `custom_animation`. Those are firmware-backed and **reliably reboot the TPA10**.

A set colour **holds** until the next command (no auto-revert); `OFF` writes black. Handing the LED back to the vendor's idle animation currently requires a reboot.

## Extending the helper (contributor guide)

ha-paneld's per-panel facts live in versioned **runtime YAML profiles** on the app side. A new panel that can use existing compiled mechanisms should need only one YAML profile; a genuinely new mechanism still requires a reviewed core driver. The daemon honours the same boundary: it remains **panel-blind**, exposing generic, parameterised primitives while validated profile data selects a compiled mechanism and its bounded targets.

How well each capability meets that today:

| Capability | Where panel specifics live | New-panel change |
| --- | --- | --- |
| **Buttons** (`WATCH`/`SUBSCRIBE`) | the app passes the evdev node + grab flag from its profile; daemon streams raw events | **profile only** — no daemon change |
| **GPIO inputs** (`GPIOWATCH`/`GPIOSUBSCRIBE`) | the app passes a bounded GPIO number from its profile; daemon derives fixed sysfs paths and streams binary values | **profile only** — no daemon change |
| **Screen** (`SCREEN`, `KEYEVENT`) | daemon auto-discovers `/sys/class/backlight`; `KEYEVENT` covers panels that have no backlight class at all | **profile only** — the app picks the route from `hardware.screen_off` |
| **Reboot / reload / start** | generic `am`/`svc` | **none** |
| **LED** (`RGB`/`OFF`/`BTN`) | **hardcoded** to the TPA10 sysfs node + its `avsux` write format | **core change** — see below |

> [!NOTE]
> **Buttons are the reference pattern.** `WATCH` takes the target *as a parameter from the profile*, constrained to a `/dev/input/` prefix. A new panel with an unusual physical button needs **zero** daemon edits — just a validated `input.evdev_buttons` entry in its YAML profile. New capabilities should copy this shape.

### The one current seam: LED

`RGB`/`OFF`/`BTN` hardcode the TPA10 node paths (`avs-pwm-led/avsux_animation`, `button-backlight/brightness`) and that panel's `HOLD_MS:RRGGBB` write format. A panel with a root-only LED at a *different* path or format can't be added without editing the shared `set_rgb`/`write_node` core. The intended fix is to **parameterise it like `WATCH`** (the TPA10 is the only daemon-LED panel; rk3576/px30 drive LEDs app-direct):

```text
LED <node> <payload>        # app supplies the target + payload from its DeviceProfile
```

constrained to a `/sys/class/leds/` prefix with bounded values. That moves per-panel LED specifics into the profile and returns the daemon to panel-blind. Until then, **LED-via-daemon is the one path that requires a core PR** — a contributor adding it should expect to touch shared C and have it reviewed for the safety model below.

### Adding a new device class (i2c, IR, haptics, …)

The daemon is the right home for any capability that needs **root or system privilege a sandboxed app can't reach** — e.g. an i2c sensor whose sysfs is `system`-owned, an IR blaster `/dev` node, a PWM/haptic. The extension recipe:

1. Add a small, self-contained handler in the module that owns the capability (a new `src/<name>.c` for a new device class), then **register the verb** with one row in the `COMMANDS` table in `src/dispatch.c`. That's the whole wiring — `dispatch()` matches the verb *exactly* and hands your handler the argument string.
2. **Take the target as a parameter** from the app (don't hardcode a panel's path), and **whitelist it by class prefix** — the way `WATCH` restricts to `/dev/input/` and a future `LED` would restrict to `/sys/class/leds/`. For a reader (i2c/sensor), stream values to `SUBSCRIBE`rs using the existing async-line mechanism in `src/input.c`.
3. Keep the safety model intact: the peer-uid auth gates the whole socket, but still bound every buffer, validate/clamp inputs (the `src/util.c` validators), width-bound every `sscanf`, pass request-derived values only through structural `sysexec_*_argv()` calls or direct filesystem operations, and never write firmware-backed nodes (see the CAUTION above). Shell programs are reserved for audited argument-free constants.
4. Publish the matching HA entity from the app per `DeviceProfile` capability — the daemon stays the privileged mechanism, the profile stays the policy.

A genuinely new *primitive* is the one acceptable reason to change the core daemon; per-panel *selection* of existing primitives must stay in the `DeviceProfile`. That boundary keeps a unique panel's contribution to a single profile file wherever the underlying primitive already exists.

## Source layout

The daemon is split by capability under `helper/src/` (the binary, `@hapaneld-helper` socket, and init service keep their historical names — only the source is modular):

| File | Responsibility |
| --- | --- |
| `main.c` | accept loop, abstract-socket bind, `SO_PEERCRED` peer-auth, connection cap |
| `identity.c` | the one table of app package ids, their data directories and the caller enum — read by peer-auth, the path and package validators, Guard, and the package-restricted verbs |
| `server.c` | the bounded line accumulator (`server_serve`) + idle timeout |
| `commands.def` / `dispatch.c` | the shared verb→handler manifest + exact-match `dispatch()` |
| `version.c` | stable helper and protocol identity exposed by `VERSION` and `--version` |
| `led.c` / `screen.c` / `input.c` / `gpio.c` | LED (sysfs + ledjni), backlight power, evdev buttons, held-descriptor GPIO streams |
| `sysctl.c` | density / governor read-back and control / reload / start / reboot / screencap privileged operations |
| `companion.c` | descriptor-anchored, bounded HA Companion backup/restore transaction |
| `perf.c` | `PERFDUMP` `/proc` snapshot |
| `zigbee.c` | bounded fixed-layout gateway state, health and role observations |
| `logcat.c` | bounded full-system log stream; sends complete entries only, with a drop record when entry or rate limits apply |
| `cht8305.c` | `CHT8305`-compatible room climate read from exact input names/axes (`EVIOCGABS`, no exec) |
| `util.c` | clamp, node IO, the argument validators |
| `sysexec.c` | **the only file that execs / pipes / spawns / reboots** — request values use absolute paths and argv |

Isolating `sysexec` keeps the entire privilege/injection surface in one auditable file, and lets the sanitizer-smoke and unit-test builds swap it for a stub so the real parser runs on the host with no side effects.

## Build and test

```bash
./helper/build.sh        # cross-compile both ABIs at Android API 26 -> helper/dist/<abi>/hapaneld-helper

make -C helper           # host -Werror compile check (the same src/*.c set)
make -C helper test      # host-native unit tests (validators, clamp, /proc parser, dispatch, accumulator)
make -C helper fuzz      # bounded in-house ASan+UBSan smoke — see fuzz/README.md for its limits
```

Or compile by hand with any Android NDK: `*-clang -O2 -s -Ihelper/src -o hapaneld-helper helper/src/*.c`.

## Installation

The panel app installs and updates the helper bundled in its APK through `BundledHelperInstaller`. Install and update the app through Panel Assistant; the standalone shell installer is retired.

## Boot persistence (init service)

Every supported route executes `/data/local/hapaneld-helper --supervise`; system, vendor, and systemless storage contain boot registrations only. The app chooses the first verified registration route:

- A writable-system/userdebug panel receives the init registration in `/system/etc/init`.
- Otherwise, a writable vendor init directory receives the same registration in `/vendor/etc/init`.
- Otherwise, a read-only-system panel is accepted only when a supported Magisk, KernelSU, or APatch service runner is detected; it receives a `service.d` launcher under `/data/adb/service.d`.

The bundled executable also supports `--adb-recovery`, independently of the ordinary helper daemon. This mode serves the app/root UID on a separate authenticated abstract socket and accepts bounded administrator shell programs from the app's negotiated Panel Assistant session. Browser and HTTP callers cannot use that channel. Shell execution returns merged byte output and exit status once, with a deadline, process-group cleanup and a 32 KiB output ceiling. The ordinary helper socket retains its fixed-command surface.

Before managed network ADB closure, this separate owner establishes a persistent positive boot fallback and an independently watched healthy-app lease. Closure stops adbd using runtime properties; the firmware's boot path restores the positive baseline, and a fresh compatible session reapplies closure. Expiry, app removal, execution-worker failure or either recovery-owner failure restores network ADB. An explicit recovery request holds it open until a later compatible session. The separate executable and its root-owned directory do not replace the ordinary helper, its boot registration or its recovery custody.

Hardened mode retains physical approval for each exact maintenance request. Automatic recovery also applies in Hardened mode: it invalidates the app's durable security authority before reopening. If allocating a fence fails, recovery retires the old authority without allocating a new file. If storage prevents every durable invalidation, emergency recovery still opens ADB and reports security as unverified; fresh live OFF proof remains mandatory before database maintenance can be admitted.
