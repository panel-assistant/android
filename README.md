<picture>
  <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/maxlyth/ha-paneld?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/maxlyth/ha-paneld/releases)
[![License](https://assets.ha-paneld.com/docs/badge/license-apache-2-0-8aa187e4.svg)](LICENSE)

**The universal Home Assistant dashboard app for Android wall panels.**

ha-paneld makes Home Assistant dashboards practical on panels that otherwise feel too slow or awkward to use. Low-powered panels can become sluggish or take seconds to respond when connected to a large Home Assistant installation. One important cause is that the panel receives and processes updates for far more entities than its dashboard displays. **ha-paneld's built-in renderer can learn which entities the dashboard uses and ask Home Assistant to send only those states**. In the real world, this can reduce entity load by 10–100×, making that dashboard finally usable.

ha-paneld also gives different makes of wall panel a consistent set of controls in Home Assistant. Depending on the hardware, that can include the screen, LEDs, buttons, sensors, relays and audio. MQTT discovery adds the available controls without per-device YAML, and the installer takes care of the Android setup.

This is an app for dedicated wall panels, not personal phones. Hardware support is described through ordinary YAML profiles, so owners and manufacturers can add another panel without rebuilding the app.

The web interface gives you one place to configure a panel, install software and find out what has gone wrong. Its performance tools measure dashboard response time, unexpected reloads, CPU and GPU load, clock speed, temperature and the busiest processes. The installer provides the same setup and update path across a mixed collection of panels, while the built-in launcher and on-screen navigation make panels without hardware keys practical to use.

<picture>
  <source media="(prefers-color-scheme: light)" srcset="https://assets.ha-paneld.com/docs/screenshot/hero-light-a17f5f14.webp">
  <img src="https://assets.ha-paneld.com/docs/screenshot/hero-dark-aeb93099.webp" alt="ha-paneld Dashboard showing live panel state, performance and display controls">
</picture>

<details>
<summary><strong>More screenshots</strong></summary>

| Dashboard | Configure |
|---|---|
| <a href="docs/img/ui-dashboard-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="docs/img/ui-dashboard-light.png"><img src="docs/img/ui-dashboard-dark.png" alt="Dashboard tab" width="420"></picture></a> | <a href="docs/img/ui-configure-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="docs/img/ui-configure-light.png"><img src="docs/img/ui-configure-dark.png" alt="Configure tab" width="420"></picture></a> |

| Entities | Install |
|---|---|
| <a href="docs/img/ui-entities-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="docs/img/ui-entities-light.png"><img src="docs/img/ui-entities-dark.png" alt="Entities tab" width="420"></picture></a> | <a href="docs/img/ui-install-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="docs/img/ui-install-light.png"><img src="docs/img/ui-install-dark.png" alt="Install tab" width="420"></picture></a> |

| Profile | Logs |
|---|---|
| <a href="docs/img/ui-profile-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="docs/img/ui-profile-light.png"><img src="docs/img/ui-profile-dark.png" alt="Profile tab" width="420"></picture></a> | <a href="docs/img/ui-logs-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="docs/img/ui-logs-light.png"><img src="docs/img/ui-logs-dark.png" alt="Logs tab" width="420"></picture></a> |

| Standing screen | Proximity sensor setup |
|---|---|
| <img src="docs/img/standing-screen.png" alt="ha-paneld standing screen with the configuration address and QR code" width="420"> | <img src="docs/img/proximity-setup-screen-480.gif" alt="Proximity sensor setup" width="420"> |

</details>

## Install

If you are unsure whether ha-paneld can run on your panel, check [Panels and support status](#panels-and-support-status) before installing.

First make ADB available over the network. On some panels this is a Developer options setting; others need a one-time USB connection to run `adb tcpip 5555`. The [provisioning guide](https://panel-assistant.io/go/docs?page=provisioning) and model-specific [hardware guides](https://panel-assistant.io/go/docs?page=hardware/readme) explain the available methods. Then run this from a computer with `adb` on the same network:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

> [!IMPORTANT]
> **On Windows, use Git Bash or WSL, not PowerShell.** The installer is a `bash` script. Git Bash is included with [Git for Windows](https://gitforwindows.org/). Install `adb` with `winget install Google.PlatformTools`, reopen the shell and then run the command. macOS and Linux can run it as written.

You do not need to clone the repository or supply any options. The installer checks that `adb` and `curl` are available, asks for the panel address and explains each change before making it. It downloads the latest signed stable release, installs it and checks that ha-paneld started correctly.

If a required step fails, the installer names the problem and exits without claiming that the installation succeeded. Correct the problem and run the same command again.

> [!IMPORTANT]
> **Check Home Assistant and the panel's system WebView before the first dashboard load.** The built-in renderer requires Home Assistant 2026.4.2 or newer and a modern WebView. Even a new panel can contain a WebView too old to display a current dashboard. See [Built-in renderer requirements](https://panel-assistant.io/go/docs?page=built-in-renderer) and [Updating the system WebView](https://panel-assistant.io/go/docs?page=hardware/readme).

To follow the newest published release, including release candidates, add `--prerelease`. A newer stable release still wins:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
```

The same installer supports unattended single-panel provisioning. See [Provisioning and fleet updates](https://panel-assistant.io/go/docs?page=provisioning) for scripted installs, USB bootstrap, panels without network ADB and whole-fleet updates.

ha-paneld is not distributed through Google Play, so installation always involves sideloading. This also applies to newer panels that otherwise have access to the Play Store.

### Other ways to install

- **F-Droid on the panel:** add [ha-paneld's F-Droid repository](https://panel-assistant.io/go/docs?page=fdroid) to install and update stable releases without a computer. F-Droid notifies you when an update is available and lets you install it on the panel; release candidates are not included. Sonoff NSPanel Pro firmware 4.0.0 and newer includes F-Droid. This installs the app, but features requiring root still need the normal provisioning steps.
- **Manual sideloading or USB bootstrap:** use the APK from the [latest release](https://github.com/maxlyth/ha-paneld/releases) and follow [Provisioning and fleet updates](https://panel-assistant.io/go/docs?page=provisioning) for the remaining permissions and setup.

## Choose how the dashboard runs

The built-in renderer is by far the preferred choice, and the one guided setup uses. It is the only route with dashboard entity filtering, and it also supports signing in from another browser, selecting a specific dashboard tab, and faster startup and recovery. After an app restart, it can reopen the last verified account-default dashboard while it refreshes Home Assistant's dashboard list in the background.

The official [Home Assistant Companion app](https://github.com/home-assistant/android) is still supported as an option, for example when a panel needs more than one Home Assistant server, Assist voice control or native notifications. On a panel without Google Play and with a supported install method, use ha-paneld's Install tab. The picker applies the compatibility limit for that panel instead of assuming the newest Companion release will run on it.

<a id="panels-and-support-status"></a>

## Panels and support status

ha-paneld does not need to be installed as a system app. Basic Android controls such as brightness, navigation and TTS work on compatible panels. LEDs, relays, true screen-off and some sensors need support for that model in its [panel profile](https://panel-assistant.io/go/docs?page=profiles/readme). Hardware-button events need Android Accessibility capture or a verified profile method.

| Panel | Status | Android / ABI | Notes |
|---|---|---|---|
| Sonoff NSPanel Pro / Pro 120 | Supported | Android 8.1, arm64-v8a | PX30 / rk3326-S; stock firmware provides root ADB, and normal provisioning installs ha-paneld's authenticated root helper |
| Tuya TPA10 | Supported | Android 11, armeabi-v7a | rk3566 with 32-bit userspace |
| Electron WF1589T | Supported | Android 14, arm64-v8a | rk3576 userdebug firmware; `adb root`, native Android navbar and RGB LED control |
| ZHICAI SMT1019 | Community-tested, some features experimental | Android 14, arm64-v8a | rk3576; stock firmware has no app-accessible root. The authenticated helper can provide additional hardware access where installed. Climate accuracy and proximity support still need more hardware testing. [Issue #8](https://github.com/maxlyth/ha-paneld/issues/8) |
| ZX-SMT156 / RK3566_T | Preliminary | Android 13, arm64-v8a | RGB LED and light/proximity work without root. Climate support is optional; relays and root access are still being characterised. [Issue #24](https://github.com/maxlyth/ha-paneld/issues/24) |
| Smatek S9E | Experimental | Android 11, arm64-v8a | Profile for onboard relays, button LEDs and proximity. Live confirmation on S9E hardware is still needed. |
| Shelly Wall Display (original) | Incompatible stock software | Android 7.0, armeabi-v7a | Android is older than ha-paneld's minimum version. |
| Shelly Wall Display X2 | Research only | Android 8.1, armeabi-v7a | No confirmed ha-paneld installation path. |
| Shelly Wall Display X2i | Preliminary | Android 11, arm64-v8a | rk3326. Confirmed on one unit: developer mode unlocks ADB, and installation, permission grants, the proximity and light sensors, brightness and taking over the home screen all work without root. The onboard relays are not exposed. |
| Shelly Wall Display X1i / XL | Research only | Android 11, arm64-v8a | Profile metadata still needs to be split by model. No confirmed ha-paneld installation path. |

See the [hardware documentation](https://panel-assistant.io/go/docs?page=hardware/readme) for model-specific setup, known limitations and reverse-engineered hardware details.

## Hardware Control Capabilities

Each panel publishes only the controls supported by its profile and detected hardware. Their names and behaviour remain consistent across models.

| Capability | Home Assistant or API control |
|---|---|
| Screen brightness | `light.<panel>_screen` brightness |
| Screen on/off | `light.<panel>_screen` on/off; true screen-off where the profile supports it, otherwise safe brightness dimming |
| RGB LED | `light.<panel>_led` on panels with supported LED hardware |
| Hardware buttons | `event.<panel>_button` when Android Accessibility capture or a verified profile method is available |
| Ambient light and proximity | `sensor.<panel>_illuminance`, `binary_sensor.<panel>_proximity` and a normalised `sensor.<panel>_proximity_level` from 0 (far) to 100 (near) |
| Adaptive brightness | Optional seven-day learning from the panel's light sensor or a Home Assistant illuminance entity |
| Open a URL | `text.<panel>_navigate` |
| Dashboard controls and reboot | Home Assistant buttons plus Dashboard, Reload and navigation actions in the remote Controls panel |
| TTS and announcement audio | `POST /play` and `number.<panel>_volume`; see the [TTS guide](https://panel-assistant.io/go/docs?page=tts) |
| Dashboard screenshot and remote tap | Panels with a supported screenshot method can show and refresh the screen from the Dashboard tab; Relaxed mode also allows a click to be sent back to the panel |
| Panel information and configuration | Open `http://<panel>:8888/`, also linked as **Visit** on the Home Assistant device page |

Home Assistant discovers these controls through MQTT without YAML. The main entity families, HTTP API and pairing details are in [docs/api.md](https://panel-assistant.io/go/docs?page=api). You can also browse and try the HTTP API on a panel at `http://<panel>:8888/api`.

## Security and root access

### Hardened security mode

Relaxed mode is the default and is intended for a trusted home network. Use [Hardened security mode](https://panel-assistant.io/go/docs?page=security-mode) when less-trusted devices share the network. Hardened security mode requires physical access to the panel. Someone must approve high-impact remote actions on the panel's screen; they cannot be approved remotely. Screenshots remain viewable, but remote taps are disabled. The setting must be enabled separately on each panel and is not copied by backup, restore or fleet provisioning.

### Features that need root

Some panel hardware is hidden from ordinary Android apps and therefore needs root access. Whether root is available depends on the panel's firmware, not ha-paneld. Some panels expose `su`; on others, the installer can add ha-paneld's small root helper. The helper does not provide a general shell or unrestricted file access.

The web interface marks unavailable controls with a lock and explains what the panel is missing. The installer and diagnostics also report which level of access is available.

**No root needed:** Home Assistant pairing, screen brightness and dimming, audio announcements, both dashboard choices, the web interface, the REST API and configuration backup and restore. Back, Recents, wake on wave and the software navbar depend on the corresponding Android or sensor capability but do not inherently require root.

**Root or the authenticated helper may be needed:** physical backlight-off, Android sleep where the profile selects it, RGB LED control on some panels, vendor-app control, reboot and CPU governor. If the active profile has no safe way to turn the screen fully off, ha-paneld dims it instead.

**Direct `su` inside ha-paneld is still needed:** Lock Android to dashboard, complete system logs, relay control where the profile requires it, and the legacy Companion-session import path. A full backup can include an existing Companion login, which always goes through the authenticated helper: the descriptor-confined protocol is the only path, on direct-root panels too.

A limited [advanced fallback](https://panel-assistant.io/go/docs?page=provisioning) exists for genuinely unrooted panels, but it is not part of the normal supported-hardware path and does not provide root-only hardware features.

## Guides and reference

### Using ha-paneld

- [Provisioning and fleet updates](https://panel-assistant.io/go/docs?page=provisioning): unattended installation, USB and network ADB setup, backups and whole-fleet updates.
- [Built-in renderer](https://panel-assistant.io/go/docs?page=built-in-renderer): requirements, remote sign-in, dashboard selection, recovery and deliberate limitations.
- [Performance](https://panel-assistant.io/go/docs?page=performance): find out why a dashboard is slow and measure the effect of entity filtering.
- [Adaptive brightness](https://panel-assistant.io/go/docs?page=adaptive-brightness): select a light source, understand learning and reset the history after moving a panel.
- [Adaptive proximity and wake on wave](https://panel-assistant.io/go/docs?page=adaptive-proximity): configure proximity detection and teach the wake gesture.
- [Security modes](https://panel-assistant.io/go/docs?page=security-mode): understand Relaxed mode and Hardened security mode, including which actions require someone at the panel.
- [TTS](https://panel-assistant.io/go/docs?page=tts): render speech with a Home Assistant TTS engine and send it to a panel.

### Developing and extending ha-paneld

- [HTTP, MQTT and Home Assistant API](https://panel-assistant.io/go/docs?page=api): the HTTP endpoints, main MQTT entity families, pairing and discovery. The machine-readable specification is available from a panel at `/api/v1/openapi.json`.
- [Panel profiles](https://panel-assistant.io/go/docs?page=profiles/readme): create, test and share support for another panel without rebuilding the app.
- [Hardware references](https://panel-assistant.io/go/docs?page=hardware/readme): model-specific setup, sensors, controls, firmware and reverse-engineering notes.
- [Building from source](https://panel-assistant.io/go/docs?page=building) and [local development](https://panel-assistant.io/go/docs?page=local-builds): build with Docker, the development container or a local Android toolchain.
- [Roadmap](https://panel-assistant.io/go/docs?page=roadmap): planned work. Completed work is recorded in the [changelog](docs/CHANGELOG.md).

The panel's `GET /diag` page produces a hardware, firmware and capability report for bug reports. Check and redact it before posting it publicly.

## Other kiosk apps

### Fully Kiosk

I do not recommend running [Fully Kiosk Browser](https://www.fully-kiosk.com/) and ha-paneld together. Both would try to manage the screen, kiosk behaviour and remote controls, leaving two places to configure the same panel.

<details>
<summary>Why I do not recommend running both</summary>

- Fully Kiosk is closed-source commercial software. Its remote administration features require a [paid licence for each device](https://license.fully-kiosk.com/license/single).
- Entity filtering is part of ha-paneld's built-in renderer, so a separate browser cannot use it.
- Fully Kiosk is configured separately on each device, which becomes awkward when several different makes of panel need to behave consistently.

Use one dashboard app on the panel: ha-paneld's built-in renderer, Companion, or a separate kiosk browser if it provides something the other two do not.

</details>

### FreeKiosk

[FreeKiosk](https://github.com/RushB-fr/freekiosk) is unrelated to ha-paneld despite the similar name. It is free and open source, but it uses React Native and therefore runs another JavaScript engine alongside the Home Assistant dashboard. That extra load can be significant on panels with only 1–2 GB of RAM.

## Community chat

I did not want to set up a Discord server or Slack workspace for a one-man project, so I am experimenting with Matrix and Element. Join [#ha-paneld:matrix.org](https://matrix.to/#/#ha-paneld:matrix.org) in your usual Matrix client, or view it without an account in [Element Web](https://app.element.io/#/room/#ha-paneld:matrix.org).

Do not post configurations or file links in GitHub issues or discussions unless you are comfortable with them remaining public forever. The Matrix room is public and world-readable too, but Matrix also supports private direct messages for support details that should not become part of a permanent public record. Redact credentials, private URLs and personal details before posting configurations, logs or file links anywhere.

## Want your panel supported?

ha-paneld has no donate button. It is free, and the "payment" that actually moves it forward is more panels supported. That takes hardware to study.

Start with the [runtime profile guide](https://panel-assistant.io/go/docs?page=profiles/readme). The Generic profile can produce a passive draft that you can validate, test and share without building the app. Before a profile can be bundled with ha-paneld, I still need evidence from the real device, especially for its buttons, LEDs, relays and sensors.

So if you'd like to help:

- **Create and share a profile.** Open `http://<panel-ip>:8888/profiles`, download the Generic device draft, and follow the [testing](https://panel-assistant.io/go/docs?page=profiles/testing) and [sharing](https://panel-assistant.io/go/docs?page=profiles/sharing) guides. A community profile can be useful before it is ready to ship with ha-paneld.
- **Open an issue with the panel's diagnostics.** Visit `http://<panel-ip>:8888/diag`, check and redact the report, then paste it into a new issue. That is enough to start. I will work with you through a short set of tests for any buttons, LEDs, relays or sensors that need someone at the panel.
- **Send me the panel.** I'm UK-based and happy to do the reverse-engineering directly. This is the fastest route to fully supported hardware. You'll get it back (I have way too many already); open an issue first so we can arrange the details.

The result is always open: your panel becomes a profile everyone can use. That's the donation.

## Development

If you want to work on ha-paneld itself, start with [CONTRIBUTING.md](.github/CONTRIBUTING.md). The developer documentation covers [building from source](https://panel-assistant.io/go/docs?page=building), [local and development-container builds](https://panel-assistant.io/go/docs?page=local-builds), the [HTTP and MQTT API](https://panel-assistant.io/go/docs?page=api), [panel-profile development](https://panel-assistant.io/go/docs?page=profiles/readme), the [browser test harness](tools/test/README.md), and the [release process](docs/RELEASING.md).

I have deliberately provided enough information to use the supplied development container and build a local test version. Do not submit computer-generated pull requests or issues unchanged: read and understand every part of the proposed text and code, then rewrite it in your own words. This is a one-man project, and I do not have time to review unfiltered computer-generated output. Be succinct and write for humans; if you are unsure about something, ask first.

<details>
<summary><strong>Technology stack</strong></summary>

- **Application:** [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX](https://github.com/androidx/androidx) and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines).
- **HTTP and Home Assistant WebSocket:** [Ktor](https://github.com/ktorio/ktor) CIO server, client and WebSocket modules.
- **MQTT:** [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client), using its MQTT 5 client and pure-Java NIO transport.
- **mDNS:** [JmDNS](https://github.com/jmdns/jmdns), advertising `_ha-paneld._tcp` so ha-paneld instances can find one another for the multi-panel switcher. ha-paneld reports when that advertisement stops and cannot be recovered.
- **Runtime profiles:** [SnakeYAML Engine](https://github.com/snakeyaml/snakeyaml-engine) for YAML 1.2, with [CodeMirror](https://codemirror.net/) and its [YAML language package](https://github.com/codemirror/lang-yaml) in the profile editor.
- **QR and logging:** [ZXing](https://github.com/zxing/zxing) for setup QR codes and [SLF4J](https://github.com/qos-ch/slf4j) for Ktor and HiveMQ logging through Logcat.

Dependency selection and updates follow the project's [dependency and supply-chain policy](.github/SECURITY.md#dependency-and-supply-chain-policy).

</details>

## Translations

Translations are generated and cross-checked using multiple services and models, including EuroLLM, DeepL and OpenAI. They have not been systematically reviewed by speakers of each language, so the English text remains authoritative. If wording is unclear or incorrect, [open a translation correction issue](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

## Acknowledgements

Thanks to **Seaky** for [NSPanel Pro Tools](https://github.com/seaky/nspanel_pro_tools_apk), which was one of the projects that inspired me to start ha-paneld. ha-paneld is not an open-source reimplementation of NSPanel Pro Tools. It has grown into a much broader wall-panel platform, with its own dashboard renderer, entity filtering, runtime hardware profiles, diagnostics and provisioning across multiple makes of panel. The two projects now have very different feature sets and should not be treated as interchangeable, even on a Sonoff panel.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
