# hapaneld-helper — root helper daemon for sandbox-walled panels

A tiny root daemon that gives ha-paneld a whitelisted, authenticated control surface for the things a sandboxed Android app can't reach itself: the RGB LED, screen-backlight power, hardware-button and GPIO instrumentation, display density / CPU governor / screencap / perf snapshots, and app reload/start/reboot. It began as an LED-only helper (the former `hapaneld-ledd`) and was **renamed `hapaneld-helper`** to match its broader role; the code is split by capability under [`src/`](#source-layout). Upgrading from an old `hapaneld-ledd` install is handled by `install-daemon.sh` (it removes the old binary + init service so both don't run) — see [Boot persistence](#boot-persistence-init-service).

## Why it exists

Panels expose their RGB LED one of two ways:

- **App-accessible char device** (e.g. rk3576 `/dev/ledjni`, world-rwx, SELinux `device` label). ha-paneld drives these **directly** from its own NDK — no helper, no root.
- **Root-only sysfs** (e.g. Tuya TPA10 `/sys/class/leds/avs-pwm-led/avsux_animation`, SELinux `sysfs_lights`, `system:system`). A normal Android app (`untrusted_app` domain) **cannot** write this node, and **cannot** exec `su` to escalate. On these panels the RGB capability lives behind the Android lights HAL / vendor system service and is reachable only by system/root code.

For the second class, `hapaneld-helper` runs **outside the app sandbox** in a root domain that *can* write the node, and exposes a minimal command surface on an **abstract-namespace UNIX socket** (`@hapaneld-helper`). ha-paneld connects to it and asks for colours. The app stays a single uniform API; the privilege is isolated here. The socket is authenticated by peer uid (see [Safety](#safety)), so no other app on the panel can reach it — unlike the earlier loopback-TCP listener.

## Protocol

Newline-terminated ASCII on the abstract UNIX socket `@hapaneld-helper`. One or more commands per connection.

| Command | Effect | Reply |
| --- | --- | --- |
| `VERSION` | read the helper identity without touching hardware | `HELPER version=1.3.1 proto=1.3` / `ERR` when arguments are supplied |
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
| `GOV <name>` | set the CPU scaling governor on all cores | `OK` / `ERR` |
| `ZIGBEECONTAIN` | argument-free containment for the exact vendor-native Sonoff `/vendor/bin/siliconlabs_host` layout; targets only its guard, gateway and matching broker, then demotes a surviving gateway | `OK` / `PARTIAL` / `ERR` |
| `PERFDUMP` | CPU/load/temp/gpu/process snapshot (for sandbox-walled apps) | marker-delimited stream, then EOF |
| `CHT8305` | room temp/humidity from exact allowlisted input layouts (`temperature`/`humidity` or ZX-SMT156 `sun-ths`/`sun-hum`) | `T=<centi> H=<centi>` / `ERR` |
| `REBOOT` / `REBOOT AWAIT` | reboot the panel through every mechanism in turn rather than trusting a zero exit status. Each mechanism gets a fixed window: it runs in its own process group under that window as a hard deadline, the whole group is killed and reaped if it has not returned by then (so a wrapper's forked worker dies with it), and any unused remainder is waited out, so a wedged actuator costs the same as a fast one and never blocks the mechanisms behind it. `REBOOT` accepts first and then goes down; `REBOOT AWAIT` answers only when the reboot demonstrably did not happen, so the client's EOF is the success signal | `OK` (then down) · `ERR` when every mechanism ran and the panel is still up, or the argument is not `AWAIT` |
| `HELPERSTATUS` | report the running helper's build identity, the connection's authenticated caller, and every package id this daemon accepts. A client cannot infer dual-uid support from `BUILDID`, which is an opaque source hash; the bridge must confirm it before it installs and launches a successor that would otherwise have no root channel at all | `HELPERSTATUS 1 BUILD=<hash> CALLER=<ROOT\|LEGACY\|SUCCESSOR> PACKAGES=<id>,<id>` / `ERR` when arguments are supplied |
| `UNINSTALL <pkg>` | remove one of the two known ha-paneld packages, so the identity migration can retire the old one without a root shell. Refuses any other package, and refuses the caller's own | `OK` / `ERR` / `BUSY` while Guard owns package mutation |
| `GRANT <pkg> <capability>` | apply one grant from a fixed table to one of the two known packages: `NOTIFICATIONS`, `MICROPHONE`, `CAMERA` (runtime permissions), `WRITESETTINGS`, `OVERLAY` (app-ops), `BATTERY` (deviceidle whitelist, `cmd` with a `dumpsys` fallback), `ACCESSIBILITY` (append the panel service to the shared secure setting and enable accessibility, idempotently). No free-form permission, app-op or setting name is accepted, and no package outside the two | `OK` / `ERR` |
| `PING` | liveness probe | `OK` |
| anything else | — | `ERR` |

`VERSION` is the stable compatibility bootstrap. `version` is the helper release version and `proto` is the wire protocol version; clients accept the same protocol major and treat later minor versions as additive. Helpers from before this command return the ordinary unknown-command `ERR`, so the app verifies them with `PING` and reports them as reachable but compatibility-unverified. Run `hapaneld-helper --version` to print the same identity without initializing hardware or binding the socket.

`WATCH`/`SUBSCRIBE` instrument physical buttons the Android input pipeline doesn't deliver to a sandboxed app — e.g. the WF1589T power key (grabbed so it no longer sleeps the panel) and the TPA10 orange button (an `EV_SW` switch, not a key). The **app** chooses which validated profile node to watch and whether to grab it; the daemon streams raw events and the app decides what each means. The app first probes `INPUTV2`, requires each `WATCH` acknowledgement before subscribing, and treats an older helper's stream as usable but diagnostically unverified. A current daemon never degrades a requested exclusive grab into a non-exclusive reader, holds a grab only while at least one subscriber owns delivery, and releases it when the last subscriber disconnects. If a node disappears after setup, its reader retries until it can reopen and, where requested, re-establish the grab.

`GPIOV1`/`GPIORESET`/`GPIOWATCH`/`GPIOSUBSCRIBE` form a separate sensor domain, so a proximity reporter can reconnect and reconfigure without interrupting an existing evdev button subscription. The app supplies the GPIO number from its `DeviceProfile`; the daemon derives only the fixed legacy-sysfs `value` and `edge` paths and never exports a GPIO or changes its direction. It holds the `value` descriptor for the watch lifetime, configures `edge=both` when the kernel exposes a recognised edge attribute, waits on `poll(POLLPRI)`, and restores the prior edge setting on reset. When edge configuration is unavailable, it falls back to a fixed 500 ms sample interval on the same descriptor—there is no shell command or process per sample. Edge mode also rechecks every five seconds so a missed vendor-driver notification cannot leave state stale indefinitely. The first `GPIO <n> <0|1>` record after subscribing is the current binary value; later values are emitted only on change. If the held descriptor becomes unreadable, the helper emits one `GPIOUNAVAILABLE <n>` status before its bounded reopen loop; a recovered descriptor emits its current value and restores availability. As with the evdev stream, a hardware/status event can race ahead of the subscription `OK`, so clients accept valid records during that handshake.

`SCREEN OFF` powers the display backlight down at the hardware level (true off) while leaving the device Awake — no keyguard, so it wakes without a PIN. This is why a panel with a device PIN needs the daemon for screen-off: a sandboxed app can only dim Settings brightness (clamped to a minimum on many panels) and `lockNow()` would force the keyguard.

## Safety

- **Peer-uid authentication.** The transport is an abstract-namespace UNIX socket, so the daemon reads the connecting process's credentials (`SO_PEERCRED`) and accepts **only** ha-paneld's current live uid plus root. ADB operators use a root shell; generic Android shell uid 2000 is rejected because Shizuku also runs authorized applications under that identity. The app's uid is resolved live by `stat`-ing `/data/data/io.github.maxlyth.hapaneld`, because it changes on every reinstall. Every other local app is rejected and the connection closed before a single command runs. (The earlier `127.0.0.1:8889` TCP listener had no auth — any app with `INTERNET` could `REBOOT`/`SCREENCAP` it.)
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
| `sysctl.c` | density / governor / reload / start / reboot / screencap privileged operations |
| `companion.c` | descriptor-anchored, bounded HA Companion backup/restore transaction |
| `perf.c` | `PERFDUMP` `/proc` snapshot |
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

## Provision (per panel, root/adb)

Install or upgrade the daemon on every rooted supported panel. Sandbox-walled panels need it for their privileged controls, while direct-`su` panels also use the current helper for the descriptor-confined Companion backup/restore protocol. The app and helper therefore form one compatibility unit even when older releases could route some controls through `su`.

```bash
./helper/build.sh
./helper/install-daemon.sh <ip:5555>
```

Do not execute the adb/shell-owned staging copy from `/data/local/tmp` as root. The installer hashes it, copies it to a transaction-unique root-owned candidate, and installs the one canonical live binary as `root:root` mode `0700` at `/data/local/hapaneld-helper`. After stopping the prior daemon it revalidates the complete live topology against its recorded snapshot, then asks the staged candidate for the exact `--replacement-safe` verdict before the live rename. Post-snapshot topology drift is a fail-closed hold: no live file is overwritten and the stale snapshots are never rolled back over the other writer. Native `GUARD_ARMED` is disambiguated through the fixed APK-coupled R1 custody records: present R1 custody retains the prepared journal without rollback, while ordinary armed Guard state with no R1 custody immediately restores and restarts the exact prior helper topology. After a safe swap, the installer launches one supervisor and requires the exact Companion capabilities, autonomous supervised Guard DB capabilities, empty Guard DB status, and build identity before committing.

The standalone transaction journal is version 3 and records the canonical binary, the selected boot registration, every historical helper binary/registration, and authenticated recovery snapshots. Its prepared/mutating/target phases make an interrupted migration recoverable without treating unknown live bytes as this installer's output. A stale v3 rollback checks the same five fixed native R1 custody paths before publishing rollback intent and again before every live restore; custody retains the journal and leaves all helper paths untouched. Existing version 1 and 2 standalone journals retain their original, explicitly version-dispatched topology semantics; because version 1 did not record its target-registration hash, post-swap v1 recovery admits only the exact two registration byte streams that v1 ever published. Across every journal version, an absent record means neither a directory entry nor a broken symlink. Every version publishes rollback bytes through an authenticated same-directory temporary whose hash, ownership, and mode are verified and synced before atomic rename and parent-directory durability, so a power cut during copy leaves either the previous live file or the complete recovery file, not a partial authoritative binary.

## Boot persistence (init service)

The daemon must (re)start in a root domain after every reboot, and the app cannot bootstrap that service itself. Every supported route executes `/data/local/hapaneld-helper --supervise`; system, vendor, and systemless storage contain boot registrations only. `helper/install-daemon.sh` chooses the first verified registration route:

- A writable-system/userdebug panel receives the init registration in `/system/etc/init`.
- Otherwise, a writable vendor init directory receives the same registration in `/vendor/etc/init`.
- Otherwise, a read-only-system panel is accepted only when a supported Magisk, KernelSU, or APatch service runner is detected; it receives a `service.d` launcher under `/data/adb/service.d`.

The transaction snapshots and removes historical `/system/bin/hapaneld-helper`, `/data/adb/hapaneld/hapaneld-helper`, old init/service.d registrations, and the former `hapaneld-ledd` layout. They are restored byte-for-byte if migration fails. If no registration route is verified, installation stops without replacing or stopping the existing helper. Reboot the panel when convenient after installation to confirm boot persistence.
