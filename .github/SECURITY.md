# Security policy

## Supported versions

ha-paneld is pre-1.0. Only the **latest release** receives security fixes; please reproduce on it before reporting.

| Version | Supported |
| --- | --- |
| latest release | ✅ |
| older | ❌ |

## Reporting a vulnerability

Please **report privately** — do not open a public issue for a suspected vulnerability.

Use GitHub **Private Vulnerability Reporting**: the repository's [**Security → Report a vulnerability**](https://github.com/panel-assistant/android/security/advisories/new) form. It's private to the maintainers and integrates with GitHub Security Advisories.

Please include the panel hardware, the ha-paneld version, and the panel's `/diag` output (it contains no credentials) where relevant. You'll get an acknowledgement, and coordinated disclosure once a fix or mitigation is agreed.

## Scope — know the trust model first

ha-paneld is designed as a **LAN-trust appliance**: the panel and Home Assistant are assumed to share a trusted network. Some behaviour is therefore **by design, not a vulnerability**:

- The HTTP API on `:8888` is **unauthenticated** — restricting who can reach it is delegated to the network layer (VLAN / firewall). See the full threat model and decisions in [docs/architecture/security.md](../docs/architecture/security.md).
- A root/file-level attacker already on the panel is out of scope (they own the device).

Reports of genuine issues *within* that model are very welcome — e.g. an unauthenticated path that escapes the LAN-trust boundary, a command injection, a credential leak off-device, or anything that affects a panel from outside its LAN.

## Dependency and supply-chain policy

ha-paneld treats every library, build plugin, GitHub Action and downloaded application as part of its attack surface. A new third-party component must have a clear project need, an active and identifiable upstream, a compatible licence, and a dependency footprint proportionate to the capability it adds. Official repositories and package registries are preferred. A copied binary or an untraceable package is not accepted as a shortcut.

Project-controlled dependency inputs are reproducibly selected rather than fetched from floating release channels:

- Gradle dependencies use explicit versions, dependency lock state and artifact verification metadata. The Gradle distribution is versioned and checksum-pinned.
- npm dependencies use exact direct versions and a committed lockfile. Automated and release builds use the lockfile without resolving newer versions.
- GitHub Actions are pinned to full commit hashes. The workflow records the corresponding release tag in a comment so updates remain reviewable.
- A curated external APK uses an exact version and download location, a recorded SHA-256 checksum, and an expected Android package name and signing certificate. Updaters that discover version-specific assets from a fixed upstream repository still require the expected package and signing certificate before installation; release discovery never overrides that identity check.

Automated tools may propose dependency updates, but they do not merge them. Each update is reviewed as a code change, including its source, release notes, licence, changed transitive dependencies and unexpected build scripts. Routine upstream releases normally cool for at least seven days before merge so that early problems have time to surface. A security fix may use an accelerated review when delaying it presents the greater risk.

An accepted update must pass the same build, unit, lint, shell and UI checks as application code. Changes to generated bundles must be reproducible from their committed manifests and lockfiles. High-privilege workflow changes and release-tool updates receive additional scrutiny because they can access signing material or publish artifacts.

Release APKs are signed with the project release key and published with a SHA-256 checksum. The release workflow also attempts to produce machine-verifiable provenance and publishes separately scoped CycloneDX inventories for the Android/Gradle runtime and the locked npm runtime graph used to build the embedded profile editor. Hosting-platform attestation failure is advisory and does not block an otherwise signed and verified APK, checksum and scoped inventories from being published. These inventories complement one another; neither is presented as a complete inventory of the Android platform or hosted build runner. These controls improve traceability; they do not guarantee that upstream software is free of vulnerabilities or malicious behavior.

Hosted CI runner images and operating-system package repositories remain external moving inputs. ha-paneld pins the higher-authority build and publishing components and records release provenance, but it does not claim that the complete hosted runner is bit-for-bit reproducible.

Please use the private vulnerability-reporting route above if a dependency, build service, signing path or published artifact may have been compromised.
