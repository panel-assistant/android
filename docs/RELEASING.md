# Releasing ha-paneld

A release is cut by pushing a `vX.Y.Z` tag; the [Release workflow](../.github/workflows/release.yml) then verifies the already-green exact source, builds, signs and publishes the APK and both supported root-helper binaries, taking the human-readable notes from the matching `docs/CHANGELOG.md` section. Run this checklist **before** tagging.

> [!IMPORTANT]
> **Releases are contenders until approved.** Pushing a tag starts the public Release workflow and publishes the resulting GitHub release if its gates pass, so prepare and verify a release *before* tagging and **tag only on explicit approval**. Pushing dev commits to `main` is fine; the **tag push is the gate**. Don't eager-tag and then force-move the tag to absorb late fixes — fold late changes into the still-untagged contender instead. (Force-moving a published tag rewrites a release others may have pulled.)

## Pre-tag checklist

1. **Public reference currency — required for every public stable and prerelease build.** Reconcile the complete public reference set with the final contender, not only files touched by the release:
   - Check README capability, support, privilege and release-status claims against the current source.
   - Check `docs/api.md` against the registered HTTP routes, MQTT discovery contracts and generated `/api/v1/openapi.json`; check the profile-format reference against the runtime schema and driver catalog.
   - Check feature, provisioning, security, build and hardware guides against current settings, scripts, profiles and access probes. Run the repository's documentation link/source checks and resolve stale headings as well as missing files.
   - Review every addition or removal in `/diag` instrumentation. Keep only data with concrete issue-triage value; keep it terse; remove duplication with existing status/capability fields; and verify that values, examples and redaction rules do not expose credentials, tokens, network identifiers, panel names, entity IDs or other PII/private deployment context.
   - When release notes or announcement drafts introduce Hardened mode, including GitHub and forum posts, state plainly that it requires physical access and that high-impact remote actions cannot proceed until someone approves them on the panel's screen; they cannot be approved remotely.
   - In **`docs/roadmap.md`**, move every item that's now done **out of "Planned"** (it's shipped — it belongs in `docs/CHANGELOG.md`, not the roadmap).
   - Confirm the README's release badge still reads GitHub dynamically and that no surrounding prose hard-codes an older release or candidate. The badge is the release-status summary; the README deliberately does not duplicate it in a manually maintained section.
   - Refresh screenshots in `docs/img/` from the final combined contender when the UI changed. Use the deterministic capture harness with Roboto loaded and verified, fixed documented viewports and normalized image metadata; the on-panel standing-screen shot is **480×480**.
   - Hardware photographs are not committed here and are not tied to a release — they live on `assets.ha-paneld.com` and are published with [`tools/docs-assets/assets.py`](../tools/docs-assets/assets.py), which strips metadata as a hard gate because camera EXIF carries GPS and serial numbers. Nothing about a release requires republishing them; see [`docs/infrastructure.md`](infrastructure.md).

2. **docs/CHANGELOG.md** — the release workflow looks for a section whose header matches the **exact tag** (`## v0.8.4-rc3`, not `## v0.8.4`). For a missing RC section the workflow **errors** (not just warns) so the CI job fails before a release is published with wrong notes.

   - **RC tags** (`-rc1`, `-rc2`, …): add a `## vX.Y.Z-rcN - <date>` section describing **only what changed since the previous RC** — not a repeat of prior RC content. This curated section is the complete changelog prose, so include every user-visible change needed to evaluate the candidate without adding maintainer-oriented commit lists. The workflow prepends its static **Recommended installation** guidance to form the complete public release body.
   - **RC numbers normally track PUBLISHED prereleases only** — the `-rcN` suffix increments when an rc is tagged and pushed to GitHub, while ordinary internal iteration bumps `versionCode` alone. Bump `versionCode` aggressively: it drives in-place upgrades and the `/health` build token. If the maintainer explicitly allocates a numbered local candidate before publication, give it its own delta-only section and preserve every earlier RC section; do not make the newest candidate cumulative.
   - **Stable tags**: add `## vX.Y.Z - <date>` with cumulative notes for the whole version. The workflow falls back to the base-version section only for stable tags, so the stable section is the one place to summarise the full release for users upgrading directly from the previous stable.
   - **The stable section must be SELF-CONTAINED**. It is the canonical summary of everything the RC line delivered, so it must not point at or cite RC notes (no "see the rc sections", no per-bullet `(rcN)` attributions). Keep the separate delta-only `-rcN` sections throughout the prerelease cycle. At stable promotion, merge their user-relevant content into the stable section and remove those superseded RC sections; the RC prereleases and tags are deleted at the same point (see [Stable promotion: remove superseded release candidates](#stable-promotion-remove-superseded-release-candidates)).

   Group entries under **Added / Changed / Fixed / Docs** (only the groups with content) — see the format note at the top of `docs/CHANGELOG.md`.
3. **Version bump** — `app/version.properties` `versionName` matches the tag and `versionCode` is incremented. (A higher `versionCode` lets panels `install -r` in place.) Confirm the dynamic release badge in `README.md` still points at this repository's GitHub releases; no version edit is needed for the badge itself.
4. **Docs** — any new capability has a matching `docs/` entry (hardware page, recipe, etc.).
5. **Build + sanity-test** on at least one real panel. Local APK and fleet paths require Android SDK Build-Tools containing `apksigner` and either `aapt` or `aapt2`. For an official-signer artifact, use `scripts/update-fleet.sh --require-release-signer --apk <built.apk> -- <ip>`; for a self-built artifact on a rooted panel, use `scripts/provision.sh <ip> --apk <built.apk> --allow-unsigned-helper`.
   - For a rooted panel and local APK, provisioning extracts the ABI-matched helper embedded in that exact APK. It treats the APK and helper as one compatibility unit and refuses to replace the APK unless the running helper reports the embedded source-derived build identity and required protocol.
   - Before tagging, wait for the final `main` CI and Security workflows for the exact contender commit to complete successfully. The tag workflow requires the latest successful Android build, host contracts, dependency-integrity, privileged-helper and CodeQL checks for that source commit, rejects open CodeQL alerts, and independently creates the release inputs from a clean checkout of the exact tag.
   - Full Android lint is not part of CI. Review the latest [Lint workflow](../.github/workflows/lint.yml) run on `main` (it runs nightly, or dispatch it) before tagging; the tag push starts another run beside the Release workflow.
6. **Authorship / no-secrets gate** — verify that commit authors and trailers are intentional and that the published history contains no keystore or credentials. Scan the pending commits before pushing.

## Tag + publish

Replace `<exact-contender-sha>` with the full reviewed commit ID. Explicit source and destination refs prevent a divergent local `main` or an unrelated checked-out `HEAD` from selecting the release source. Run the first command, then verify that exact commit is on `origin/main` and that its required checks have passed before running the final two commands.

```sh
git push origin <exact-contender-sha>:refs/heads/main
git tag vX.Y.Z <exact-contender-sha>
git push origin refs/tags/vX.Y.Z
```

## Stable promotion: remove superseded release candidates

After the stable Release workflow succeeds and its expected assets have been verified, remove every `vX.Y.Z-rcN` Release object and matching tag for that exact version. Keep the release candidates available until then so a failed stable workflow does not remove the newest downloadable build. Preserve every release and tag that does not match the exact `vX.Y.Z-rcN` pattern.

First enumerate the exact stable release and RC release, remote-tag and local-tag sets. Replace `X.Y.Z` with the stable version; do not broaden the pattern to another version line.

```sh
gh release view vX.Y.Z --repo maxlyth/ha-paneld --json tagName,isDraft,isPrerelease,url
gh release list --repo maxlyth/ha-paneld --limit 100 \
  --json tagName,isPrerelease,isDraft \
  --jq '.[] | select(.tagName | test("^vX\\.Y\\.Z-rc[0-9]+$")) | [.tagName, .isPrerelease, .isDraft] | @tsv'
git ls-remote --tags origin 'refs/tags/vX.Y.Z-rc*'
git tag --list 'vX.Y.Z-rc*'
```

The `isPrerelease` and `isDraft` columns are audit evidence only. The exact tag pattern determines membership even when a Release object has incorrect prerelease metadata.

Compare the Release-object inventory with the remote-tag inventory and classify each exact RC tag before deletion:

- When a Release object exists, delete it with `gh release delete --cleanup-tag`; this also deletes its matching remote tag.
- When a remote RC tag has no Release object, classify it as an orphan and delete that exact remote ref directly.
- When the exact tag is present locally as well as remotely, delete the local tag only after its applicable remote deletion succeeds.
- When the exact tag appears only in the local inventory, classify it as local-only and delete it only after both the Release-object and remote-tag inventories prove that literal tag absent.

Before running either remote deletion command, replace `X.Y.Z` and `N` with one literal tag, preview the complete classification and exact commands, and obtain explicit approval for the destructive public actions. Use only the applicable remote command for each tag. Never use a wildcard, loop or inferred numeric range in a mutation command. Run the local deletion only when that literal tag appeared in the local inventory and either its applicable remote deletion succeeded or both remote inventories proved it local-only.

```sh
# A Release object exists for this exact tag:
gh release delete vX.Y.Z-rcN --repo maxlyth/ha-paneld --cleanup-tag --yes

# No Release object exists and this exact remote tag is an orphan:
git push origin :refs/tags/vX.Y.Z-rcN

# This exact tag appeared locally and its remote deletion succeeded, or both remote inventories prove it local-only:
git tag --delete vX.Y.Z-rcN
```

Re-run all four inventory commands afterwards and confirm that no `vX.Y.Z-rcN` Release object, remote tag or local tag remains for the promoted version, that every non-matching release and tag remains untouched and that the stable release is still present.

## After release

- Follow stable releases with `scripts/update-fleet.sh --latest -- <ip> <ip> …`, or the newest published release including release candidates with `scripts/update-fleet.sh --prerelease -- <ip> <ip> …` (both install **and** launch each panel — a bare `adb install -r` loop leaves them installed-but-dead).
- Confirm the release contains both `ha-paneld-helper-<tag>-armeabi-v7a` and `ha-paneld-helper-<tag>-arm64-v8a`, with a `.sha256` and `.sha256.sig` beside each. The provisioner fails closed before APK replacement if the selected helper or proof is absent.
- Confirm panels report the new version and reappear in HA.
