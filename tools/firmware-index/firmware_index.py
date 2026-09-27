#!/usr/bin/env python3
"""NSPanel Pro firmware OTA index — generator and URL availability monitor.

The firmware download links published in GitHub Discussion #7 are point-in-time
hits against the CoolKit OTA CDN (the bucket cannot be listed, so each URL is an
exact-filename probe). This script keeps that page honest in two ways:

  probe     range-GET every URL, record up/down into a rolling 7-day history file
  render    emit the Discussion markdown body — a rolling window of recent
            upgrade targets, with each row's Wayback capture date
  archive   generate the exhaustive in-repo index page (every indexed object)
  discover  search the CDN for releases newer than the index

`probe` only re-checks URLs that are already indexed, so a clean probe run says
nothing about whether new firmware shipped. `discover` answers that separate
question: the bucket has no manifest, so it guesses forward from the index —
the next few per-build serials crossed with plausible next version numbers —
and confirms each hit by ZIP magic. It validates itself against known-good
objects first and aborts rather than reporting a negative it cannot stand
behind. Full ROMs are deliberately out of scope: their filenames embed a build
date that cannot be guessed.

Both subcommands derive their URLs from the same builders, so the squares line
up exactly with the table rows. The CI workflow runs `probe` then `render` on a
schedule and pushes the result to the Discussion; the history lives on a
dedicated data branch so the main history stays clean.

Data files (`fw-120p.dat`, `fw-86p.dat`) are the source of truth. Format:

    channel   <cdn-channel>
    diffsuffix <suffix-or-empty>          # 120P uses V228, 86P is bare
    apkfmt    <apk-filename-prefix>       # 86P "app", 120P "228V"
    full|<ver>|<idx>|<filename>|<bytes>
    diff|<target>|<idx>|<from>:<bytes>|<from>:<bytes>...
    apk|<ver>|<idx>|<bytes>
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HOST = "https://global-otadl2bsy.coolkit.cc"
HERE = os.path.dirname(os.path.abspath(__file__))

# (display name, sub-title, data file) — render order is top to bottom.
DEVICES = [
    ("120P", "750×1334, rk3326-S", "fw-120p.dat"),
    ("86P", "480×480, PX30", "fw-86p.dat"),
]

WINDOW_HOURS = 24 * 7    # 7-day rolling window
MAX_POINTS = 7           # one dot per day
PROBE_TIMEOUT = 20
PROBE_WORKERS = 16
GITHUB_BODY_LIMIT = 65536

# The Discussion body is capped by GitHub, so it carries a rolling window of the
# most recent diff targets plus APKs from the flashing checkpoint onward. The
# complete history is generated into ARCHIVE_DOC instead of being dropped.
RENDER_TARGET_WINDOW = 6
APK_FLOOR = (4, 0, 12)
ARCHIVE_DOC = "docs/hardware/nspanel-pro-firmware-archive.md"
ARCHIVE_DOC_URL = (
    "https://github.com/panel-assistant/android/blob/main/docs/hardware/nspanel-pro-firmware-archive.md"
)


# --------------------------------------------------------------------------- #
# data parsing + URL builders (shared by render and probe)
# --------------------------------------------------------------------------- #

def human(b):
    return f"{b} ({b / 1048576:.1f} MB)"


def vkey(v):
    return tuple(int(x) for x in v.split("."))


def parse(path):
    d = {"fulls": [], "diffs": [], "apks": [], "suffix": "", "apkfmt": "", "channel": ""}
    with open(path) as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("channel "):
                d["channel"] = line.split(" ", 1)[1].strip()
            elif line.startswith("diffsuffix"):
                parts = line.split(" ", 1)
                d["suffix"] = parts[1].strip() if len(parts) > 1 else ""
            elif line.startswith("apkfmt "):
                d["apkfmt"] = line.split(" ", 1)[1].strip()
            elif line.startswith("full|"):
                _, ver, idx, fn, sz = line.split("|")
                d["fulls"].append((ver, idx, fn, int(sz)))
            elif line.startswith("diff|"):
                p = line.split("|")
                to, idx = p[1], p[2]
                for fs in p[3:]:
                    frm, sz = fs.split(":")
                    d["diffs"].append((to, frm, idx, int(sz)))
            elif line.startswith("apk|"):
                _, ver, idx, sz = line.split("|")
                d["apks"].append((ver, idx, int(sz)))
    return d


def full_url(d, idx, fn):
    return f"{HOST}/{d['channel']}/rom/{idx}/{fn}"


def diff_url(d, idx, frm, to):
    return f"{HOST}/{d['channel']}/rom-diff/{idx}/CK_{frm}_{to}{d['suffix']}-diff.zip"


def apk_url(d, idx, ver):
    return f"{HOST}/{d['channel']}/apk/{idx}/{d['apkfmt']}{ver}.apk"


def all_urls(devices):
    """Every downloadable URL across all devices (deduped, stable order)."""
    return list(all_url_sizes(devices))


def all_url_sizes(devices):
    """Every downloadable URL mapped to its expected total byte size."""
    urls = {}
    for d in devices:
        for ver, idx, fn, sz in d["fulls"]:
            urls[full_url(d, idx, fn)] = sz
        for to, frm, idx, sz in d["diffs"]:
            urls[diff_url(d, idx, frm, to)] = sz
        for ver, idx, sz in d["apks"]:
            urls[apk_url(d, idx, ver)] = sz
    return urls


def load_devices():
    return [parse(os.path.join(HERE, fn)) for _, _, fn in DEVICES]


# --------------------------------------------------------------------------- #
# history
# --------------------------------------------------------------------------- #

def load_history(path):
    try:
        with open(path) as fh:
            h = json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        h = {}
    h.setdefault("samples", [])
    return h


def save_history(path, h):
    with open(path, "w") as fh:
        json.dump(h, fh, separators=(",", ":"))


def trim(h, now):
    cutoff = now - WINDOW_HOURS * 3600 - 1800   # 30 min grace for scheduler jitter
    latest_by_day = {}
    for sample in h["samples"]:
        timestamp = sample.get("t", 0)
        if timestamp < cutoff:
            continue
        day = timestamp // 86400
        previous = latest_by_day.get(day)
        if previous is None or timestamp >= previous.get("t", 0):
            latest_by_day[day] = sample
    h["samples"] = [latest_by_day[day] for day in sorted(latest_by_day)][-MAX_POINTS:]



# --------------------------------------------------------------------------- #
# probe
# --------------------------------------------------------------------------- #

def response_total_size(response):
    """Read the complete object size from a range or full response."""
    if response.status == 206:
        content_range = response.headers.get("Content-Range", "")
        try:
            return int(content_range.rsplit("/", 1)[1])
        except (IndexError, ValueError):
            return None
    if response.status == 200:
        try:
            return int(response.headers.get("Content-Length", ""))
        except ValueError:
            return None
    return None


def probe_one(url, expected_size):
    req = urllib.request.Request(url, method="GET")
    req.add_header("Range", "bytes=0-0")
    req.add_header("User-Agent", "ha-paneld-firmware-monitor")
    try:
        t0 = time.time()
        with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT) as r:
            ms = int((time.time() - t0) * 1000)
            return response_total_size(r) == expected_size, ms
    except urllib.error.HTTPError:
        return False, None      # 403 (missing) → down
    except Exception:
        return False, None


# --------------------------------------------------------------------------- #
# discover — find versions that are on the CDN but not yet in the .dat
# --------------------------------------------------------------------------- #

ZIP_MAGIC = b"PK\x03\x04"
DISCOVER_INDEX_WINDOW = 32
DISCOVER_MINOR_WINDOW = 4
DISCOVER_PATCH_WINDOW = 8
# Waits before each retry of a throttled (429), unavailable (5xx) or timed-out
# probe. Sixteen parallel workers can briefly trip the CDN's rate limit.
DISCOVER_RETRY_DELAYS = (5, 15, 45)
TRANSIENT_HTTP_CODES = {429, 500, 502, 503, 504}


def discover_one(url):
    """Return the object's total size if `url` is a real ZIP, else None.

    The CDN answers 403 for anything that does not exist, so a 206 with ZIP
    magic is the only positive signal. Reading the first four bytes rather
    than one costs nothing and rejects a non-ZIP body that still ranges.
    """
    req = urllib.request.Request(url, method="GET")
    req.add_header("Range", "bytes=0-3")
    req.add_header("User-Agent", "ha-paneld-firmware-monitor")
    for delay in DISCOVER_RETRY_DELAYS + (None,):
        try:
            with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT) as r:
                total = response_total_size(r)
                if total is None or r.read(4) != ZIP_MAGIC:
                    return None
                return total
        except urllib.error.HTTPError as exc:
            if exc.code == 403:
                return None         # This CDN's explicit missing-object response.
            if exc.code not in TRANSIENT_HTTP_CODES or delay is None:
                raise
        except (TimeoutError, urllib.error.URLError):
            if delay is None:
                raise
        # A throttled or briefly unavailable CDN is not an answer. Wait and ask
        # again; only a persistent failure fails the run, never "not found".
        time.sleep(delay)


def candidate_versions(
    known,
    minor_window=DISCOVER_MINOR_WINDOW,
    patch_window=DISCOVER_PATCH_WINDOW,
):
    """Plausible successors to the highest known version.

    Sonoff skips freely: 4.0.x jumped straight to 4.4.0, so a patch+1 guess
    alone would miss a release permanently. Cover the next few patches, the
    next few minors at .0/.1, and the next major.
    """
    major, minor, patch = vkey(known)
    out = []
    for p in range(patch + 1, patch + 1 + patch_window):
        out.append(f"{major}.{minor}.{p}")
    for m in range(minor + 1, minor + 1 + minor_window):
        for p in range(patch_window):
            out.append(f"{major}.{m}.{p}")
    out.append(f"{major + 1}.0.0")
    return out


def newest_version(entries):
    """Highest version across (ver, ...) tuples, by numeric key."""
    return max((e[0] for e in entries), key=vkey)


def rom_states(d):
    """Every version a panel's ROM can actually be sitting on.

    These are the plausible sources of an inbound diff: full ROMs, previous
    diff targets, and versions already used as a diff source. The newest
    release must be included even though it has never been a source yet —
    it is the single most likely upgrade origin, and deriving the set from
    observed sources alone would silently exclude it.
    """
    states = {frm for _t, frm, _i, _s in d["diffs"]}
    states |= {to for to, _f, _i, _s in d["diffs"]}
    states |= {ver for ver, _idx, _fn, _sz in d["fulls"]}
    return states


def discover_device(d, index_window, minor_window):
    """Probe one channel for unindexed APKs and their inbound ROM diffs.

    Returns (findings, searched) where `searched` records the exact window
    that was covered, so a miss is diagnosable rather than silent.
    """
    known_apk = {v for v, _idx, _sz in d["apks"]}
    known_versions = known_apk | {to for to, _f, _i, _s in d["diffs"]}
    newest = max(known_versions, key=vkey)
    versions = [v for v in candidate_versions(newest, minor_window) if v not in known_versions]

    max_apk_idx = max(int(idx) for _v, idx, _s in d["apks"])
    apk_indices = list(range(max_apk_idx + 1, max_apk_idx + 1 + index_window))

    apk_urls = {}
    for idx in apk_indices:
        for ver in versions:
            apk_urls[apk_url(d, idx, ver)] = (idx, ver)

    with ThreadPoolExecutor(max_workers=PROBE_WORKERS) as ex:
        sizes = list(ex.map(discover_one, apk_urls))

    findings = []
    for (url, (idx, ver)), size in zip(apk_urls.items(), sizes):
        if size is not None:
            findings.append({"kind": "apk", "version": ver, "index": idx, "bytes": size, "url": url})

    searched = {
        "channel": d["channel"],
        "apk_indices": [apk_indices[0], apk_indices[-1]],
        "versions": versions,
        "diff_indices": None,
    }

    # A release can be ROM-diff-only, so its diff must not depend on finding an
    # APK for the same version first. Probe every candidate target directly.
    max_diff_idx = max(int(idx) for _t, _f, idx, _s in d["diffs"])
    diff_indices = list(range(max_diff_idx + 1, max_diff_idx + 1 + index_window))
    froms = sorted(rom_states(d), key=vkey)
    diff_urls = {}
    for idx in diff_indices:
        for ver in versions:
            for frm in froms:
                diff_urls[diff_url(d, idx, frm, ver)] = (idx, ver, frm)
    with ThreadPoolExecutor(max_workers=PROBE_WORKERS) as ex:
        dsizes = list(ex.map(discover_one, diff_urls))
    for (url, (idx, ver, frm)), size in zip(diff_urls.items(), dsizes):
        if size is not None:
            findings.append({"kind": "diff", "version": ver, "index": idx,
                             "from": frm, "bytes": size, "url": url})
    searched["diff_indices"] = [diff_indices[0], diff_indices[-1]]
    searched["diff_froms"] = froms

    return findings, searched


def validate_harness(devices):
    """Prove the prober can see objects that are known to exist.

    Without this a broken prober, a DNS failure or a CDN change would report
    'nothing new' forever. Every device must confirm, and the check uses the
    newest indexed APK because that is the object most like what discovery is
    looking for.
    """
    ok = True
    for d in devices:
        ver, idx, expected = max(d["apks"], key=lambda a: vkey(a[0]))
        url = apk_url(d, idx, ver)
        size = discover_one(url)
        if size == expected:
            print(f"harness ok: {d['channel']} {ver} ({expected} bytes)")
        else:
            print(f"harness FAILED: {d['channel']} {ver} expected {expected}, got {size} — {url}")
            ok = False
    return ok


def format_findings(findings):
    """Render findings as `.dat` lines, one per version and kind.

    Diffs are grouped onto a single line per target, matching the existing
    format, with sources in ascending version order.
    """
    lines = []
    apks = sorted((f for f in findings if f["kind"] == "apk"), key=lambda f: vkey(f["version"]))
    for f in apks:
        lines.append(("apk", f"apk|{f['version']}|{f['index']}|{f['bytes']}"))

    diffs = [f for f in findings if f["kind"] == "diff"]
    by_target = {}
    for f in diffs:
        by_target.setdefault((f["version"], f["index"]), []).append(f)
    for (ver, idx) in sorted(by_target, key=lambda k: vkey(k[0])):
        group = sorted(by_target[(ver, idx)], key=lambda f: vkey(f["from"]))
        joined = "|".join(f"{f['from']}:{f['bytes']}" for f in group)
        lines.append(("diff", f"diff|{ver}|{idx}|{joined}"))
    return lines


def apply_findings(path, findings):
    """Append `.dat` lines after the last existing entry of the same kind.

    Appending inside the existing group keeps the file readable; the rendered
    tables sort by version regardless, so placement never changes output.
    """
    new_lines = format_findings(findings)
    if not new_lines:
        return 0
    lines = open(path).read().rstrip("\n").split("\n")
    for kind, text in new_lines:
        if text in lines:
            continue
        last = max((i for i, l in enumerate(lines) if l.startswith(f"{kind}|")), default=len(lines) - 1)
        lines.insert(last + 1, text)
    open(path, "w").write("\n".join(lines) + "\n")
    return len(new_lines)


def set_github_output(**kv):
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a") as fh:
        for k, v in kv.items():
            fh.write(f"{k}={v}\n")


def cmd_discover(args):
    devices = load_devices()

    if not validate_harness(devices):
        print("discovery aborted: the prober could not see known-good objects, "
              "so a negative result would be meaningless.")
        set_github_output(harness_ok="false", found="false")
        return 2

    all_findings = []
    per_device = []
    for d in devices:
        findings, searched = discover_device(d, args.index_window, args.minor_window)
        per_device.append((d, findings))
        lo, hi = searched["apk_indices"]
        print(f"searched {searched['channel']}: apk indices {lo}-{hi} × "
              f"{len(searched['versions'])} candidate versions "
              f"({', '.join(searched['versions'])})")
        if searched["diff_indices"]:
            dlo, dhi = searched["diff_indices"]
            print(f"searched {searched['channel']}: rom-diff indices {dlo}-{dhi} × "
                  f"from {', '.join(searched['diff_froms'])}")
        all_findings.extend(findings)

    if not all_findings:
        print("no unindexed firmware found in the searched window")
        set_github_output(harness_ok="true", found="false")
        return 0

    print(f"\nFOUND {len(all_findings)} unindexed object(s):")
    for f in sorted(all_findings, key=lambda x: (vkey(x["version"]), x["kind"], x["url"])):
        label = f"{f['kind']} {f['version']}"
        if f["kind"] == "diff":
            label += f" ← {f['from']}"
        print(f"  {label}  idx={f['index']}  {human(f['bytes'])}  {f['url']}")

    versions = sorted({f["version"] for f in all_findings}, key=vkey)
    set_github_output(harness_ok="true", found="true", versions=",".join(versions))

    if args.apply:
        written = 0
        for (d, findings), (_name, _sub, fn) in zip(per_device, DEVICES):
            if findings:
                written += apply_findings(os.path.join(HERE, fn), findings)
        print(f"\nappended {written} line(s) to the index — re-run `probe` to validate "
              f"every new URL and byte size against the CDN")

    if args.json:
        with open(args.json, "w") as fh:
            json.dump(all_findings, fh, indent=2, sort_keys=True)
        print(f"\nwrote {args.json}")
    return 0


def cmd_probe(args):
    devices = load_devices()
    url_sizes = all_url_sizes(devices)
    entries = list(url_sizes.items())
    with ThreadPoolExecutor(max_workers=PROBE_WORKERS) as ex:
        outcomes = list(ex.map(lambda item: probe_one(*item), entries))
    results = {u: (1 if up else 0) for (u, _size), (up, _ms) in zip(entries, outcomes)}

    h = load_history(args.history)
    now = int(time.time())
    h["samples"].append({"t": now, "r": results})
    trim(h, now)
    save_history(args.history, h)

    up = sum(results.values())
    down = len(entries) - up
    print(f"probed {len(entries)} URLs at {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(now))}: "
          f"{up} up, {down} down ({len(h['samples'])} samples retained)")
    return 1 if down > 0 else 0


# --------------------------------------------------------------------------- #
# render
# --------------------------------------------------------------------------- #

INTRO = """# NSPanel Pro firmware — OTA download index (community-maintained)

Direct, clickable, **live-verified** download links for Sonoff NSPanel Pro OTA firmware — both the **86P** (480×480, PX30) and the **120P** (750×1334, rk3326-S). Every link was confirmed live by a range response whose total object size exactly matches the index. The S3 bucket can't be listed, so these are exact-filename hits, not a directory listing.

Flashing how-to (fully remote, no recovery-mode ADB): see [the repo's firmware guide](https://github.com/panel-assistant/android/blob/main/docs/hardware/nspanel-pro-firmware.md). `/data` (apps + settings) is preserved across an OTA.

> [!TIP]
> **How to read this index:** the per-model tables below are generated from the index data and cover the recent upgrade targets — GitHub caps the size of this post, so the authority on everything indexed is the [complete index](https://github.com/panel-assistant/android/blob/main/docs/hardware/nspanel-pro-firmware-archive.md), which lists every object including the older releases omitted here. This page deliberately does not name a “latest” version in prose, because prose goes stale the moment a release lands. `4.0.12` is the full-ROM checkpoint, and it is itself a full ROM. A release indexed after it arrives either as one or more diffs — each patching from a specific earlier version, and a release commonly has several inbound diffs to choose from — or as an APK-only update carrying no ROM at all, and a release can be a ROM on one model and APK-only on the other — so an upgrade is not always a single hop, and the per-model tables show which form each release takes. The project has hardware-verified the flashing procedure only through **4.4.0**; anything newer is CDN-verified only, so try it on one recoverable panel first.

> [!NOTE]
> **Help wanted:** this index lists what has been *found*, not everything that exists. The bucket cannot be listed and the per-build index cannot be derived from a version number, so a failed probe rules out one filename at one index and never a build — absence here is not proof of non-existence. Spot a build or a link that is missing? Reply with the URL plus a range probe (`curl -s -r 0-0 -D - -o /dev/null "<url>"`) showing `206` and the total size, and it gets added. This thread is the living list.
"""

CHANGES = """## Which version should an HA panel run? (newer isn't always better)

Vendor release notes are written for eWeLink / Zigbee-hub users, not for people running the panel purely as a Home Assistant dashboard. Below is the same history re-read through an HA-panel lens, combining the official notes where they exist, community reports from the release and user feedback threads (**[c]**), and our own fleet testing (**[f]**, ha-paneld, June 2026). Several releases trade dashboard convenience for hub features an HA-only panel doesn't need.

> [!TIP]
> **Picking a build for an HA-only panel:**
> - **Lean kiosk:** a late **3.x** build is lighter, keeps the **Web Shortcut** web-app (point straight at a dashboard URL, no sideload), and ships **no Termux**.
> - **Modern app model:** if you want F-Droid / the HA Companion app or 4.x MQTT features, stop at **4.0.12** — the conservative full-ROM checkpoint — then turn on **Settings → About → Block Firmware Updates** to pin it.
> - **Latest / bleeding edge:** take the newest rows of the table below and of the per-model download tables. Nothing past **4.4.0** is live-flash verified by this project, and recent releases carry unverified community reports — restart loops on 4.5.1 / 4.5.2, sub-device connectivity trouble on 4.7.0. Try any of them on one recoverable panel first; **4.0.12** remains the conservative full-ROM checkpoint to pin for maximum stability.
> - **Always:** sideload a current **System WebView** — the stock one is too old to render the HA dashboard (see the repo's [docs/hardware](https://github.com/panel-assistant/android/tree/main/docs/hardware) notes).

| Version | What it changes for HA-panel use | Watch out for |
| --- | --- | --- |
| **≤ 3.x** | Web Shortcut web-app (dashboard URL, no sideload); lighter UI; no Termux. | Old stock WebView — sideload a current one **[f]**. |
| **4.0.0** | Faster UI; adds F-Droid + direct app install (HA Companion). | **"Web Shortcut" web-app phased out** — you install an app now instead of pointing at a URL. (4.x also begins bundling Termux — see gotchas below.) |
| [**4.0.7**](https://forum.ewelink.cc/t/nspanel-pro-v4-0-7-optimized-version-released/205842) | Roller-shutter support; Zigbee 3rd-party control + T&H display fixes. | **[c]** Random reboots / power-on failures; screensaver off-centre and **disabled while the HA Companion app runs**. |
| [**4.1.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-1-0-release-new-features-enhancements/206443) | Export Zigbee devices to HA over MQTT; Matter Bridge; Matter SDK 1.4. | **[c]** MQTT published **without `retain`** → HA entities go blank after a broker/HA restart until the next state change. Needs eWeLink app ≥ 5.21.0. |
| [**4.2.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-2-0-officially-released-new-features-enhancements/206900) | App Switcher + set a **"Home App" (kiosk launcher)**; control accessibility-button visibility; auto-start on boot; more Zigbee devices. | **[c]** Freezing, shell errors, extra restarts, slower Zigbee rebuild; MQTT still publishes **unfiltered Zigbee DP values** → entity/log spam. |
| [**4.3.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-3-0-officially-released-new-features-enhancements/207281) | **Panel exposes its own capabilities as HA entities over MQTT** (speaker, screen on/off, brightness, ambient light, security status, IP); 0.1 °C heating; custom ringtones. | **[c]** Tap-to-toggle vs tap-for-details is fiddly; heating over-cycles on small thresholds; schedules reset on config change. |
| [**4.4.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-4-0-officially-released-new-features-enhancements/207640) | Native **wake-on-proximity**, **mic recording** and **speaker playback** over MQTT (play a file or URL from HA); Zigbee NCP 8.x. | **[c]** Widespread **persistent ticking/tapping sound** from the proximity feature (disable Touch Sounds or the feature); heating not shutting off at setpoint. |
| [**4.5.1 / 4.5.2**](https://forum.ewelink.cc/t/nspanel-pro-firmware-4-5-2-has-been-released/208527) | Zigbee water-valve + PIR motion support; general fixes. 4.5.2 is an APK-only update over the 4.5.1 ROM. | **[c]** **Frequent restarts (~10–60 min) on both 120P and 86P**; app crashes viewing logs; Matter Bridge missing on some units. |
| [**4.5.3**](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) | Matter auto-discovery and screen-management optimizations; ROM diffs on 120P but APK-only on 86P. | No 4.5.3-specific restart-loop evidence found; followed by 4.6.0. |
| [**4.6.0**](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) | **Local Web Portal** — the panel is now reachable on the LAN at `http://nspanelpro.local` (or its IP) for setup + management: add Zigbee / eWeLink sub-devices, arm/disarm Smart Security, configure the **MQTT broker to sync Zigbee into Home Assistant**, authorize HA + Matter Bridge pairing, upload custom ringtones/screensavers. | Released **2026-06-30** — not yet live-flash verified here. Distributed as diffs off 4.0.12 / 4.4.0 / 4.5.1, with no new full ROM. |
| **4.6.2** | No release notes found; indexed as an APK-only update with no ROM diff on either channel. | Located by probing the CDN — treat the absence of notes as unknown-content, not as a minor release. |
| **4.7.0** | Covers Gen1 and the Gen2 panels; users report added Basic gen-5 relay (BASIC-1GS) support. **No official release notes were found** — this is drawn from the [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789), which is a discussion thread rather than a release announcement or a vendor changelog. | Released ~**2026-07-16**; not live-flash verified here. **[c]** The thread carries reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing. This project has not reproduced or quantified them; treat them as unverified user reports rather than a known regression. |
| [**4.8.0**](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240) | **No release announcement or changelog found** — located by probing the CDN. An eWeLink staff post scheduled it for August 2026 and confirmed an option to auto-update the panel through the eWeLink app. | Contents and stability are otherwise unassessed; no feedback thread was found. The auto-update option's default is unknown, so check it before relying on a pinned version. Not live-flash verified here. |

**Cross-cutting gotchas (firmware-independent) [f]:**

- **WebView is the #1 blocker.** The stock System WebView is too old to render the HA dashboard (blank/broken). Sideload a current `com.android.webview` (no root needed) — verified on PX30 (138), TPA10/Cromite (147), rk3576 (150).
- **Proximity granularity is per-model, not per-version.** The **86P reports graded (continuous)** proximity; the **120P reports only binary near/far** — it tracks the on-device version, not the 4.x marketing number. Affects how finely you can tune wake-on-approach / presence.
- **4.x bundles Termux** (`/system/app`, plus Termux:Boot) — the runtime some community Zigbee2MQTT-on-panel bridges use. An HA panel where the HA server already owns Zigbee doesn't need it; it's disableable without root.
- **The vendor's own MQTT exposure (4.1.0+) is unfiltered** — it can flood HA with raw Zigbee DP entities. If you just want the panel as a dashboard, leave the eWeLink↔HA MQTT bridge off and drive the panel from HA directly.

*Legend: unmarked = official release notes · **[c]** community-reported in the release or user feedback thread · **[f]** ha-paneld fleet testing, June 2026.*
"""

RELEASE_NOTES = """## Release notes

Notes are published per "NSPanel Pro" — not split by 86P vs 120P.

| Version(s) | Where the notes live |
| --- | --- |
| 1.x – 3.8.x | [SONOFF "NSPanel Pro Version Update Information and FAQ"](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) — dated changelog |
| 4.0.0 | [SONOFF V4.0.0 update blog](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install) (F-Droid + HA app install) |
| 4.0.7 | [eWeLink — V4.0.7 optimized version](https://forum.ewelink.cc/t/nspanel-pro-v4-0-7-optimized-version-released/205842) |
| 4.1.0 / 4.2.0 / 4.3.0 | eWeLink per-version threads ([4.1.0](https://forum.ewelink.cc/t/nspanel-pro-v4-1-0-release-new-features-enhancements/206443) · [4.2.0](https://forum.ewelink.cc/t/nspanel-pro-v4-2-0-officially-released-new-features-enhancements/206900) · [4.3.0](https://forum.ewelink.cc/t/nspanel-pro-v4-3-0-officially-released-new-features-enhancements/207281)) |
| 4.4.0 | [eWeLink — V4.4.0 officially released](https://forum.ewelink.cc/t/nspanel-pro-v4-4-0-officially-released-new-features-enhancements/207640) (native wake-on-proximity, mic record + speaker playback over MQTT) |
| 4.5.1–4.5.3 | [eWeLink — firmware 4.5.2 released](https://forum.ewelink.cc/t/nspanel-pro-firmware-4-5-2-has-been-released/208527) · [eWeLink rolling release notes](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) |
| 4.6.0 | [SONOFF "NSPanel Pro Version Update Information and FAQ"](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) (Local Web Portal) |
| 4.6.2 | **No release notes found** — this project found neither a vendor changelog entry nor a community thread for it. It was located by probing the CDN, so this index records the files only. |
| 4.7.0 | **No vendor changelog entry found.** Community discussion only: [eWeLink 4.7.0 user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread of user reports, not a release announcement and not official release notes. |
| 4.8.0 | **No release announcement or changelog found.** The only vendor statement found was an [eWeLink staff roadmap post](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240) scheduling it for August 2026 and naming an eWeLink-app auto-update option. The files were located by probing the CDN; contents and stability remain unassessed. |
| 3.9.4 / 4.0.10 / 4.0.12 | No official notes published (bug threads only) |
| All 4.x (rolling) | [eWeLink "[Rolling] NSPanel Pro Firmware Updates"](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) |
"""

SCHEME = """## How the URLs are built (to verify these or find new ones)

Host: `global-otadl2bsy.coolkit.cc`. Three path forms, per channel:

- Full ROM: `…/<channel>/rom/<idx>/<file>`
- Incremental diff: `…/<channel>/rom-diff/<idx>/CK_<from>_<to>[suffix]-diff.zip`
- eWeLink app APK: `…/<channel>/apk/<idx>/<file>`

Channels and conventions differ by model:

| | 86P | 120P |
| --- | --- | --- |
| Channel | `nspanel-pro` | `nspanel-pro-ver120` |
| Diff suffix | *(none)* | `V228` |
| APK filename | `app<ver>.apk` | `228V<ver>.apk` |

`<idx>` is a per-build serial (the `rom-diff` index is per **target** version). Probing: a missing file returns `403`; a real file answers a range request with `206` + a `Content-Range` total. Check one with `curl -s -r 0-0 -D - -o /dev/null "<url>"`.

> [!CAUTION]
> The per-model tables above show the recent upgrade targets; the [complete index](https://github.com/panel-assistant/android/blob/main/docs/hardware/nspanel-pro-firmware-archive.md) is the authority on every full ROM, diff and APK indexed. Past the `4.0.12` full-ROM checkpoint a release arrives either as one or more diffs — each patching from a specific earlier version, so there is usually more than one way in — or as an APK-only update carrying no ROM diff at all, and a release can be a ROM on one model and APK-only on the other. Read the route off the tables rather than assuming a direct hop.
"""

FOOTER = """---

*This page is generated and refreshed automatically — do not hand-edit the body. The link list lives in [`tools/firmware-index/`](https://github.com/panel-assistant/android/tree/main/tools/firmware-index), every URL is re-verified daily by [a GitHub Action](https://github.com/panel-assistant/android/blob/main/.github/workflows/firmware-url-monitor.yml), and the Archived dates come from the weekly [Wayback archiver](https://github.com/panel-assistant/android/blob/main/.github/workflows/firmware-wayback.yml). To add or correct a link, edit the data files (or reply below) — don't edit this post.*
"""


def archive_banner(wb, devices):
    """State archival coverage over everything indexed, not just what is rendered."""
    urls = all_url_sizes(devices)
    covered = sum(1 for u in urls if wb.get(u))
    if not wb:
        return ("> [!NOTE]\n> **Archived copies:** the Wayback state was not available when this page "
                "was generated, so the **Archived** column is blank. It is not a claim that no copy exists.\n")
    gaps = len(urls) - covered
    tail = (f" **{gaps}** indexed file(s) have no capture yet."
            if gaps else " Every indexed file has a capture.")
    return ("> [!NOTE]\n> **Archived copies** — the **Archived** column gives the date each file was "
            "captured by the [Wayback Machine](https://web.archive.org/), which is what protects you if "
            "CoolKit ever withdraws a build. Reach any capture at "
            "`https://web.archive.org/web/<date>/<the download URL>`, or browse "
            "[every capture of the CDN](https://web.archive.org/web/*/global-otadl2bsy.coolkit.cc/*). "
            f"**{covered}/{len(urls)}** indexed files are archived.{tail}\n")


def archived_cell(url, wb):
    """Capture date, or an explicit gap marker — never a silent blank."""
    stamp = wb.get(url)
    if not stamp:
        return "—"
    return f"{stamp[0:4]}-{stamp[4:6]}-{stamp[6:8]}"


def wayback_url(url, stamp):
    return f"https://web.archive.org/web/{stamp}/{url}"


def fulls_table(d, wb):
    """Full ROMs carry a real archive link, not just a date.

    There are few of them and they are the irreplaceable artifacts — 4.0.12 is
    the checkpoint the whole upgrade path depends on — so the extra bytes buy
    a one-click recovery route.
    """
    out = ["| Version | Size | Download | Archived |", "| --- | --- | --- | --- |"]
    for ver, idx, fn, sz in sorted(d["fulls"], key=lambda x: vkey(x[0]), reverse=True):
        url = full_url(d, idx, fn)
        stamp = wb.get(url)
        cell = f"[{archived_cell(url, wb)}]({wayback_url(url, stamp)})" if stamp else "—"
        out.append(f"| **{ver}** | {human(sz)} | [{fn}]({url}) | {cell} |")
    return "\n".join(out)


def diffs_table(d, wb, diffs=None):
    rows = d["diffs"] if diffs is None else diffs
    out = ["| To (target) | From | Size | Download | Archived |", "| --- | --- | --- | --- | --- |"]
    for to, frm, idx, sz in sorted(rows, key=lambda x: (vkey(x[0]), vkey(x[1])), reverse=True):
        url = diff_url(d, idx, frm, to)
        fn = f"CK_{frm}_{to}{d['suffix']}-diff.zip"
        out.append(f"| **{to}** | {frm} | {human(sz)} | [{fn}]({url}) | {archived_cell(url, wb)} |")
    return "\n".join(out)


def apks_table(d, wb, apks=None):
    rows = d["apks"] if apks is None else apks
    out = ["| Version | Size | Download | Archived |", "| --- | --- | --- | --- |"]
    for ver, idx, sz in sorted(rows, key=lambda x: vkey(x[0]), reverse=True):
        url = apk_url(d, idx, ver)
        fn = f"{d['apkfmt']}{ver}.apk"
        out.append(f"| {ver} | {human(sz)} | [{fn}]({url}) | {archived_cell(url, wb)} |")
    return "\n".join(out)


def recent_targets(devices, window):
    """The most recent `window` diff targets across both channels.

    A rolling window rather than a version floor, because a floor still grows
    without bound: every release adds ~11 diff rows, which is what pushed the
    body to 88% of GitHub's limit.
    """
    targets = sorted({t for d in devices for t, _f, _i, _s in d["diffs"]}, key=vkey)
    return set(targets[-window:]) if window else set(targets)


def device_block(name, sub, d, wb, targets, floor):
    """One channel's tables, narrowed to what the documented route can use.

    Diffs onto older targets and APKs below the checkpoint are omitted here and
    kept in full in the generated archive page — the upgrade rule sends anything
    below the checkpoint through the full ROM, so those rows describe hops this
    project tells nobody to take.
    """
    diffs = [x for x in d["diffs"] if x[0] in targets]
    apks = [x for x in d["apks"] if vkey(x[0]) >= floor]
    omitted = (len(d["diffs"]) - len(diffs)) + (len(d["apks"]) - len(apks))
    note = (f"\n{omitted} older row(s) for this channel are omitted here and listed in full in "
            f"[the complete index]({ARCHIVE_DOC_URL}).\n" if omitted else "")
    return f"""## {name} ({sub}) — channel `{d['channel']}`

### Full ROMs

{fulls_table(d, wb)}

<details open>
<summary><b>Incremental diffs ({len(diffs)})</b> — patch an existing version up to a target</summary>

{diffs_table(d, wb, diffs)}

</details>

<details open>
<summary><b>eWeLink app APKs ({len(apks)})</b></summary>

{apks_table(d, wb, apks)}

</details>
{note}"""


def load_wayback(path):
    """Wayback capture stamps as {url: "YYYYMMDDhhmmss"}.

    Written by the archival workflow onto its own data branch. A missing file
    is not an error — the Archived column degrades to gap markers and the
    banner says so, rather than the page silently implying nothing is archived.
    """
    if not path or not os.path.exists(path):
        return {}
    with open(path) as fh:
        state = json.load(fh)
    return {url: rec.get("wb") for url, rec in state.get("files", {}).items() if rec.get("wb")}


def render_body(devices, wb, window):
    parsed = {fn: d for (_, _, fn), d in zip(DEVICES, devices)}
    targets = recent_targets(devices, window)
    blocks = [INTRO, archive_banner(wb, devices), CHANGES, RELEASE_NOTES]
    for name, sub, fn in DEVICES:
        blocks.append(device_block(name, sub, parsed[fn], wb, targets, APK_FLOOR))
    blocks += [SCHEME, FOOTER]
    return "\n".join(blocks) + "\n"


def cmd_render(args):
    devices = load_devices()
    wb = load_wayback(getattr(args, "wayback", None))
    window = getattr(args, "target_window", RENDER_TARGET_WINDOW)
    body = render_body(devices, wb, window)

    if args.out:
        with open(args.out, "w") as fh:
            fh.write(body)
    else:
        sys.stdout.write(body)

    shown_diffs = sum(len([x for x in d["diffs"] if x[0] in recent_targets(devices, window)])
                      for d in devices)
    shown_apks = sum(len([x for x in d["apks"] if vkey(x[0]) >= APK_FLOOR]) for d in devices)
    n = len(body.encode())
    msg = (f"rendered {n} bytes "
           f"({sum(len(d['fulls']) for d in devices)} ROMs, "
           f"{shown_diffs} diffs, {shown_apks} APKs shown; "
           f"{sum(len(d['diffs']) for d in devices)} diffs and "
           f"{sum(len(d['apks']) for d in devices)} APKs indexed)")
    if n >= GITHUB_BODY_LIMIT:
        print(f"WARNING: {msg} — exceeds GitHub's {GITHUB_BODY_LIMIT}-byte body limit", file=sys.stderr)
        return 1
    print(msg, file=sys.stderr)
    return 0


ARCHIVE_INTRO = """# NSPanel Pro firmware — complete index

Every OTA object this project has located on the CoolKit CDN, for both original NSPanel Pro models. This page is **generated from `tools/firmware-index/fw-120p.dat` and `fw-86p.dat`** — edit those, never this file.

The [firmware Discussion](https://github.com/panel-assistant/android/discussions/7) carries a readable subset: recent upgrade targets and the app updates that matter for a current panel. It is capped by GitHub's body limit, which is why the exhaustive list lives here instead of being discarded.

Nothing here is a recommendation. **The flashing procedure is hardware-verified only through 4.4.0**; everything past it is CDN-verified — confirmed to exist and to match its recorded size — and has never been flashed on a panel by this project. Read the [firmware & flashing page](nspanel-pro-firmware.md) before using any of it, and note that anything below the **4.0.12** checkpoint is expected to reach current firmware through that full ROM rather than through the older diffs listed here.

The **Archived** column is the Wayback Machine capture date; reach a capture at `https://web.archive.org/web/<date>/<the download URL>`. A `—` means no capture is recorded yet, which is a gap worth closing, not a claim the file is gone.
"""


def archive_device_block(name, sub, d, wb):
    return f"""## {name} ({sub}) — channel `{d['channel']}`

### Full ROMs ({len(d['fulls'])})

{fulls_table(d, wb)}

### Incremental diffs ({len(d['diffs'])})

{diffs_table(d, wb)}

### eWeLink app APKs ({len(d['apks'])})

{apks_table(d, wb)}
"""


ARCHIVED_CELL_RE = re.compile(r"\|\s*\[?(\d{4}-\d{2}-\d{2})\]?[^|]*\|\s*$", re.M)


def archived_rows(text):
    """How many rows in a generated page carry a capture date."""
    return len(ARCHIVED_CELL_RE.findall(text))


def cmd_archive(args):
    """Generate the exhaustive in-repo index page.

    Refuses to publish a page with less archival coverage than the one it would
    replace. Without that guard a caller that forgets to supply the archival
    state silently rewrites every capture date to a gap marker, and the drift
    test cannot catch it because the URLs are unchanged — which is exactly the
    regression this guard exists to stop.
    """
    devices = load_devices()
    wb = load_wayback(getattr(args, "wayback", None))
    urls = all_url_sizes(devices)
    covered = sum(1 for u in urls if wb.get(u))

    blocks = [ARCHIVE_INTRO]
    parsed = {fn: d for (_, _, fn), d in zip(DEVICES, devices)}
    for name, sub, fn in DEVICES:
        blocks.append(archive_device_block(name, sub, parsed[fn], wb))
    blocks.append(f"""## Coverage

| | Count |
|---|---|
| Indexed objects | {len(urls)} |
| With a Wayback capture | {covered} |
| Without a capture | {len(urls) - covered} |

Regenerate with `python3 tools/firmware-index/firmware_index.py archive --wayback wayback.json --out {ARCHIVE_DOC}`, where `wayback.json` comes from the `wayback-state` branch. Without it the generator refuses to run rather than blank the Archived column.
""")
    body = "\n".join(blocks)

    out = args.out or ARCHIVE_DOC
    if os.path.exists(out) and not getattr(args, "allow_coverage_loss", False):
        existing = archived_rows(open(out).read())
        fresh = archived_rows(body)
        if fresh < existing:
            print(f"ERROR: refusing to write {out} — it currently records {existing} archived "
                  f"row(s) and this run would leave {fresh}. Supply the archival state with "
                  f"--wayback <wayback.json>, or pass --allow-coverage-loss if the loss is "
                  f"intended.", file=sys.stderr)
            return 1

    with open(out, "w") as fh:
        fh.write(body)
    print(f"wrote {out}: {len(body.encode())} bytes, {len(urls)} objects, "
          f"{covered} archived", file=sys.stderr)
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("probe", help="check every URL and append up/down to the history file")
    p.add_argument("--history", required=True)
    p.set_defaults(func=cmd_probe)

    dsc = sub.add_parser("discover", help="search the CDN for versions newer than the index")
    dsc.add_argument("--index-window", type=int, default=DISCOVER_INDEX_WINDOW,
                     help=f"how many indices past the current maximum to probe (default {DISCOVER_INDEX_WINDOW})")
    dsc.add_argument("--minor-window", type=int, default=DISCOVER_MINOR_WINDOW,
                     help=f"how many minor versions ahead to consider (default {DISCOVER_MINOR_WINDOW})")
    dsc.add_argument("--json", help="also write findings to this JSON file")
    dsc.add_argument("--apply", action="store_true",
                     help="append discovered entries to the .dat files")
    dsc.set_defaults(func=cmd_discover)

    a = sub.add_parser("archive", help="generate the exhaustive in-repo index page")
    a.add_argument("--wayback", help="wayback.json from the archival data branch")
    a.add_argument("--out", help=f"output file (default: {ARCHIVE_DOC})")
    a.add_argument("--allow-coverage-loss", action="store_true",
                   help="permit writing fewer archived rows than the existing page")
    a.set_defaults(func=cmd_archive)

    r = sub.add_parser("render", help="emit the Discussion markdown body")
    r.add_argument("--wayback", help="wayback.json from the archival data branch")
    r.add_argument("--target-window", type=int, default=RENDER_TARGET_WINDOW,
                   help=f"how many recent diff targets to show (default {RENDER_TARGET_WINDOW}; 0 = all)")
    r.add_argument("--history", help="accepted and ignored; retained so the monitor keeps working")
    r.add_argument("--out", help="output file (default: stdout)")
    r.set_defaults(func=cmd_render)

    args = ap.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
