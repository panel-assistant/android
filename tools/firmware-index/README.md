# Firmware index, availability monitor, and Wayback archiver

Source of truth for the **NSPanel Pro firmware OTA download index**, published in two places: [Discussion #7](https://github.com/panel-assistant/android/discussions/7) carries the recent upgrade targets, because GitHub caps a Discussion body, and the generated [complete index](../../docs/hardware/nspanel-pro-firmware-archive.md) carries every indexed object. Alongside them, a daily checker verifies every download link still resolves at its recorded size, and a weekly job preserves every firmware file in the Internet Archive — each row's capture date is what the **Archived** column reports.

## Files

- `fw-120p.dat`, `fw-86p.dat` — the verified link data (one device per file).
- `firmware_index.py` — generator (`render`) and prober (`probe`).
- `wayback_archive.py` — submits the index page + every firmware URL to the Wayback Machine.
- [`.github/workflows/firmware-url-monitor.yml`](../../.github/workflows/firmware-url-monitor.yml) runs `probe` then `render` daily and rewrites the Discussion body, with a one-hour retry when any URL is unreachable.
- [`.github/workflows/firmware-wayback.yml`](../../.github/workflows/firmware-wayback.yml)
  archives to the Internet Archive weekly and on any `fw-*.dat` change.

## Data format

```text
channel    <cdn-channel>                  # nspanel-pro (86P) | nspanel-pro-ver120 (120P)
diffsuffix <suffix-or-empty>              # 120P diffs carry V228; 86P diffs are bare
apkfmt     <apk-filename-prefix>          # 86P "app", 120P "228V"
full|<ver>|<idx>|<filename>|<bytes>
diff|<target-ver>|<idx>|<from-ver>:<bytes>|<from-ver>:<bytes>...
apk|<ver>|<idx>|<bytes>
```

`<idx>` is the per-build serial in the CDN path. The `rom-diff` index is per
**target** version, so one `diff|` line lists every `from` variant that shares it.

URLs are built as:

- Full ROM — `https://global-otadl2bsy.coolkit.cc/<channel>/rom/<idx>/<filename>`
- Diff — `…/<channel>/rom-diff/<idx>/CK_<from>_<to><suffix>-diff.zip`
- APK — `…/<channel>/apk/<idx>/<apkfmt><ver>.apk`

## Usage

```bash
# Regenerate the Discussion body locally (grey squares — no live data):
python tools/firmware-index/firmware_index.py render --out body.md

# Check every URL and append a sample to a history file:
python tools/firmware-index/firmware_index.py probe --history history.json

# Render with the archival state so the Archived column is populated:
python tools/firmware-index/firmware_index.py render --wayback wayback.json --out body.md

# Regenerate the complete in-repo index (also needs the archival state):
python tools/firmware-index/firmware_index.py archive --wayback wayback.json
```

Stdlib only — no dependencies. The **Archived** column is the Wayback Machine capture date, read from `wayback.json` on the `wayback-state` branch; `—` means no capture is recorded yet. `archive` refuses to write a page with fewer archived rows than the one it replaces, so forgetting `--wayback` fails the run instead of quietly erasing the dates.

## Shelly Wall Display monitor

`shelly_firmware.py` checks the `WallDisplay` and `WallDisplayV2` OTA tracks.
Shelly's manifest and firmware hosts use a private Allterco CA, so the monitor
adds the reviewed `shelly-cloud-ca.pem` trust anchor to Python's default TLS
context. Certificate validation and hostname checking remain required. The CA
was recovered from the `shelly_cloud.pem` trust store in the official ShellyOS
Plus1 1.7.5 firmware image (SHA-256
`8b856276fcd4e629650256e5fc73a90a9cc6c061269e867c5bdc0b61e355f1db`);
its DER SHA-256 is pinned by the unit tests.

The Shelly workflow's manual dispatch defaults to `verify`. That job strictly
fetches every current manifest and HEADs every corresponding CDN object, even
when the version is already indexed. Any manifest, certificate, hostname or CDN
failure makes the job fail. It has read-only repository permissions and receives
no publishing secrets. Select `update` explicitly to run the archive, Discussion,
and firmware-index commit path. Scheduled runs continue to use the update path.

## Wayback archiving

`wayback_archive.py` preserves the index against the CoolKit CDN going away. It saves the Discussion page (with `capture_outlinks=1`) and submits every firmware URL to Save Page Now *explicitly* — `capture_outlinks` caps at 100 links, far short of the more than 200 files, so the page crawler alone is not relied on. Firmware URLs are immutable, so each is archived once and recorded in the state file; later runs only submit new ones.

Save Page Now rejects anonymous API calls, so a secret is required:

1. Create a free [archive.org account](https://archive.org/account/signup).
2. Generate S3 keys at [archive.org/account/s3.php](https://archive.org/account/s3.php).
3. Add them as the repo Actions secret **`WAYBACK_S3`** in the form `accesskey:secret`.

Without the secret the workflow skips cleanly (stays green). State lives on the
append-only `wayback-state` branch. Local run:

```bash
WAYBACK_S3="accesskey:secret" python tools/firmware-index/wayback_archive.py \
  --state wayback.json --page-url "https://github.com/panel-assistant/android/discussions/7" --max 5
```

## Adding or correcting a link

Edit the relevant `.dat` file and commit. The next scheduled run (or a manual
`workflow_dispatch`) republishes the Discussion. The history branch
(`firmware-status`) is machine-managed — don't edit it by hand. The Discussion
body itself is generated; edit the data here, not the post.

To find new builds, range-GET a candidate URL — a live file answers with `206`
and a `Content-Range` total; a missing one returns `403`:

```bash
curl -s -r 0-0 -D - -o /dev/null "<url>"
```
