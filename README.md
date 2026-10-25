<h1 align="center">
  <a href="https://panel-assistant.io"><picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://panel-assistant.io/brand/wordmark-on-dark.svg">
    <img src="https://panel-assistant.io/brand/wordmark-on-light.svg" width="500" alt="Panel Assistant">
  </picture></a>
</h1>

<h1 align="center">Home Assistant, on the wall, done properly.</h1>

[![CI](https://github.com/panel-assistant/android/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/panel-assistant/android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/panel-assistant/android?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/panel-assistant/android/releases)

**The Panel Assistant app for Android wall panels: your Home Assistant dashboard on the wall, the panel's screen, sensors and buttons as Home Assistant entities, and an Assist voice satellite on panels with a microphone.**

It runs on ARM wall panels with Android 8.0 or newer and debugging access, from Sonoff (NSPanel Pro), Shelly, Tuya, Meta, Electron and Smatek, and on any other panel that meets those requirements. You do not install it yourself: the [Panel Assistant integration](https://github.com/panel-assistant/ha-integration) installs it from Home Assistant, over the network or over USB from your browser, and delivers every update.

**Get started:** add Panel Assistant to Home Assistant through [HACS](https://github.com/panel-assistant/ha-integration#install-the-integration), then follow [Getting started](https://panel-assistant.io/start/getting-started/). [Choose a panel](https://panel-assistant.io/install/supported-panels/) says what to expect from each model, and [Updates and recovery](https://panel-assistant.io/manage/updates-and-recovery/) covers what happens if an installation stops.

## Development

This repository contains the Android app, its root helper and developer tooling. Start with [CONTRIBUTING.md](.github/CONTRIBUTING.md).

- [Building from source](https://panel-assistant.io/go/docs?page=building) and [local development](https://panel-assistant.io/go/docs?page=local-builds).
- [Control and Home Assistant reference](https://panel-assistant.io/go/docs?page=api): the panel's entities, its pages and endpoints, and the deprecated REST API.
- [Panel-profile development](https://panel-assistant.io/go/docs?page=profiles/readme), with [example](docs/profiles/examples/minimal-community.yaml) and [community profiles](docs/profiles/unofficial/).
- [Browser test harness](tools/test/README.md).
- [Root-helper protocol and development](helper/README.md).
- [Release process](docs/RELEASING.md) and [changelog](docs/CHANGELOG.md).
- [Security policy](.github/SECURITY.md).

## Licence

Apache-2.0. See [LICENSE](LICENSE).
