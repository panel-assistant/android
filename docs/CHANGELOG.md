# Changelog

## v0.9.8-rc1 - 2026-09-15

This release candidate lets Panel Assistant 0.3.0 show each panel's own web interface inside the Home Assistant sidebar, and adds the panel side of Panel Assistant's optional native transport. Documentation has moved to panel-assistant.io. The changes below are since v0.9.7.

### Added

- **The web interface works inside the Panel Assistant sidebar.** Panel Assistant 0.3.0 shows each connected panel's own web interface in the Home Assistant sidebar. Embedded, it hides the header and panel switcher, follows the Home Assistant user's language and theme, and loads its styles and scripts through the sidebar. Panels on v0.9.7 or earlier show an unstyled page there, so update them to use the Panel Assistant 0.3.0 sidebar.

- **The panel side of Panel Assistant's native transport.** When the `native_entities` option is enabled in Panel Assistant, a panel can report state and accept commands over its own authenticated Home Assistant connection instead of MQTT. Commands follow the same order and approval rules as MQTT commands, and sensitive commands still need approval on the panel's screen. While Panel Assistant holds a panel's entities, the panel withdraws its MQTT discovery entities, and it announces them again when the integration releases them or its entry is removed. `GET /api/v1/panel-assistant/transport` reports the current state, and `POST /api/v1/panel-assistant/transport/release` hands a panel back to MQTT when no integration can; in Hardened mode a remote release needs approval on the panel. Without the option, MQTT stays in charge as before.

- **Groundwork for approving sidebar changes on Hardened panels.** A panel can now accept a routine change from the Panel Assistant sidebar without waiting for approval on its screen when the request is signed with a key Panel Assistant holds for that session. Operations such as exporting secrets still need approval on the panel's own screen, and unsigned requests still wait for approval as before. Panel Assistant 0.3.0 does not sign its requests yet, so with 0.3.0 nothing changes.

### Changed

- **Documentation now lives at [panel-assistant.io](https://panel-assistant.io).** The pages under `docs/` point there, the README and the web interface link to the site, and the translated copies of the documentation have been removed.

## v0.9.7 - 2026-09-13

0.9.7 makes panels get back to their dashboards faster, recover from Home Assistant outages by themselves and explain what they are waiting for when they cannot. It adds Home Assistant update entities, local discovery, proximity calibration wizard, a nine-language interface and camera streaming, and makes installs and rooted upgrades far easier to diagnose.

### Added

- **Proximity can be calibrated and used from the panel itself.** Guided setup separates presence from wave detection, calibrated presence can drive auto sleep, and a single hand approach can wake the display. Panels without usable proximity hardware do not show these actions.

- **The interface is available in nine languages.** English, German, French, Italian, Spanish and Simplified Chinese, plus early-access Dutch, Polish and Ukrainian. Automatic follows the browser or panel language, with English fallback where a string is not translated. The translations are machine-drafted.

- **Camera streaming and snapshots.** An off-by-default Camera setting serves a video-only H.264 stream at `rtsp://<panel>:8554/live` and a JPEG snapshot. Enabling it from Home Assistant needs approval on the panel, the camera stays closed unless a client is reading it, and a mandatory on-screen or LED indicator shows whenever it is open.

- **Sign-in and reauthentication can be handed to a phone** with a code that opens the panel's own sign-in setting.

- **Network diagnostics.** Runtime diagnostics, `/api/v1/status` and `/api/v1/diag` report Home Assistant response time separately from the network path, the IPv4 or IPv6 family in use, whether the built-in dashboard connected and why it failed, and a 24-hour Wi-Fi outage count. No addresses or network names are included.

- **Native Android navbar support** on panels with a verified native navbar, currently the Electron WF1589T.

- **Hardware support.** ZHICAI SMT1019 screen off, temperature, humidity and experimental proximity; an import-only community profile for the Portworld YC-SM10P; updated Lenovo ThinkSmart View and Raspberry Pi 4 KonstaKANG wake profiles; and the NSPanel Pro firmware index now includes 4.8.0.

- **Specific dashboard tabs.** Choose a tab path in guided setup or Configure. Scripted installs can set the dashboard with `--home-dashboard` and the entity filter with `--entity-filter` before the first load.

### Changed

- **Faster dashboard startup and recovery.** With the account default dashboard, the built-in renderer reopens the last verified dashboard while it checks Home Assistant's dashboard list in the background. Temporary failures retry with a visible countdown; problems that need a new sign-in or an update still wait for you.

- **Home Assistant restart status.** The built-in renderer shows when Home Assistant is stopping, starting or offline and clears the message when it is ready again.

- **Automatic IPv4 and IPv6 fallback.** MQTT and the Home Assistant WebSocket try the other address family when the first route does not connect, under the existing `Automatic`, `Prefer IPv4` and `Force IPv4` setting.

- **Clearer status and recovery screens.** Startup, setup and error pages identify themselves as ha-paneld, fit 480×480 panels, and say whether a problem will retry by itself or needs a repair in Configure, Entities, Home Assistant or Android System WebView.

- **A useful auto-brightness Sensitivity range.** Existing values are converted automatically, and the auto-brightness minimum can be set as high as 99%.

### Fixed

- **Dashboard entity filters survive upgrades, dashboard changes and restarts.** Filters saved before this release keep working, pins and exclusions survive a dashboard switch, and a panel that starts before Home Assistant retries its dashboard check instead of waiting for you. [Issue #113](https://github.com/maxlyth/ha-paneld/issues/113)

- **Android System WebView problems can be repaired without reinstalling ha-paneld.** An updated WebView is picked up by restarting the renderer, a panel with a pinned known-good WebView can reinstall it, and the screens name the fix that actually works.

- **Rooted upgrades and helper replacements say exactly what failed.** The installer checks the target before replacing the helper, stops a helper that will not exit, verifies the helper belongs to the APK, and reports that nothing changed when an upgrade did not start. [Issue #120](https://github.com/maxlyth/ha-paneld/issues/120)

- **Installation is more reliable across computers and panels.** Git Bash on Windows keeps panel-side paths literal and finds Android Build-Tools, the newest usable build-tools are preferred, slow package managers are no longer refused, a missing Java runtime is reported as such, and a time-zone mismatch between panel and computer is warned about. [Issue #24](https://github.com/maxlyth/ha-paneld/issues/24)

- **Screen and navbar.** A redundant Screen On no longer dims a lit panel, the reload button returns to your dashboard again, and the `Always on` navbar can be switched off again on newer Android panels. [Issue #137](https://github.com/maxlyth/ha-paneld/issues/137)

- **Smaller fixes.** Reboot requests are verified and fall back to the next method; temporary database-busy errors no longer leave storage marked failed; database maintenance can return unused pages to the filesystem; Smatek S9E relay and button-LED commands respond promptly; `auto_brightness_sensitivity` is accepted again by `POST /api/v1/config`; entity search reports its matches [Issue #114](https://github.com/maxlyth/ha-paneld/issues/114); sandboxed panels get root-helper guidance; and the Wi-Fi stability row and diagnostics dump agree.

## v0.9.7-rc5 - 2026-09-13

This release candidate makes provisioning verification reliable straight after the app restarts and adds a community profile for the Portworld YC-SM10P. The changes below are since v0.9.7-rc4.

### Added

- **An import-only community profile for the Portworld YC-SM10P.** It covers the 10.1-inch Rockchip RK3566 panel on Android 11 userdebug and records its verified screen-off route. It is not bundled or selected automatically; owners who can confirm the hardware can import it.

### Fixed

- **Provisioning no longer fails a healthy panel whose web server is still starting.** Verification that runs straight after the app restarts retries the configuration schema read when no answer arrives, within a 12 second limit, instead of stopping on the first attempt. A malformed answer still fails immediately.

## v0.9.7-rc4 - 2026-09-12

This release candidate makes panel discovery, sleep and proximity controls easier to use, adds three early-access interface languages and records more of what the panel actually did instead of what its profile expected. The changes below are since v0.9.7-rc3.

### Added

- **ha-paneld and the Companion app now appear in Home Assistant as update entities.** Each entity shows the installed version, the latest release on the panel's update channel, release notes and installation progress. Installing from Home Assistant uses the same checked path as the Install page and installs only the newer release shown by the entity. A missing Companion app can be installed, a Play Store build is reported without offering an update, and a build above the device profile's safety cap is shown as unsupported with the capped release offered instead. The existing update buttons remain for this release so automations can move to `update.install`; they now ignore MQTT payloads that are not Home Assistant button presses.

- **Panels can announce themselves for privacy-safe local discovery.** Compatible local clients can find ha-paneld over mDNS without the announcement exposing a reusable panel identity. The service keeps a stable connection identity while it is running, then starts fresh after the app is reinstalled or its state is cleared.

- **Auto sleep now has its own card on the Configure page.** Its source, switch, status, history and setup guidance are no longer mixed into the general Behaviour card. The card stays in place while the rest of Configure refreshes, which avoids losing its chart position or a control you are using.

- **Compatible panels can calibrate and use proximity from the panel itself.** Guided setup separates presence detection from wave detection, keeps readings visible on compact screens and can use calibrated presence as the automatic sleep source. A single hand approach can wake the display before touch, while panels without usable proximity hardware do not show actions they cannot perform.

- **Dutch, Polish and Ukrainian are available as early-access interface languages.** These machine-drafted translations can be selected alongside English, German, French, Italian, Spanish and Simplified Chinese. Individual strings still fall back to English where a translation is unavailable.

- **Kiosk lock can exempt one chosen Companion package.** An operator-named companion app can remain in the foreground without the kiosk immediately reclaiming the screen, while every other package keeps the existing lock behaviour.

### Changed

- **Named Home Assistant themes are now available from Configure and survive dark-mode changes.** Dashboard theme selection is no longer limited to Follow Home Assistant, Dark and Light. On older Android panels, changing dark mode now preserves the selected theme and its custom colours instead of silently replacing them with the default light or dark theme.

- **Touch sound now follows the saved panel setting.** A vendor setting or another app changing Android's global sound flag no longer changes the value reported by ha-paneld or creates an unrelated pending change on the next save. Changes from Home Assistant are saved before the panel tries to apply them, and a failed external change no longer leaves a superseded request waiting forever.

- **The camera indicator settles down during a camera session you meant to leave running.** It still starts as a bright red arc flashing about once a second, because an unexpected camera opening should be impossible to miss. Over the following minutes the gap stretches to about a minute and the light dims. A reopened camera or newly awakened display returns it to the prominent warning, while the screen-off status LED and the rule that the camera cannot open without an indicator are unchanged.

- **The community wake profiles now reflect confirmed device behaviour.** The Lenovo ThinkSmart View profile records Home Assistant screen off and on for the reported firmware. The Raspberry Pi 4 KonstaKANG profile records touch wake only with its required Magisk-installed input mapping, rather than presenting it as a stock capability.

- **More translated guides ship with the project.** The supported documentation now covers security, performance, hardware, provisioning safety, adaptive display behaviour, text-to-speech and the API, retaining English where a reviewed translation is not available.

### Fixed

- **Runtime diagnostics now tells the truth about how the screen was switched off.** Status shows both the route declared by the device profile and the route actually selected by the panel, with the reason for any fallback. A profile asking for brightness-only sleep no longer looks as though it used a root or helper route simply because that route later became available.

- **A backup taken from a panel that could not read its own stored state now says so.** The usable configuration is still preserved, but the Install page, restore preview and restore result no longer present the bundle as though it contained panel state. A backup from a panel with genuinely nothing stored is unchanged, and existing backups restore as before.

- **Sandboxed panels without helper-backed hardware now get root-helper guidance.** On a panel where the app cannot use `su` and the profile declares no helper-backed LED, screen, sensor or button, the provisioning plan now explains that display sizing, CPU control, screenshots and performance readings need the root helper when a root route is available.

- **Updating from Git Bash on Windows now finds Android Build-Tools, and a missing tool is reported as one.** The installer recognises `.bat` and `.cmd` tools and Windows-style `ANDROID_HOME` paths. When `apksigner`, `aapt` or `aapt2` really is missing or cannot run, the update stops with a message naming the tool and how to fix it instead of blaming the APK or panel database.

- **Installation no longer refuses a slow panel while checking whether ha-paneld is already present.** The safety check now allows enough time to read both the application and Android framework package paths from an older but responsive package manager without weakening the validation.

- **Automatic MQTT connection can recover through the other address family.** When a Home Assistant hostname has both IPv4 and IPv6 addresses and the first route is stale or unreachable, the panel can continue through a working sibling address instead of remaining disconnected.

- **Prerelease update checks stay on the prerelease channel.** A panel following release candidates no longer treats a stable-release lookup as the answer when deciding whether a newer candidate is available.

## v0.9.7-rc3 - 2026-09-04

This release candidate adds a six-language interface, experimental camera streaming and clearer Home Assistant network diagnostics. It also improves recovery when an update changes a pinned device profile or leaves a dashboard waiting for an entity-filter decision. The changes below are since v0.9.7-rc2.

### Added

- **The diagnostics now say which address family the panel is actually using to reach Home Assistant.** When a hostname resolves to both an IPv4 and an IPv6 address and one of them silently goes nowhere, the panel quietly connects over whichever works and everything looks perfectly healthy — which is exactly what makes that fault hard to find. `/api/v1/diag` and `/api/v1/status` now report the family in use, in every state, so the starting point for diagnosing it is visible at a glance. No address, hostname or network name is included.

- **The UI is now in six languages.** Configure → System → Interface language offers English, German, French, Italian, Spanish and Simplified Chinese. Leave it on Automatic and the panel picks up the language from your browser or the panel itself. Shared navigation, setting labels, help and supported runtime messages follow the same choice, with per-string English fallback where a translation is unavailable. This was all done with AI so I can't vouch for the quality.

- **Experimental camera streaming and snapshots.** The off-by-default Camera setting serves a shared video-only H.264 stream at `rtsp://<panel>:8554/live` and a JPEG from `GET /api/v1/camera/snapshot.jpg`, with configurable resolution, frame rate, bitrate and exposure. Enabling it from Home Assistant asks for approval on the panel itself. Android asks for camera permission only after the feature is enabled; microphone access is not used, and the camera stays closed unless a client is reading it. A pulsing red on-screen indicator, or the status LED while the screen is off, is mandatory whenever the camera is open; if the indicator cannot be shown, the camera remains closed. Home Assistant receives an optional Camera switch and on-demand image entity through MQTT, while status and diagnostics report camera state without carrying image data.

- **Network diagnostics separate Home Assistant response time from the network path.** Lightweight probes on the authenticated Home Assistant WebSocket measure the complete request path and track latency, jitter and missed replies. Separate intermittent ICMP bursts measure the network path when the panel and server support them; unanswered echoes are not reported as loss until at least one echo has succeeded. Missed WebSocket replies, not slow replies alone, can raise the dashboard warning and touch-through on-panel chip. Both measurements appear in Runtime diagnostics, `/api/v1/status` and `/api/v1/diag` without exposing the Home Assistant address, Wi-Fi name or individual samples.

- **Dashboard theme can be chosen on the panel.** Configure → Built-in renderer offers Follow Home Assistant, Dark and Light without changing the Home Assistant account's saved theme. An explicit Light or Dark preference in Home Assistant still takes priority, and Runtime diagnostics now explains when that overrides the panel's choice. [Issue #61](https://github.com/maxlyth/ha-paneld/issues/61)

- **Sign-in and reauthentication screens can hand the job to a phone.** A phone-readable code opens the panel's own Home Assistant sign-in setting when the panel has a reachable address, so credentials need not be typed on the wall panel.

- **A panel with a pinned, known-good Android System WebView can repair it directly.** Where ha-paneld has verified installation capability, recovery offers to download and install that build, then restart ha-paneld once to bind it. When automatic repair is unavailable, the screen explains whether the missing requirement is a pinned build or installation access.

- **Release downloads include signed installation metadata.** A bounded descriptor binds the APK's checksum, size, signer, package, version, supported architectures, minimum Android version and database compatibility to the release, so installers can check compatibility before downloading and installing the APK.

### Fixed

- **An account-default dashboard can recover its working entity filter after an update.** The retained filter resumes only when its complete catalogue remains valid; otherwise the explicit safety question remains. A completed scan waiting only for that decision checks for dashboard changes with a widening interval instead of re-reading the whole entity catalogue.

- **Pinned built-in device profiles advance safely when their bundled revision changes.** ha-paneld validates the replacement, rolls back to the retained revision if activation fails, and falls back explicitly rather than silently changing pinning mode. Imported profiles are never replaced by a built-in profile with the same name.

- **A redundant Screen On command no longer dims a panel that is already lit.** A command without brightness preserves the active level, while an explicit brightness or a genuine wake still behaves as requested. [Issue #137](https://github.com/maxlyth/ha-paneld/issues/137)

- **Git Bash on Windows no longer rewrites panel-side Android paths.** The installer keeps paths on the panel literal while preserving the conversion needed for files on the installation computer. macOS and Linux are unchanged. [Issue #24](https://github.com/maxlyth/ha-paneld/issues/24)

- **Rooted upgrades explain why a helper replacement cannot proceed.** Before replacement, the installer checks the target filesystem, free space, ownership, permissions, SELinux context and staged checksum. A failed copy carries the panel-reported error, and a replacement refused before mutation leaves the app and helper unchanged. [Issue #120](https://github.com/maxlyth/ha-paneld/issues/120)

- **Status distinguishes an old dashboard check from an old application build.** Runtime diagnostics names the age of the dashboard check, while `/api/v1/status` also reports application uptime and time since installation or update.

- **Hardened mode now protects a dashboard reload sent over MQTT.** The published list of operations that need approval at the panel already named it and the HTTP route already enforced it, but the Home Assistant command did not. Panels in the default Relaxed mode reload without a prompt exactly as before.

- **A panel with a camera no longer offers Home Assistant a snapshot it cannot turn on.** The snapshot image entity followed the hardware rather than the camera switch, so a panel that had never exposed the switch published a permanently unavailable image beside no control able to arm it. Both now appear together or not at all.

- **Database maintenance can return unused pages to the filesystem.** Reclamation is bounded, defers when a reader prevents the checkpoint, and reports the specific database operation that failed rather than a generic storage failure.

### Upgrade notes

- Use the normal installer for an in-place update from v0.9.7-rc2. No configuration reset or Home Assistant entity cleanup is expected. This remains a release candidate; camera support is experimental, and the existing S9E and SMT1019 hardware-testing limits still apply.

## v0.9.7-rc2 - 2026-08-26

### Fixed

- **An entity filter you chose by hand keeps working after the upgrade.** The record that says which dashboard a filter belongs to now names the dashboard rather than the individual view, and a filter saved before this release named the view. On upgrade the filter read as belonging to nothing, reported itself as off, and the panel went back to subscribing to every entity Home Assistant would show it: the exact load the filter exists to avoid, with nothing on screen to say so. Stored filters are corrected on first start.

- **A panel that restarts before Home Assistant no longer waits for you.** While it checks which entities your dashboard uses, the panel holds the dashboard rather than open it unfiltered. That check could not finish while Home Assistant was still starting, and it was only retried once, so a panel that came back first could sit on a waiting or problem screen long after Home Assistant returned. It now keeps retrying on a widening interval and opens as soon as the check succeeds.

- **A failed helper replacement no longer tells you to repair a panel it never touched.** The installer stops the old helper before replacing anything, so a panel where that fails still has its previous helper and app exactly as they were. It used to report a failed install, attempt to undo work it had not done, and end by telling you to restore the helper by hand. It now says the upgrade did not start and that nothing changed.

- **The reload button returns the panel to your dashboard again.** A home dashboard saved before this release could be stored as a full address rather than a path, because the setting accepted anything then. The reload command built a link out of the whole address and went nowhere. Stored values are corrected on first start, and a saved address pointing at a different server is left alone rather than silently repointed.

- **A backup taken with an earlier release restores again.** A restore is all or nothing, so a single home dashboard saved in the older form failed the whole archive, precisely when you need it. That value is now read the same way an upgrade reads it.

- **The screens that say your web viewer is too old name the fix that works.** They asked you to update Android System WebView, which is no help on a panel already carrying the newest build its vendor ships; reinstalling the same build is what repairs a damaged one, and the screens now say so. The screen you reach when nothing can render also stopped claiming that no dashboard app can work, which contradicted the screen beside it.

- **Installing from a computer with more than one Android SDK build-tools version works again.** The installer picked the oldest usable one, and an older `aapt` that cannot read a modern APK made it report that the package could not be identified. It now prefers the newest that runs, and a tool you put on your PATH still wins.

- **`auto_brightness_sensitivity` is accepted again by `POST /api/v1/config`.** The setting was replaced by one on a different scale, and because that request is applied as a whole, sending the old name discarded every other setting in the same call. The old name is now accepted and converted, so an automation written against an earlier release keeps working.

- **Updating Android System WebView now takes effect without reinstalling ha-paneld.** A WebView provider binds once per process, so a panel showing "Secure dashboard bridge unavailable" stayed on that screen however many times WebView was updated or Retry was pressed. The panel now notices the newly installed provider and restarts its renderer to bind it, and no longer abandons that pending restart while the verdict is briefly out of view during the switch.

- **Rooted upgrades no longer stall on a helper that will not exit.** The installer now stops the old helper through Android's own service control, escalates if the process ignores the first request, and if it still cannot retire it reports exactly which process survived and what Android believes the service state to be. Every other step of a helper replacement that used to fail with one generic message now names itself, so a failed install tells you what failed. [Issue #120](https://github.com/maxlyth/ha-paneld/issues/120)

- **A release's helper asset must match what the release says it is.** The installer reads the identity stamped inside the downloaded helper and refuses to stage it if it disagrees with the identity the signed provisioner records, so a mismatch is caught before the helper is staged or replaced rather than discovered after the swap and rolled back. The standalone helper installer reads the staged binary's own identity the same way, so a helper staged from a different build can no longer fail a good install afterwards.

- **Scripted installs no longer refuse `--home-dashboard` on a panel already using the built-in renderer.** The check used to require the renderer to be named explicitly, so a panel on the default selection was told it was not using a renderer it was in fact using. The panel now reports which renderer it resolves to and the installer asks that instead.

- **Entity catalog search tells you what it found.** Typing in the search box on the Entities page used to change the tables silently, and the matches often sat below the fold, so a search that worked looked like it had done nothing. A status line now acknowledges each keystroke and reports the match count for every section, and the first section with matches scrolls into view once. [Issue #114](https://github.com/maxlyth/ha-paneld/issues/114)

- **Backups no longer assume a Companion login exists.** `POST /api/v1/backup` read a missing `include_companion` as `true`, so the simplest backup request failed on any panel without the Home Assistant Companion. Omitting the parameter now means "include it if a supported Companion is installed"; asking for it explicitly on a panel with none still refuses, and an unrecognised value is now rejected instead of quietly producing a config-only archive.

- **Dashboard filter advisories no longer treat a template as a missing entity.** ha-paneld deliberately does not evaluate filter templates; Home Assistant renders them itself. The advisory now says exactly that, and which entities a template does and does not add, instead of reporting the template's own condition entities as a discovery failure. [Issue #113](https://github.com/maxlyth/ha-paneld/issues/113)

- **Status screen actions look like the buttons they are.** Actions on the panel's own status screens now use the same control style as the standing screen, with real padding and spacing between a pair of actions and clear separation from the surrounding text.

- **Recovery screens now say what went wrong and where to fix it.** Setup, sign-in, WebView, dashboard-selection, and entity-filter failures now distinguish automatic retries from repairs in Configure, Entities, Home Assistant, or Android System WebView, so Retry is only shown where it can help.

- **The Wi-Fi stability row and the diagnostics dump agree.** The Dashboard row derived its wording from the raw outage count while `/diag` used the chronic classification, so a panel whose history had been compacted could show a low number on its own card while a pasted report called it chronic. Both now use the same classification, and a saturated count reads as the floor it is.

## v0.9.7-rc1 - 2026-08-19

### Panels reach their dashboards faster and recover from Home Assistant outages

0.9.7-rc1 includes a lot of changes. The most noticeable improvement for many users will be how quickly the built-in renderer gets back to the dashboard. When the account default is selected, it can reopen the last verified dashboard while it refreshes Home Assistant's dashboard list in the background instead of making you wait for that work to finish.

I also wanted to improve what happens when Home Assistant is unavailable. A wall panel should recover by itself when it can and tell you what it is waiting for when it cannot. The built-in renderer can tell the difference between Home Assistant restarting and an ordinary connection failure, retries problems that may clear by themselves and reports whether the dashboard itself is connected.

Several other problems turned up while I was working on this. You can choose a specific dashboard tab, and scripted installs can select the dashboard and entity filter before the first load. Manual entity subscription pins and exclusions are no longer lost when you change dashboards. MQTT and Home Assistant connections try IPv4 or IPv6 if the first one does not work.

This is still a release candidate. I have tested it on the hardware available to me, but I do not own an S9E and the SMT1019 proximity support has not run end to end on a real panel. Please report any problems with panels recovering after Home Assistant goes offline, rooted upgrades, S9E controls or SMT1019 support.

### Added

- **Specific dashboard tabs.** You can enter a tab path in guided setup or Configure. ha-paneld warns if the signed-in account cannot currently see the dashboard containing that tab, but still lets you save it so a panel can be prepared before the dashboard is ready. Scripted installs can make the same choice with `--home-dashboard` and set entity filtering with `--entity-filter` before the first dashboard load.

- **Built-in dashboard and Wi-Fi diagnostics.** The panel, diagnostics dump and `GET /api/v1/status` show whether the built-in dashboard connected, whether a retry is pending and why a connection failed. A separate 24-hour Wi-Fi outage count helps distinguish panel network problems from Home Assistant or MQTT failures. Panels with no recorded outages show no warning.

- **APK downloads from HTTPS addresses.** The Install page can download an APK and check it before installation. Hardened security mode requires physical access to the panel. Someone must approve the download on the panel's screen; it cannot be approved remotely. Cancelling stops the download. If an APK has been downloaded and checked but not installed, it survives a page or app restart and can be discarded or replaced without leaving the Install page stuck.

- **Native Android navbar support.** Panels with a verified native Android navbar can use it instead of ha-paneld drawing another bar. This is currently enabled for the Electron WF1589T. Hiding Android's system bars also hides the native navbar.

- **More ZHICAI SMT1019 support.** On panels where ha-paneld has the required access, the SMT1019 can turn its screen fully off, report room temperature and humidity, and use experimental proximity detection. The raw temperature and humidity values have been confirmed, but I have not checked their accuracy against a reference sensor. I have not yet been able to test proximity detection on the panel itself, so reports on [issue #106](https://github.com/maxlyth/ha-paneld/issues/106) would be useful.

- **A time-zone warning during installation.** The installer warns if the panel and the computer running it use different time zones. The warning does not block installation or change either clock. Equivalent time-zone names are accepted, and no warning is shown if either zone cannot be read reliably.

### Changed

- **Faster dashboard startup and recovery.** When the account default dashboard is selected, the built-in renderer can return to the last verified dashboard without waiting for Home Assistant's dashboard list to refresh. It checks that choice in the background, removing an unnecessary delay from ordinary startup and helping the panel recover sooner after an outage. Temporary failures while checking Home Assistant or starting the dashboard connection are retried with a visible countdown. Problems that need a new sign-in or a software update still wait for you.

- **Home Assistant restart status.** The built-in renderer shows when Home Assistant is stopping, starting or offline and removes the message when the server is ready again. Administrator accounts receive the full stopping and starting sequence. Other accounts can use configured MQTT availability to show that Home Assistant is offline and back online.

- **Automatic IPv4 and IPv6 fallback.** If MQTT or the Home Assistant WebSocket cannot connect over IPv4 or IPv6, it tries the other one. The existing `Automatic`, `Prefer IPv4` and `Force IPv4` setting applies to both connections, and MQTT remembers which one worked for the next reconnect. Ordinary HTTPS requests are unchanged.

- **A useful auto-brightness Sensitivity range.** At zero, brightness follows the learned pattern alone. Higher values respond more strongly to the difference between the learned and measured light levels. Existing values up to 50 are converted to keep the same effective response, while higher values become full sensitivity because the old top half exaggerated changes. The auto-brightness Minimum level can be set as high as 99%; manual brightness can still be set lower.

- **Clearer full-screen status pages.** ha-paneld's startup, setup and error messages clearly identify themselves instead of looking as though they came from Home Assistant. They follow the selected theme, fit 480×480 panels and remain usable with larger text. Progress updates no longer make the top of the page jump.

### Fixed

- **Dashboard entity choices survive a dashboard change.** Pinned and excluded entities remain visible for review after you switch dashboards. Automatic filtering also waits until the new dashboard has been checked instead of briefly applying the entity list from the previous one.

- **Reboot verification.** ha-paneld confirms that a requested reboot actually happened and tries the next available method if it did not. A stuck command no longer blocks the remaining methods, and failure is reported after they have all been tried.

- **Temporary database-busy errors.** A temporary busy error no longer leaves storage permanently marked as failed. ha-paneld retries the health check and clears the warning only after the database passes an integrity check and a later setting write succeeds.

- **Smatek S9E controls.** Relay and button-LED commands should respond promptly, and rapid changes should no longer send an older state to Home Assistant. I do not own an S9E on which to confirm this fix, so reports are welcome.

- **The `Always on` navbar can be changed again on newer Android panels.** Updating fixes a problem that could leave earlier versions unable to switch back to `Off`. On panels where Android cannot reserve space for the bar, it is drawn over the bottom of the dashboard. Controls underneath it cannot be tapped, so use `Native`, swipe-reveal or `Off` if the overlap matters.

- **Clearer installation and rooted-update failures.** A clean install now distinguishes a missing app from an Android package manager that did not answer. Rooted updates try the root methods available on the panel, replace stale helper processes and verify that the replacement helper belongs to the selected APK. The installer now gives the correct recovery steps when `/system` is read-only, and a missing Java runtime is no longer reported as a bad APK signature. Package, helper and signature checks remain enforced.

- **Home Assistant Companion choices in the Dashboard picker.** Installed Companion variants no longer disappear after you select the built-in renderer. The other renderer choices are unchanged.

- **A more readable ambient-light history chart.** Today, yesterday and two days ago remain separate lines. Earlier days appear as a shaded range. This only changes the chart display; stored readings, the learned pattern and brightness decisions are unchanged.

### Docs

- **The known NSPanel Pro firmware index includes 4.8.0.** The complete index is available in the repository, and automated checks look for newly published vendor versions. The hardware guide records where each file came from and which versions have actually been tested on hardware.

### Upgrade notes

- Use the normal installer for an in-place update. No configuration reset or Home Assistant entity cleanup is expected. Existing auto-brightness settings are converted automatically.

- If you previously changed the IPv4 or IPv6 setting for MQTT, check it after upgrading because it now also applies to Home Assistant WebSockets.

## v0.9.6 - 2026-08-02

0.9.6 is a substantial update centred on guided onboarding, Home Assistant-native dashboard integration, presence-aware sleep, safer upgrades and backups, and better diagnosis and recovery for always-on panels. Existing v0.9.5 installations can update in place without resetting configuration or cleaning up Home Assistant entities.

### Added

- **Guided first-run setup is available on the panel or at `/setup` from another device.** It covers renderer readiness, Home Assistant browser sign-in, dashboard selection and entity filtering before the first dashboard load, and blocks incompatible browser engines with recovery guidance instead of showing a broken dashboard.

- **Auto sleep can combine touch and proximity activity with selected Home Assistant presence devices.** It learns suitable delays and shows the reason for its current decision. You might get a better outcome with an automation written in Home Assistant, but this low-configuration panel-side solution might work for many users. The panel and Home Assistant sensors should share the same Home Assistant area.

- **Remote panel control.** You can now simulate panel taps from the web UI, but only in Relaxed security mode, for obvious reasons. Click the screenshot in the Dashboard tab to access remote control. This is useful for navigating dashboard tabs remotely while trying to exercise full coverage of the entity filter. It is not designed to be a real-time, interactive, multi-touch terminal.

- **Use Home Assistant's native dashboard interface.** I discovered during the 0.9.6 work that Home Assistant has a new external-bus interface that covers authentication and dashboard lifecycle capabilities we had previously built ourselves. For future compatibility, I chose to use Home Assistant's native interface where possible. This means the built-in renderer now requires Home Assistant 2026.4.2 or newer.

- **Storage, SQLite and Android power-safety monitoring now identify conditions that could leave a panel unreliable.** The Dashboard, diagnostics, Home Assistant and installer surface actionable warnings. Android power settings can be repaired with verification where the platform permits it, with specific manual guidance otherwise.

### Changed

- **The built-in renderer is now the default.** Automatic renderer selection now prefers the built-in renderer because the performance gains over the Home Assistant Companion app are so transformative for most users that the remaining feature gap (multi-server support, Assist, notifications) is mostly irrelevant.

- **Dashboard entity filtering now understands more of what modern Home Assistant dashboards load.** It follows dynamic and custom-card dependencies more accurately, warns when a dependency cannot be resolved safely, and avoids claiming a filter is complete when it may hide something the dashboard needs.

- **Log shipping now supports syslog over TCP or UDP and HTTP, with destination testing and clearer failure reporting.** Fresh configurations and an explicit legacy `syslog` selection use TCP, while upgraded configurations that never stored a transport retain their previous UDP behaviour. If you relied on implicit defaults, review Configure after upgrading.

- **Configuration and recoverable application state now use a more durable SQLite-backed model.** Upgrade directly from v0.9.5; installations older than v0.9.5 must first upgrade to v0.9.5 if their configuration must be preserved. New complete backups can include settings, profiles, eligible Companion login data and eligible same-panel durable application state, but entity catalogs and learned proximity or ambient histories are rebuilt after restore, so create a fresh `.hpb` after upgrading. An encrypted backup download is withheld if its sensitive temporary plaintext archive cannot be removed.

- **Ordinary upgrades now use defense-in-depth recovery.** The installer always attempts an automatic settings export; with a usable root route it also requests an orderly shutdown and verifies one coherent database copy, with one legacy SQLite fallback for older builds. On unrooted panels, only the settings export can be copied off-panel automatically. An unavailable or invalid automatic copy is removed and reported prominently while package replacement continues with existing app data preserved; these automatic copies are not substitutes for a user-verified backup.

- **Local `--apk` provisioning and fleet installs now require Android Build-Tools with `apksigner` and `aapt` or `aapt2`.** Fleet mode rejects `--reset-config`, `--export`, `--id` and device-scoped `--restore` before starting workers; `--restore-fleet` remains supported for portable fleet configuration.

- **`--reset-config` is deliberately irreversible and creates no backup.** It requires the user to type `RESET` as confirmation, erases settings together with learned entity, proximity, ambient and revision state, and leaves the app, helper and unrelated applications installed. Fleet updates refuse this option. Create and verify a complete `.hpb`, or run a separate `--export FILE` when settings alone are sufficient, before resetting.

### Fixed

- **Failed settings writes no longer appear successful or replace working configuration.** A setting becomes visible only after durable storage succeeds, while a failed save leaves the previous value and active behaviour intact.

- **LAN discovery now recovers from silently stalled mDNS responders and network addresses that arrive late during boot.** Recovery uses bounded retries and reports when automatic repair is exhausted instead of requiring an unexplained app or network restart.

- **Dashboard and Configure pages remain stable on small panels.** Restored Dashboard placement no longer drifts to the wrong cards, narrow-screen headers no longer jump shortly after loading, and automatic-sleep refreshes preserve controls, focus, chart position and scroll state.

- **Provisioning recovers more reliably from slow first starts, Android-stopped applications and interrupted rooted upgrades.** It waits within documented bounds, avoids configuration writes or Home Assistant token creation before the app answers, recovers cleanly when a rooted upgrade is interrupted and no longer rejects viable APK updates because of fixed storage margins or optional housekeeping copies.

## v0.9.5 - 2026-07-20

**ha-paneld is easier to sign into, adapts better to each panel's environment and recovers more reliably from routine changes.** It also makes sensitive maintenance safer without taking control away from existing installations.

### Added

- **Sign a panel in to Home Assistant from a remote browser** — Configure creates a short-lived link that can be opened normally or copied into a private window for another user, avoiding credential entry on the panel keyboard. Fresh installations print the next-step URL, and Configure shows the connected user’s display name.

- **Auto-brightness adapts to each room** — it learns from up to seven days of panel or Home Assistant light readings, follows the normal daylight pattern and responds to changes such as a room light switching on. You can set the minimum automatic level, preview sensitivity changes and pause automatic control after a manual brightness change.

- **Proximity sensors now set themselves up automatically** — ha-paneld learns empty-room and approaching-person readings, including whether the sensor rises or falls. Once it has enough reliable readings, Home Assistant receives occupancy and a 0–100 proximity level. A guided three-wave setup can teach it immediately, while brief fluctuations are ignored.

- **Optional Hardened security mode requires approval on the panel for sensitive remote actions** — software changes, credential export or restore, profile activation, reboot and other sensitive maintenance wait for a one-time approval in the Android app. They cannot be approved remotely. Existing installations remain in Relaxed security mode unless Hardened security mode is deliberately enabled on the panel.

- **You can measure whether dashboard filtering makes a panel faster** — a comparison records the same dashboard with filtering off and on, so you can compare response time rather than relying on how it feels. It can still collect useful results if the dashboard becomes unresponsive. [Discussion #10](https://github.com/maxlyth/ha-paneld/discussions/10)

- **Obsolete learned dashboard entities can be cleared from the Entities tab** — a confirmed reset clears learned and manually included or excluded entities while the current filter continues working until a fresh scan completes. [Issue #50](https://github.com/maxlyth/ha-paneld/issues/50)

- **The software navigation bar has a dedicated Dashboard action** — it opens the configured dashboard without reloading it. Reload remains available separately if recovery is needed. [Issue #43](https://github.com/maxlyth/ha-paneld/issues/43)

- **NSPanel Pro Zigbee joining can be requested from Configure** — once joining is enabled in ZHA or Zigbee2MQTT, an unjoined panel can try joining again without a restart.

- **Advanced setup no longer requires cloning the repository** — the signed release installer can configure, verify, back up or restore a panel using `install.sh --provision`.

### Changed

- **Kiosk-enabled panels go directly to their dashboard during routine startup** — the built-in renderer shows the QR/configuration screen once after each ha-paneld version change, while a launchable external renderer goes directly to its dashboard. Admin Launcher and recovery states still retain the configuration screen. [Issue #31](https://github.com/maxlyth/ha-paneld/issues/31)

- **Configure and Install are easier to use on compact panels** — related settings use shorter cards, Save changes appears only after a setting has changed, and setup tools such as display sizing, vendor packages and backup/restore are grouped on Install.

- **Wake on wave now requires a deliberate gesture** — the display wakes only after a complete wave towards and then away from the panel. Setup, testing and standing near the panel no longer wake it accidentally; touch-to-wake remains available while it is learning.

- **Editable YAML files now define all hardware profiles** — the editor suggests valid choices, prevents profiles for different hardware from being activated, and keeps unofficial reflashed-device profiles in the community catalog rather than presenting them as built-in support. [Issue #28](https://github.com/maxlyth/ha-paneld/issues/28)

- **Dashboard diagnostics identify busy Home Assistant entities more clearly** — they show which three entities sent the most updates and data during the past hour.

- **Panel information is more useful** — runtime diagnostics report app storage plus verified-boot and bootloader status without configured names; profile views show clearer processor details and useful reference links.

### Fixed

- **Panels are less likely to become unavailable after settings, network or helper changes** — replaced tasks and MQTT connections now shut down cleanly, and losing privileged screen control no longer leaves ha-paneld permanently unresponsive.

- **LED and button-backlight controls now reach the hardware reliably** — controls no longer return to off while brightness is changing, and button-backlight entities use Home Assistant’s normal light icon.

- **Installer progress and failures are clearer** — named stages show what is happening, backups are checked before connecting, stalled downloads and installs time out cleanly, and panels with insufficient system space receive a clear explanation. [Issue #44](https://github.com/maxlyth/ha-paneld/issues/44), [Issue #46](https://github.com/maxlyth/ha-paneld/issues/46)

- **Adaptive-brightness settings are validated before saving** — unreadable, missing or non-numeric Home Assistant light entities leave the current source and other pending settings unchanged, while invalid values show the supported range and an actionable error.

- **Wake on wave can be taught before a proximity sensor has identified how it works** — simple on/off and graduated sensors can complete guided learning, while missing or unknown sources remain safely disabled. Relearning or changing the sensor no longer leaves wake-on-wave incorrectly enabled after a restart.

- **Ethernet-connected panels no longer expose unavailable Wi-Fi diagnostics** — Wi-Fi network and signal entities appear only while Wi-Fi is the active connection, and stale values are removed when it changes to Ethernet. [Issue #21](https://github.com/maxlyth/ha-paneld/issues/21)

- **Restoring a backup no longer changes untouched vendor Zigbee settings**, including when an older backup is restored. [Issue #48](https://github.com/maxlyth/ha-paneld/issues/48)

- **Hardened security mode no longer reuses credentials after a Home Assistant or MQTT server change** — enter credentials for the new server or the old secret is cleared.

- **Install and Configure report the real outcome without discarding newer edits** — failed display-size changes are no longer shown as successful, and settings appear only where they apply.

- **CPU and temperature sensors explain when the installed helper is too old** instead of silently disappearing. [Issue #21](https://github.com/maxlyth/ha-paneld/issues/21)

- **Disabled features stop consuming resources** while temporarily unavailable sensors retry at a controlled rate and repeated diagnostic requests share one refresh.

- **Live Sensors shows brightness as a percentage** while retaining the 0–255 value for diagnostics. [Issue #30](https://github.com/maxlyth/ha-paneld/issues/30)

- **Panel capability lists omit hardware known to be absent** while Generic profiles retain discovery guidance for unknown hardware.

### Upgrade notes

- Existing panels remain in Relaxed security mode. Enable Hardened security mode only from the Android app’s Configure toolbar on the panel after disabling classic network ADB and Android Wireless debugging; remote tap injection remains unavailable until the panel returns to Relaxed security mode.

## v0.9.5-rc2 - 2026-07-20

### Added

- **Home Assistant sign-in can be completed from an administrator's browser** — Configure creates a short-lived link that can be opened normally or copied into a private window for another user, avoiding credential entry on the panel keyboard. Fresh installations print the next-step URL, and Configure shows the connected user's display name when Home Assistant provides one.
- **Adaptive brightness can start with existing Home Assistant history** — selecting a Home Assistant illuminance sensor brings in up to seven days of recent readings instead of waiting for new readings to accumulate.

### Changed

- **Kiosk-enabled panels go directly to their dashboard during routine startup** — the built-in renderer shows the QR/configuration screen once after each ha-paneld version change, while a launchable external renderer goes directly to its dashboard. Explicit Admin Launcher access and recovery states still retain the configuration screen.[^issue-31]

### Fixed

- **Wake on wave can now be taught before a proximity sensor has identified how it works** — both simple on/off and graduated sensors can complete guided learning, while missing or unknown sources remain safely disabled. Relearning or changing the sensor no longer leaves wake-on-wave incorrectly enabled after an app restart.
- **Invalid adaptive-brightness values are caught before saving** — Configure now shows the supported range and the server's actionable error instead of failing with a generic HTTP 400 response.
- **Ethernet-connected panels no longer expose unavailable Wi-Fi diagnostics** — Wi-Fi network and signal entities now appear only while Wi-Fi is the panel's active connection, and stale values are removed when it changes to Ethernet.[^issue-21]

[^issue-31]: User follow-up: [#31 — Load native renderer when ha-paneld starts](https://github.com/maxlyth/ha-paneld/issues/31).
[^issue-21]: User follow-up: [#21 — No CPU/Temp sensors reporting](https://github.com/maxlyth/ha-paneld/issues/21).

## v0.9.5-rc1 - 2026-07-19

**This release candidate makes panels more adaptive, more recoverable and easier to manage.** Auto-brightness and proximity no longer need tedious manual configuration and now tune themselves automatically using panel-local environmental data. Optional Hardened mode requires physical access: sensitive remote actions wait for approval on the panel's screen and cannot be approved remotely. Panels also recover more reliably after settings, network or helper changes.

### Added

- **Auto-brightness adapts to each room** — it learns from light readings collected over the past seven days, follows the normal daylight pattern and reacts to changes such as a room light switching on. It can use the panel's own sensor or a light-level sensor in Home Assistant, lets you set the minimum automatic level and preview sensitivity changes, and pauses after a manual brightness change until automatic control is resumed.
- **Proximity sensors now set themselves up automatically** — ha-paneld learns what the sensor reports when the room is empty and when someone approaches, including whether the reading rises or falls. Once it has enough reliable readings, Home Assistant receives occupancy and a simple 0–100 proximity level. A guided three-wave setup can teach it immediately, while brief sensor fluctuations are ignored instead of causing false presence.
- **Optional Hardened mode requires approval on the panel for sensitive remote actions** — software changes, credential export or restore, profile activation, reboot and other sensitive maintenance require a one-time approval from the Android app's Configure toolbar. They cannot be approved remotely. A subtle shield identifies affected web actions even before Hardened mode is enabled. Existing installations remain in Relaxed mode unless Hardened mode is deliberately enabled on the panel.
- **The software navigation bar has a dedicated Dashboard action** — it opens the configured dashboard without reloading it. Reload remains available separately if the dashboard needs recovery.[^issue-43]
- **Obsolete learned dashboard entities can be cleared from the Entities tab** — a confirmed reset clears what ha-paneld has learned, including manually included or excluded entities. The current filter keeps working while a fresh scan runs.[^issue-50]
- **Runtime diagnostics now show how much storage the app uses** — they also report verified-boot and bootloader status without exposing configured panel or network names.
- **Panel profiles show clearer processor details and useful reference links** — the Dashboard separates the processor model, CPU type and introduction year from the live core count, while the Profiles page can include approved links to product, vendor, community and technical information.
- **You can measure whether dashboard filtering actually makes a panel faster** — a new comparison records the same dashboard with filtering switched off and on, so you can see whether it improves response time instead of relying on how it feels. It can still collect useful results if the dashboard becomes unresponsive.[^discussion-10]
- **NSPanel Pro Zigbee joining can be requested from Configure** — once joining is enabled in ZHA or Zigbee2MQTT, an unjoined panel can try joining again without a restart.
- **Advanced setup no longer requires cloning the repository** — the signed release installer can configure, verify, back up or restore a panel using `install.sh --provision`.

### Changed

- **Configure and Install are easier to use on compact panels** — related dashboard and connection settings use shorter cards, Save changes appears only after a setting has changed, and setup tools such as display sizing, vendor packages and backup/restore are grouped on Install.
- **Home Assistant traffic diagnostics identify the busiest entities more clearly** — they show which three Home Assistant entities sent the most updates and data during the past hour.
- **Wake on wave now requires a deliberate gesture** — the display wakes only after a complete wave towards and then away from the panel. Setup, testing and standing near the panel no longer wake it accidentally; touch-to-wake remains available while it is learning.
- **Editable YAML files now define all hardware profiles** — the editor suggests valid choices, prevents a profile intended for different hardware from being activated, and keeps unofficial reflashed-device profiles in the community catalog rather than presenting them as built-in hardware support.[^issue-28]
- **Shizuku is now clearly described as optional** — official profiles use root or the helper where available and suggest Shizuku only for a specific feature that needs it.[^issue-42]
- **Fresh installations preserve the existing boot-chime behaviour by default** and prerelease browser tabs show the Android build code first, making test builds easier to identify.

### Fixed

- **Selecting a Home Assistant light sensor now checks it before saving** — an unreadable, missing or non-numeric entity leaves the current ambient-light source and all other pending settings unchanged and explains what needs attention.
- **Panels are less likely to become unavailable after settings, network or helper changes** — replaced tasks and MQTT connections now shut down cleanly, and losing privileged screen control no longer leaves ha-paneld permanently unresponsive.
- **LED and button-backlight controls no longer return to off while brightness is changing** — commands from Home Assistant now reach the hardware reliably, and button-backlight entities use Home Assistant's normal light icon.
- **CPU and temperature sensors now explain when the installed helper is too old** instead of silently disappearing.[^issue-21]
- **Disabled features stop consuming resources** while temporarily unavailable sensors retry at a controlled rate and repeated diagnostic requests share one refresh.
- **Installer progress and failures are clearer** — named stages show what is happening, backups are checked before connecting to a panel, stalled downloads and installs time out cleanly, and panels with insufficient system space receive a clear explanation.[^issue-44][^issue-46]
- **Restoring a backup no longer changes untouched vendor Zigbee settings**, including when an older backup is restored.[^issue-48]
- **Hardened mode no longer reuses credentials after the Home Assistant or MQTT server changes** — enter credentials for the new server or the old secret is cleared.
- **Install and Configure now report the real outcome without discarding newer edits** — failed display-size changes are no longer shown as successful, and settings appear only where they apply.
- **Live Sensors shows brightness as a percentage** while retaining the 0–255 value for diagnostics.[^issue-30]
- **Panel capability lists omit hardware known to be absent** while Generic profiles retain discovery guidance for unknown hardware.

### Upgrade notes

- Existing panels remain in Relaxed mode. Enable Hardened mode only from the Android app's Configure toolbar on the panel after disabling classic network ADB and Android Wireless debugging; remote tap injection remains unavailable until the panel returns to Relaxed mode.
- Locally built APKs can still update the app normally. Provisioning their bundled root helper requires `--allow-unsigned-helper` after the build has been verified and trusted; official release installations authenticate their helper automatically.
- Config JSON restore through `provision.sh --restore` now requires Python 3 on the computer running the installer. Full `.hpb` backups continue to restore from the panel's Install page.

### Limited hardware preview

- **ZX-SMT156 owners can opt into room temperature and humidity entities for release-candidate testing** — ha-paneld reads the panel's `sun-ths` and `sun-hum` sensor values through its authenticated helper or locally approved Shizuku service. The temperature and humidity scaling still needs comparison with the panel UI or another instrument; relay control is not included.[^issue-24]

[^issue-21]: User report: [#21 — No CPU/Temp sensors reporting](https://github.com/maxlyth/ha-paneld/issues/21).
[^issue-24]: User report and hardware evidence: [#24 — Panel diagnostic dump](https://github.com/maxlyth/ha-paneld/issues/24).
[^issue-28]: User request: [#28 — Profile for Echo Show 5 Gen 2](https://github.com/maxlyth/ha-paneld/issues/28).
[^issue-30]: User report: [#30 — Live State panel show brightness as a %](https://github.com/maxlyth/ha-paneld/issues/30).
[^discussion-10]: User discussion: [#10 — Performance improvement: entity filter proxy](https://github.com/maxlyth/ha-paneld/discussions/10).
[^issue-42]: User report: [#42 — Shizuku setup](https://github.com/maxlyth/ha-paneld/issues/42).
[^issue-43]: User request: [#43 — Dashboard button in the software navigation bar](https://github.com/maxlyth/ha-paneld/issues/43).
[^issue-44]: User request: [#44 — Explain installer progress and troubleshooting](https://github.com/maxlyth/ha-paneld/issues/44).
[^issue-46]: User report: [#46 — Root-helper install cannot fit on a full stock system partition](https://github.com/maxlyth/ha-paneld/issues/46).
[^issue-48]: User report: [#48 — Configuration export/import gaps](https://github.com/maxlyth/ha-paneld/issues/48).
[^issue-50]: User request: [#50 — Learned entity flush option](https://github.com/maxlyth/ha-paneld/issues/50).

## v0.9.4 - 2026-07-16

**ha-paneld can now help explain why a built-in Home Assistant dashboard feels slow.** New and updated cards on the Dashboard tab measure interaction delay, main-thread blocking, Home Assistant state traffic and renderer instability, then report a conservative likely cause. This helps distinguish an overloaded dashboard from excessive entity updates, memory pressure or another busy process on the panel.

**Installation now uses the panel's active hardware profile in a single pass.** The one-line installer starts ha-paneld during the installation run so the app can identify the panel before setup finishes. It then verifies the profile-specific access, helper and software requirements, while clearly reporting any optional or on-panel steps instead of silently applying them.

### Added

- **Built-in dashboard performance diagnostics** — interaction timing is separated into input delay, event handling and presentation alongside state-update rate, payload volume, main-thread occupancy, long frames and renderer reloads. These measurements do not require root or WebView debugging when using the built-in renderer.
- **Persistent performance history** — content-free, minute-level dashboard measurements are retained for up to seven days and available through the API for before-and-after comparisons across page reloads and app restarts.
- **NSPanel Pro Zigbee gateway health reporting and runaway protection** — ha-paneld reports gateway state, join status, CPU use, restart history and containment results. An unjoined gateway with sustained high CPU, or a configured gateway on a supported layout that repeatedly restarts, can be switched off automatically after a startup grace period; a joined gateway is never stopped merely for high CPU use.
- **Profile-aware installation in one pass** — the one-line installer starts the app, waits for it to identify the panel profile, then verifies the access, helper and software requirements for that hardware before reporting the installation complete.

### Changed

- **Full panel backups now preserve more recoverable state** — backups include configuration, custom profiles and entity-filter choices, with optional Home Assistant Companion login data. Supplying a passphrase creates an encrypted `.hpb` backup; creating an unencrypted `.zip` requires explicit acknowledgement.
- **Persistent application state is consolidated into a local database** — existing settings and learned entity data migrate automatically on first start, providing a common durable store for configuration, profiles and performance history.
- **Root-helper upgrades are authenticated and recoverable** — the installer verifies that the helper matches the release and retains the previous working installation until the app and replacement helper have both been verified.
- **Custom profiles now use schema 2** — provisioning recommendations are separated from hardware capabilities and runtime configuration. Existing schema 1 revisions remain available for inspection and export but cannot be activated; ha-paneld falls back to a compatible bundled profile or Generic until the profile is updated.
- **Profile recommendations no longer make optional system changes automatically** — ordinary and fleet installation report recommended vendor-package changes instead. Existing package controls explicitly selected by the user continue to work and reapply at boot.

### Fixed

- **Dashboard screenshots remain visible while refreshing** — returning to Dashboard shows the last successful capture immediately and replaces it only after a fresh screenshot has loaded; a slow or failed capture no longer blanks the card.
- **Panel capability reporting is easier to interpret** — the effective WebView rendering engine leads over stale package-reported versions, helper-backed root capability is not presented as a failure, and operational details such as state convergence, MQTT authentication timing and audio playback are separated from core panel information.
- **Successful installation is no longer reported as failed because a later optional recommendation could not be completed** — required installation and startup failures still fail the run, while optional actions are reported separately.
- **Configuration changes and shutdown no longer leave duplicate or stale background operations running** — superseded work is collapsed or cancelled once its result is no longer relevant.
- **WF1589T LED ownership is reported accurately** — the conflicting vendor LED service is presented as an optional, reversible package-control recommendation rather than being changed automatically.
- **Large backups and Companion login restores use bounded file-backed processing** — backup, encryption, upload, validation and restore no longer need to hold the complete archive in memory.

### Upgrade notes

- Custom profiles created for schema 1 must be updated to schema 2 before they can be activated.
- Existing settings and learned entity data should migrate automatically, but an explicit panel backup is recommended before installing this release.
- Unencrypted backups now use `.zip`; encrypted backups continue to use `.hpb`.
- Profile-recommended vendor-package changes are no longer applied automatically. Review the post-install guidance if a hardware feature depends on one.

## v0.9.3 - 2026-07-15

**Device support is no longer limited to profiles shipped by the ha-paneld project.** Panel owners and hardware vendors can create, edit, validate, import, activate, export and share panel profiles without rebuilding the app. Profiles remain declarative and limited to capabilities already implemented by ha-paneld, so extending hardware support cannot introduce executable code or arbitrary privileged operations.

This release also strengthens dependency and release verification, updates the shipped MQTT networking stack to security-patched versions and adds optional enhanced access for genuinely unrooted panels.

### Added

- **Panel support can be added or refined through validated YAML profiles** — the new Profile page can inspect the active bundled profile, edit or import a profile, preview validation, activate a revision, return to automatic selection and roll back to the last working revision. Profiles select bounded drivers and curated artifacts supported by ha-paneld; they cannot introduce shell commands, arbitrary paths, credentials or executable code. An unknown panel can also create a conservative draft from passive diagnostics for refinement and sharing, while bundled profiles include comments explaining how important values were established.
- **Optional [Shizuku enhanced access](provisioning.md#shizuku-fallback-for-unrooted-panels) is available for genuinely unrooted panels** — after the checksum-pinned Shizuku manager is installed and its service started, the user can approve ha-paneld locally to gain display sizing, screenshots, key and tap input, and signer-verified ha-paneld or minimal Home Assistant Companion installs. It does not provide root or the root-only hardware, system and private-data capabilities. The approval cannot be enabled remotely, and a service started through ADB normally needs rearming after a reboot.
- **Provisioning can prepare the optional Shizuku path without hiding the remaining on-panel step** — `provision.sh --shizuku` verifies or installs the pinned manager, starts its ADB service and points to the local approval screen. A trusted same or newer manager is retained on a repeated run.

### Changed

- **Dependency and release inputs now have stronger supply-chain controls** — Gradle artifacts are locked and checksum or signature verified, npm tools use exact lockfiles without dependency lifecycle scripts, GitHub Actions use full commit pins and publishing tools use hash-locked dependencies. Separately scoped CycloneDX inventories describe the Android/Gradle and embedded profile-editor runtimes, while curated Shizuku and WebView APKs require their recorded checksum as well as the expected package and signer.
- **The shipped MQTT networking stack has been updated to security-patched versions** — the HiveMQ client now uses the updated Netty transport line while retaining the existing MQTT connection and recovery model.
- **Release validation is more resilient and precise** — software inventories carry stable document identities, security analysis uses high-precision queries, and an external attestation outage no longer prevents an otherwise signed and verified release from being published.
- **Fleet imports now fail closed for new settings** — a setting is copied between panels only after it has been explicitly classified as portable. Shared endpoints and common behaviour preferences remain portable, while credentials, renderer and dashboard state, calibration, display tuning, update policy and logging stay with their original panel.

### Fixed

- **A configured dashboard returns to the foreground reliably after startup** — once Android's home-screen state has been reconciled, ha-paneld opens a ready built-in or explicitly selected external renderer instead of leaving the admin launcher visible. Interrupted built-in-renderer preparation remains retryable. (#31)
- **The admin launcher's Dashboard tile opens the configured renderer** — it now selects a ready built-in renderer, an explicitly selected external renderer or the existing automatic Companion fallback without showing unusable or duplicate targets. (#32)
- **Restoring a Home Assistant Companion login repairs a blank internal server URL before it goes live** — ha-paneld applies and verifies the repair in the staged database before entering the rollback-capable live transaction. A failed repair leaves the existing Companion data untouched instead of restoring a login that opens Home Assistant's “Missing Host header” error.
- **Credential backup and HTTPS audio handling are safer** — Android's implicit app backup and device-transfer path is disabled and excludes credential preferences, while HTTPS audio downloads use Android's normal certificate and hostname verification.
- **Embedded browser and local navigation targets are more tightly constrained** — embedded browser views reject file and content-provider access, release links accept only GitHub HTTPS destinations, peer navigation targets are validated before use and screenshot hydration stays on its fixed same-origin endpoint.

### Docs

- **The NSPanel Pro firmware index now includes the omitted 4.5.3 files** — the monitor also verifies each indexed download's size and its documentation now matches the daily seven-day availability history.

## v0.9.3-rc2 - 2026-07-15

**This release candidate updates the shipped MQTT networking stack to current security-patched releases and refreshes the tooling used to build, inspect and publish releases.**

### Changed

- **The MQTT client and its networking libraries have been updated** — the shipped HiveMQ client now uses the current Netty transport line, replacing older dependency versions affected by known security advisories while preserving the existing MQTT connection and recovery behaviour.
- **Release and CI dependencies have been refreshed and pinned** — GitHub Actions use reviewed full-commit references, the container build image is pinned by digest, CodeQL provides advisory security analysis, and the CycloneDX build tooling no longer uses the affected Plexus Utils release.

### Fixed

- **Release SBOMs now carry valid identities** — the Android inventory includes the document identifier required for attestation, while the embedded profile-editor inventory derives a stable identifier from its locked dependency graph. An external attestation outage remains advisory and no longer prevents an otherwise valid release from being published.
- **Credential backup and HTTPS audio handling are safer** — Android's implicit app backup and device-transfer path is disabled and excludes credential preferences, while HTTPS audio downloads use Android's normal certificate and hostname verification. Embedded browser views also reject file and content-provider access, and release links accept only GitHub HTTPS destinations.

### Docs

- **The NSPanel Pro firmware index now includes the omitted 4.5.3 files** — the monitor also verifies each indexed download's size and its documentation now matches the daily seven-day availability history.

## v0.9.3-rc1 - 2026-07-15

**Device support is no longer limited to profiles shipped by the ha-paneld project.** Owners and hardware vendors can create, edit, validate, import, activate, export and share panel profiles without rebuilding the app. Profiles remain declarative and constrained to capabilities already implemented by ha-paneld, so extending hardware support does not allow a profile to introduce executable code or arbitrary privileged operations.

### Added

- **Panel support can be added or refined through validated YAML profiles** — the new Profile page can inspect the active bundled profile, edit or import a profile, preview validation, activate a revision, return to automatic selection and roll back to the last working revision. Profiles select bounded drivers and curated artifacts compiled into ha-paneld; they cannot introduce shell commands, arbitrary paths, credentials or executable code. An unknown panel can also create a conservative draft from passive diagnostics for refinement and sharing.
- **Optional [Shizuku enhanced access](provisioning.md#shizuku-fallback-for-unrooted-panels) for genuinely unrooted panels** — after the checksum-pinned Shizuku manager is installed and its service started, the user can approve ha-paneld locally to gain display sizing, screenshots and key/tap input, and signer-verified ha-paneld / minimal Home Assistant Companion installs. It does not provide root, arbitrary APK uploads, System WebView replacement, private Companion data, system logs, reboot, backlight hard-off, LED/relay access, CPU governor, kiosk lock or vendor taming. The approval cannot be enabled by MQTT, the web API, a restored backup or a fleet push. A service started through ADB normally needs rearming after a reboot.
- **Provisioning can prepare the Shizuku path without hiding the remaining on-panel step** — `provision.sh --shizuku` verifies or installs the pinned manager, starts its ADB service and then points to the exact local approval screen. A trusted same or newer manager is retained on a repeated run. Automatic manager replacement is deliberately not part of this release candidate because an update can stop the Shizuku service and require rearming.

### Changed

- **Fleet imports now fail closed for new settings** — a setting is copied between panels only after it has been explicitly classified as portable. Shared endpoints and common behaviour preferences remain portable, while MQTT credentials, renderer choices, dashboard state, Home Assistant login provenance, calibration, display tuning, update policy and logging stay with their original panel.
- **Dependency and release inputs now have stronger supply-chain controls** — Gradle artifacts are locked and checksum/signature verified, npm tools use exact lockfiles without dependency lifecycle scripts, GitHub Actions use full commit pins and publishing tools use hash-locked dependencies. Separately scoped CycloneDX inventories describe the Android/Gradle runtime and embedded profile editor runtime, while curated Shizuku and WebView APKs require their recorded checksum as well as the expected package and signer. Automated updates can propose reviewed changes but cannot merge them.

### Fixed

- **Restoring a Home Assistant Companion login repairs a blank internal server URL before it goes live** — ha-paneld applies the repair to the staged database, checkpoints it and verifies both database integrity and the repair result before entering the rollback-capable live transaction. A failed repair leaves the existing Companion data untouched instead of restoring a login that opens Home Assistant's “Missing Host header” error.

## v0.9.2 - 2026-07-15

**Home Assistant dashboards that seemed too demanding for a low-powered wall panel can now be made far more responsive.** A large Home Assistant installation may send thousands of entity states and updates to a panel even when its dashboard displays only a small fraction of them, causing delayed taps and sluggish navigation. The built-in renderer can now learn what the dashboard uses and ask Home Assistant to send only those states. Automatic filtering remains experimental, opt-in and exclusive to the built-in renderer, but it no longer requires a hand-maintained entity list. For installations using a second Home Assistant instance, filtering proxy or similar workaround solely to reduce panel load, the built-in filter may allow that extra infrastructure to be retired.

### Added

- **Automatic dashboard entity filtering for the built-in renderer** — ha-paneld examines the configured dashboard, observes the states it uses while running and builds a focused Home Assistant subscription. A dedicated Entities page shows what was found, explains why each entity is included and allows manual additions or exclusions before filtering is enabled.
- **Potentially unsafe dashboard rules are identified before filtering** — broad or dynamic rules that ha-paneld recognizes are shown with their source and a suggested correction. Users can fix the dashboard, deliberately continue without the uncertain entities or leave filtering disabled; ignored warnings remain visible and can be restored later. Custom cards and behavior that automatic learning has not observed may still require manual review.
- **Learned entity choices persist without making backups unnecessarily large** — ha-paneld retains the user's manual choices, discards old observations automatically and can rebuild the rest of the catalog when needed. Existing exact entity lists remain supported through `/api/v1/dashboard/entity-filter` for controlled or externally managed setups.
- **Built-in dashboard performance is now measurable on every panel** — performance cards on the Dashboard tab and `/api/v1/perf` show how long a cold or warm dashboard load takes to become usable and how often the renderer has reloaded unexpectedly in the past 24 hours. These measurements do not require root and make it easier to see whether a renderer or configuration change actually helped.
- **Two newly reported panel types now identify correctly** — preliminary profiles give the Amazon Echo Show 5 Gen 2 running LineageOS and the unbranded ZX-SMT156/RK3566_T cautious defaults based on their submitted diagnostics. Follow-up diagnostic reports also collect the remaining hardware details needed to refine support without a long manual adb session.
- **Dashboard startup now shows what the panel is waiting for** — if networking is still coming up after a reboot, the built-in dashboard shows whether it is waiting for network services, a link, an address or a connection instead of looking broken. It learns the panel's typical startup time to give more useful progress on later boots and disappears entirely when networking is already ready.

### Changed

- **F-Droid channel retired.** Existing releases remain available, but new releases are distributed through the Home Assistant installer.

- **Changes made on the panel now stay in sync with Home Assistant** — screen power, brightness, volume, relays, LEDs and proximity could become stale or briefly jump back after a local or external change. ha-paneld now reports the latest confirmed panel state and keeps pending updates in order.
- **Installing the APK alone is clearly identified as incomplete setup** — releases now lead with the installer that handles permissions, startup, configuration and verification. The APK remains available for on-device sideloading and manual setups.
- **The unfinished remote-control page is withheld** — the Test tab and its screenshot tap-control workflow are hidden while the feature is reviewed for reliability. Existing screenshot, action, input and audio APIs remain available, and old `/test` bookmarks return to Dashboard.

### Fixed

- **Panels recover automatically from a temporary MQTT login rejection** — a rejected connection could leave a panel offline or start overlapping reconnect attempts. ha-paneld now starts a fresh connection and retries at a controlled pace, while diagnostics show what happened and when the next attempt will run.
- **Home Assistant entities no longer become stale after a failed MQTT update** — a missed state update could block later changes or trigger repeated retries. ha-paneld now keeps the latest panel state, limits the retry rate and continues sending pending updates.
- **Home Assistant now shows the screen's real power state** — on panels such as the TPA10, switching off the backlight could leave Home Assistant showing the screen as on because the stored brightness remained non-zero. ha-paneld now reads whether the backlight itself is powered. The updated app and helper daemon must be installed together on affected panels.
- **Settings changes and restarts no longer mix old and new behaviour** — work already underway during a settings change, MQTT reconnect or service restart could finish using the previous configuration, allowing an old connection, dashboard, audio request or status update to reappear after the change. ha-paneld now discards anything that belongs to the previous setup once its replacement begins.
- **Home Assistant no longer shows hardware changes that did not happen** — if an LED, relay or display command failed or was overtaken by a newer command, Home Assistant could still show the requested state even though the panel had not applied it. ha-paneld now publishes changes only after the hardware confirms them and prevents older commands from replacing newer ones.
- **Interrupted maintenance tasks no longer look successful** — an upload, download, software installation or uninstall that stopped part-way could still be reported as complete, while a failed update check could leave an old result looking current. ha-paneld now keeps these failures visible and reports success only when the full operation finishes.
- **A reboot no longer shows two connection errors before the dashboard appears** — if networking was not ready, the built-in dashboard could first show Chromium's offline error and then Home Assistant's 60-second connection-failed countdown. It now waits for the network and opens the dashboard directly; genuine later failures still retry.
- **“Silence boot chime” now also silences startup notification sounds** — on panels with separate ring and notification volumes, startup could still play a notification sound even when Silence boot chime was enabled. The setting now mutes both streams and uses a silent notification channel.
- **“Open in Home Assistant” follows the panel's current server and device** — changing the Home Assistant server or panel identity no longer leaves the button pointing at an obsolete device or a device on the previous server.
- **Display information no longer presents Android's base logical density as native DPI** — the logical density used to size the interface is labelled separately from the screen's physical pixels per inch, which is shown only for device profiles where it is known reliably.
- **Helper-backed panels no longer claim that working privileged controls are unavailable** — diagnostics now distinguish direct app access to `su` from actions routed through the helper daemon, such as reboot and reload on the TPA10.

## v0.9.1 - 2026-07-12

### Added

- **System WebView updates can now be installed automatically** — panels with a recommended WebView build gain an optional WebView auto-update switch, which is off by default and installs a newer recommended build during update checks. The first switch between different WebView vendors may still require the one-time manual procedure in the panel's hardware guide.
- **Switching from the Companion app no longer makes the dashboard look smaller** — the built-in dashboard now uses the same scale and carries over the Companion app's Page zoom value. A Dashboard zoom setting allows deliberate per-panel adjustment, while rooted panels can use the sharper display-density control when a larger change is needed.
- **ha-paneld settings are now available from the dashboard sidebar** — the built-in dashboard adds the same App Configuration entry as the Companion app, opening ha-paneld's configuration page directly on the panel.
- **The built-in dashboard starts with sensible wall-panel defaults** — the sidebar starts hidden and background connections stay active so the panel remains responsive while idle. These defaults apply only on first run and remain changeable.

### Changed

- **Camera streams on TPA10-class panels can now start without a tap** — LineageOS WebView 150 replaces Cromite as the recommended build because it keeps Home Assistant camera autoplay enabled. Cromite remains available as a fallback.
- **The dashboard no longer stretches when scrolling past an edge** — the built-in dashboard now disables Android's elastic stretch or edge glow by default. The `dashboard_overscroll` API setting restores the native effect for anyone who prefers it.
- **LED pulse effects are smoother and no longer flash white** — the breathing animation now follows a smoother curve at a higher frame rate instead of stepping visibly or flashing at its dimmest point.

### Fixed

- **Bottom-row dashboard controls now work normally with swipe-to-reveal navigation** — taps and scrolling near the bottom edge could be delayed or ignored because the navigation gesture captured every touch in that area. ha-paneld now intercepts only a genuine upward edge swipe, leaving ordinary dashboard interaction untouched.
- **Dragging or scrolling a dashboard card no longer refreshes the page** — pull-to-refresh now starts only when a drag begins at the very top edge of the screen. Card controls and scrolling inside the dashboard no longer trigger an accidental reload. (#29)
- **LED effects stop as soon as the light is turned off or another effect is selected** — strobe, blink or pulse could previously continue running, sometimes leaving the panel flashing until ha-paneld restarted. (#16)

## v0.9.0 - 2026-07-10

**ha-paneld can now display Home Assistant itself.** The optional built-in dashboard allows a panel to show Home Assistant without using the Companion app as the visible dashboard. It remains experimental and off by default; the Companion app remains the default and fully supported. Everything below is cumulative since v0.8.7.

### Added

- **Optional built-in Home Assistant dashboard** — ha-paneld can show the dashboard in its own WebView, with sign-in provisioned without typing on the panel. The feature is experimental and off by default. It does not provide Companion app features such as Assist voice control, so the Companion remains the better choice when those are required.
- **Wall-panel controls for the built-in dashboard** — camera streams start automatically and can open fullscreen, pull-to-refresh is immediate, an idle panel can return home, and edge-to-edge fullscreen mode reveals the system bars with a swipe. Renderer storage can be cleared remotely, and HTTPS servers using a user-installed private certificate authority are supported. (#25)
- **Light and dark appearance now follows where the interface is viewed** — the web interface follows each browser's preference, while a Dark mode setting themes ha-paneld's on-panel screens and supplies a dashboard default on older Android versions. A theme chosen inside Home Assistant still takes priority.
- **The Configure page stays current without losing unsaved edits** — settings changed through Home Assistant, the API or another browser trigger a reload when the form is untouched. If edits are in progress, a banner offers the reload instead of discarding them.
- **Features that need root access are visible instead of silently missing** — unavailable controls are greyed out with an explanation, the Install page offers a manual APK download, and the installer states which capabilities the panel can use.
- **The built-in dashboard can reuse the Companion app's sign-in** — on rooted panels, selecting the built-in dashboard borrows the existing Companion login so it can be tried without entering a Home Assistant URL or token on the panel.
- **Home Assistant can control LED effects** — compatible panel lights expose strobe, blink and pulse through Home Assistant's standard light effect selector.
- **MQTT connections can be encrypted** — `ssl://` and `mqtts://` broker addresses use TLS and the Android trust store, defaulting to port 8883.
- **Prereleases can be installed with one option** — `install.sh … | bash -s -- --prerelease` or `provision.sh --prerelease` selects the newest release candidate instead of the latest stable release.

### Changed

- **Dashboard options are easier to find and experimental features are clearly marked** — dashboard settings now have their own Configure card and renderer picker, while maturity badges identify unfinished or experimental controls.
- **Dashboard performance cards separate dashboard cost from measurement overhead** — sampling uses fewer resources, its own cost appears separately, and the work of hosting the built-in dashboard is labelled clearly.
- **Companion-only settings no longer clutter panels without the Companion app** — those controls are hidden and their unused Home Assistant entities are retired when no Companion installation is present.

### Fixed

- **A broken built-in dashboard no longer traps the panel in a restart loop** — repeated crashes are slowed and eventually fall back to ha-paneld's launcher, while a revoked Home Assistant login shows repair instructions and can be replaced from the Configure page.
- **Returning to a healthy built-in dashboard no longer reloads it unnecessarily** — kiosk and watchdog recovery now bring the existing page forward, preserving its current view instead of blanking and loading it again.
- **Switching from the Companion app reliably makes the built-in dashboard the panel's home screen** — existing panels now reclaim the Android home role immediately instead of returning to the Companion.
- **The navigation bar's Reload action no longer stops ha-paneld itself** — reloading a built-in dashboard now refreshes the page without terminating the app that hosts it.
- **Fullscreen and dark-mode changes now take effect when saved** — these settings previously stored the new value without visibly applying it to the running dashboard.
- **Panel status no longer shows stale or misleading hardware and software information** — uninstalling the Companion clears its update notice, and diagnostics no longer assume every Rockchip `/dev/ledjni` RGB light uses the rk3576 controller. (#24)
- **Provisioning login failures now explain how to recover** — failed Home Assistant sign-in no longer ends without useful guidance, and credentials containing special characters are handled correctly.
- **The dashboard returns promptly after a vendor home app is disabled** — panels no longer remain on ha-paneld's launcher until the next watchdog interval after vendor taming.

## v0.8.7 - 2026-07-07

**This release includes a large amount of work to improve the reliability of the install and provisioning scripts** (`install.sh`, `provision.sh`, and the root-daemon installer) across the supported panels — su-dialect probing, adb preflighting, honest self-verification, and clear, classified failure reporting. If you still hit an install or provisioning problem, **please [report it](https://github.com/maxlyth/ha-paneld/issues)** so it can be fixed.

Beyond that, panel telemetry is more reliable across the fleet, the Tuya TPA10 gains room temperature/humidity sensors, and the `:8888` header is now responsive on narrow panels.

### Added

- **Room temperature & humidity on the Tuya TPA10** — the onboard CHT8305 chip is exposed as opt-in Room temperature / Room humidity sensors in Home Assistant, read through the root helper daemon (proper temperature/humidity device classes, off by default), with a per-panel calibration offset for panel self-heating.

### Changed

- **Provisioning is much more robust** — `provision.sh` and the root-daemon installer now probe each panel's `su` dialect (join-style vs exec-style, `su 0` / `su root` / `su -c`, or root-adbd with no `su`), preflight the adb connection and fail fast (~12s) with specific recovery steps instead of hanging, classify install failures (signature mismatch, downgrade, out-of-storage) with how to recover, and degrade gracefully when a vendor build refuses adb-side permission grants. The daemon installer's exit code now reflects whether the daemon is actually running.
- **Diagnostic sensors populate on more panels** — CPU usage and SoC temperature fall back to the root helper daemon when the app can't read them directly, so they no longer show "Unknown" on sandboxed panels. All OS-sourced telemetry — the diagnostic sensors and the `:8888` performance view — now flows through one shared reader instead of two paths that could disagree.
- **Responsive `:8888` header** — a sticky top bar that never scrolls away; on a narrow or single-column panel the tab bar collapses to a hamburger menu and header items progressively hide instead of wrapping, so the header never overflows.
- **Config & controls polish** — the "expose to Home Assistant" control is now a link / broken-link icon toggle, and the Controls action buttons collapse to icons when the row would otherwise wrap. The low-value instrumentation master switch is gone (the performance sampler stays gated by page views, the real cost control).

### Fixed

- **`/api/v1/diag` responds instantly** — it serves the last-known panel snapshot and refreshes in the background instead of re-running the full probe suite (which can take >12s on an NSPanel Pro).
- **`provision.sh` self-verify and `--persist-adb` work reliably** — verify no longer always ended "re-run to finish", `--persist-adb` is confirmed by read-back, and provisioning can no longer clobber other accessibility services if it can't read the current list.
- **"Open in Home Assistant" is more reliable** — it falls back to the URL the HA Companion already uses when the panel's own HA device-page URL can't resolve.
- **On the Tuya TPA10, the room-temperature calibration offset saves from the web UI** (a posted value was previously accepted but silently dropped), and the older `temperature`/`humidity` sensors — which never streamed a live value on this chip — are retired in favour of the daemon-read `room_temp`/`room_humidity`.

### Docs

- The root-daemon install step (`helper/install-daemon.sh`) is now linked from the provisioning guide.

## v0.8.7-rc4 - 2026-07-07

### Fixed

- **`/api/v1/diag` responds instantly** — it claimed to reuse the cached panel snapshot but actually re-ran the full probe suite (a dozen su/system probes, >12s on an NSPanel Pro) whenever the snapshot was more than 15s old. It now serves the last-known snapshot and refreshes it in the background, like the Configure endpoints already did.
- **`provision.sh` self-verify works again** — the end-of-run checklist grepped for a diagnostics token that no longer exists (`a11y.enabled=`) and gave a cold `/diag` only 4 seconds (it can take >12s while the panel probes its capabilities), so every run ended "re-run to finish" even when the panel was fully provisioned, and `--verify` always exited non-zero. Verify now passes on a healthy panel and also reports whether the root helper daemon is running.
- **Root commands now work on SuperSU-style panels (NSPanel Pro)** — that `su` re-joins its arguments and runs them through its own `sh -c`, so wrapping a command in `sh -c` silently stripped the quoting (the daemon installer's multi-step root blocks were mangled). Both scripts now probe the panel's su dialect (join-style vs exec-style, plus `su 0` / `su root` / `su -c` prefixes and root-adbd with no su at all) and wrap commands accordingly.
- **The daemon installer's systemless boot script is pushed as a file** instead of being generated through nested device shells, which evaluated its boot-completed wait once at install time instead of at boot.
- **`provision.sh` can no longer wipe other accessibility services** — if reading the current enabled-services list fails, it skips the write (with a manual-path hint) instead of overwriting the whole list.
- **`--persist-adb` is verified by read-back** — previously a silent no-op on panels whose su form wasn't one of the two it tried.

### Changed

- **Provisioning and the daemon installer preflight the adb connection** — connect failures, the on-panel authorization dialog, and stale "offline" sessions are detected up front and fail fast (~12s instead of minutes) with specific recovery steps, before any release download.
- **Install failures are classified with recovery steps** — signature mismatch (debug vs release), downgrade, and out-of-storage each explain how to recover (including creating a full `.hpb` backup before an uninstall) instead of aborting with a raw adb error.
- **Permission grants degrade gracefully** — a vendor build that refuses adb-side grants no longer aborts provisioning; each failed grant names the manual Settings path and the run continues to the self-verify.
- **`install-daemon.sh` exit code now reflects daemon liveness** — files-in-place but no running process is reported as a failure with recovery steps; the script also states which root path it is using.

### Docs

- Sandbox-walled panels' root-daemon install step (`helper/install-daemon.sh`) is now linked from the provisioning guide, not just `helper/README.md`.

## v0.8.7-rc3 - 2026-07-07

### Changed

- **On the Tuya TPA10, room climate now reports as `room_temp` / `room_humidity`** (read through the panel's helper daemon) instead of the previous `temperature` / `humidity` sensors, which read through a standard Android sensor that never streams a value on this chip — so they showed a frozen, stale reading. The old entities are retired automatically; update any automation or dashboard card that referenced them.

### Fixed

- **The room-temperature calibration offset saves from the web UI again** — a posted value was accepted (HTTP 200) but silently dropped, so the field always reverted to 0.

## v0.8.7-rc2 - 2026-07-06

### Added

- **Room temperature & humidity on the Tuya TPA10** — the onboard CHT8305 chip is exposed as opt-in Room temperature / Room humidity sensors in Home Assistant (proper temperature/humidity device classes, off by default), with a per-panel calibration offset to correct for panel self-heating.

### Changed

- **Responsive `:8888` header** — a sticky top bar that never scrolls away; on a narrow/single-column panel the tab bar collapses to a hamburger menu and header items progressively hide instead of wrapping, so the header never overflows. The GitHub link stays reachable on every tab and in the footer.
- **Config & controls polish** — the "expose to Home Assistant" control is now a link / broken-link icon toggle, checkbox rows lay out correctly, and the Controls action buttons collapse to icons when the row would otherwise wrap.
- **"Open in Home Assistant" is more reliable** — it falls back to the URL the HA Companion already uses when the panel's own HA device-page URL can't resolve (e.g. a remote panel over a tunnel), so the button appears on every tab whenever a Companion is configured.
- **Removed the low-value instrumentation master switch** — the performance sampler and sensor trace are always on (still page-view gated, which is the real cost control).

## v0.8.7-rc1 - 2026-07-06

### Changed

- **Diagnostic sensors report on more panels** — CPU usage and SoC temperature now fall back to the root helper daemon when the app can't read them directly, so they populate on panels where they previously showed "Unknown". All OS-sourced panel telemetry (the diagnostic sensors and the `:8888` performance view) now flows through one shared reader, replacing two separate read paths that could disagree.

## v0.8.6 - 2026-07-05

The Install tab becomes a software-management hub, panels get encrypted backup/restore and self-healing update paths, and health problems surface much earlier and more clearly.

### Added

- **The Install tab is a software-management hub** — view and update ha-paneld, the HA Companion and the System WebView from `:8888`, each with a channel selector and a picker of recent releases, an on-demand health re-check, and an update banner with a per-version "Ignore this version" dismissal.
- **Encrypted panel backup & restore** — export a panel's full state (ha-paneld config plus, on a rooted panel, the HA Companion login) as a passphrase-sealed bundle; restore reapplies the config and re-logs in the Companion with no on-panel OAuth — the wall-panel equivalent of the cloud app-data backup these GMS-less panels don't have.
- **One-tap self-heal for the two most common blank-dashboard causes** — a too-old System WebView (downloaded and installed from the ha-paneld WebView mirror, per-panel known-good build) and a Companion with no internal URL (which shows as a "Missing 'Host' header" screen).
- **Watchdog crash-loop protection** — a dashboard app that crashes on every launch backs off instead of restart-storming and raises a clear health warning; a per-panel Companion version cap guards against a known-bad update (e.g. the NSPanel Pro's Android 8.1 crashing on Companion 2026.6.5+).
- **Install an APK from the web UI**, and **uninstall** a removable app — both gated behind explicit opt-in toggles (ha-paneld itself and the System WebView provider can't be removed this way).
- **Opt-in diagnostic sensors** (IP address, CPU usage, memory usage, SoC temperature, boot time), and **local-state sync extended to the CPU governor** — values changed outside ha-paneld now reach Home Assistant without flooding the broker.
- **Panel switcher in the `:8888` header** — on a multi-panel network, the panel's name becomes a dropdown that jumps your browser to the same view on another panel.
- **Experimental kiosk lock** — an opt-in per-panel setting that keeps non-admin users on the dashboard by suppressing the Android navigation bar and returning to the dashboard on any navigate-away; always reversible (on-panel 7-tap unlock, `:8888`, Home Assistant, or a reboot).
- **Dashboard & Launcher app pickers** are now dropdowns of installed apps, and both settings show what "auto" resolves to instead of a bare placeholder.
- **Provisioning tames recommended vendor apps by default** (e.g. the NSPanel Pro's eWeLink control-panel overlay and factory test tools) — reversible, and it can never strand the panel's home role.
- **Live log viewer** (Logs tab, app + system sources, pause/filter/follow) and a **live Sensors card** on the Dashboard, both served over Server-Sent Events with the same redaction pass as remote log shipping.
- **Companion auto-update channel** (Stable / Pre-release), and full config export/import via `provision.sh`.

### Changed

- **Publishing a setting to Home Assistant is now opt-in per setting** — admin-oriented settings (self-update, Companion auto-update, network ADB, Zigbee router, app watchdog) are panel-local by default; existing panels keep whatever they already expose.
- **The web UI loads instantly** — the Dashboard and Configure tab render immediately from a cached, single-flight probe snapshot instead of blocking on every root-probed value.
- **Safer, leaner APK installs** — every over-the-air install preflights free space and streams the verified APK straight into the installer instead of keeping a second staged copy.
- **The header shows the panel's friendly name** instead of its `panel_id`, and panel-health warnings are more prominent, with a one-tap self-heal offered from a failed Companion login.
- Clearer update-entity names ("ha-paneld auto-update"), and config import is now best-effort — valid keys apply even when others in the same bundle don't.

### Fixed

- **Home Assistant entity ids are anchored to the panel id, not the friendly name** — new entities keep stable `sensor.<panel>_*`-style ids regardless of how the device is renamed.
- **Local changes now reach Home Assistant** — brightness, volume and the effective backlight sync back to HA via a deadbanded, settle-aware heartbeat instead of going stale until the next command.
- **Vendor kiosk launchers (eWeLink) can be tamed** without stranding the panel, and the Launcher button always falls back to something usable instead of doing nothing.
- **MQTT reliability** — a superseded client rebuilding in the background could no longer overwrite the live connection's status with a false "credentials rejected" warning.
- **A brightness command or an interrupted config save can no longer leave the panel in a bad state** — brightness floors above zero, and config writes are atomic.
- **A hung `su` can no longer stall the app** — one-shot root commands now run with a hard timeout.
- **Touch-to-wake** — a screen-off can never strand the panel dark, even before proximity calibration.
- **"Open in Home Assistant" self-heals** when HA's device id changes, and auto-return after a relaunch now waits for the MQTT reconnect instead of giving up after 8 seconds.

### Security

- **CSRF and DNS-rebinding protection** on the `:8888` control API.
- **App and Companion updates download over HTTPS only**, on top of the existing signature + package pin.

## v0.8.6-rc6 - 2026-07-05

### Added

- **Panel switcher in the `:8888` header** — when more than one ha-paneld panel is on your network, the panel's name in the header becomes a dropdown of the others; picking one jumps your browser to the **same view on that panel** (e.g. `/configure` on panel A → `/configure` on panel B), so you can hop between local panels without remembering IP addresses. A single-panel network just shows the name as plain text.
- **Experimental kiosk lock** — an opt-in, per-panel setting (marked experimental) that keeps non-admin users on the dashboard by suppressing the Android navigation bar and returning to the dashboard if they navigate away. Aimed at generic Android panels with a working system navbar (the vendor panels are already covered by taming); on-panel unlock is a 7-tap top-left corner, and it's designed so it can't strand a panel or lock an admin out.
- **Dashboard & Launcher app pickers on the Configure tab** — the *Dashboard app* and *Launcher app* settings are now dropdown menus of the apps installed on the panel, instead of free-text package-name fields (a blank entry still means auto-detect).

### Changed

- **The header now shows the panel's friendly name** instead of its `panel_id`, on both the dashboard and the tabbed UI shell.
- **Panel-health warnings are more prominent**, and a failed HA Companion login now offers a **one-tap self-heal** from the warning.
- **Removed the redundant "Tame vendor packages" field** from the Configure tab — the Vendor packages card already covers it.

### Fixed

- **Touch-to-wake — a screen-off can never strand the panel dark.** Touching a dark screen always wakes it, even before proximity calibration, so no screen-off path can leave the panel unresponsive.
- **"Open in Home Assistant" self-heals when HA's device id changes.** If a panel's HA device is deleted and recreated (new device id, same panel), the info-page link re-resolves instead of pointing forever at the deleted device (which showed *"Device / service not found"*).
- **Saving on the Configure tab settles on a clear "Saved."** instead of hanging on a "reconnecting MQTT…" message that never cleared.
- **Auto-return to the dashboard after a relaunch now waits for the MQTT reconnect** (polling for up to 90s) instead of a single 8-second check, so a slow reconnect after a restart no longer leaves the panel sitting on the ha-paneld UI.

## v0.8.6-rc5 - 2026-07-03

### Changed

- **Safer, leaner APK installs** — every over-the-air install (ha-paneld self-update, the HA Companion updater, and the new WebView heal) now **preflights free space** before downloading, so a large APK can't fill `/data` or fail half-written on a low-storage panel, and **streams the verified APK straight into the installer** instead of keeping a second staged copy — halving peak disk use (a WebView build is ~250 MB).

### Added

- **The Install tab is now a software-management hub** — view and update ha-paneld, the HA Companion and the System WebView from `:8888`. Each GitHub-hosted component shows its installed version with a channel selector and a picker of recent releases (with release-notes links), so you can install a specific version or the channel's newest — not just "latest". Adds an on-demand health re-check (the same audit now drives the dashboard banner, the Install tab and `/status`, so they can't drift), a dashboard update banner with a per-version **"Ignore this version"** dismissal that re-surfaces when a newer release ships, and a radio-firmware (EFR32/Zigbee) status card.
- **Install an APK from the web UI** — upload an APK to a panel and, after it's parsed and its package/version/signer are shown for confirmation, install it over root. Gated behind an `allow_apk_upload` toggle with a prominent security warning (the `:8888` UI is unauthenticated LAN-trust).
- **Encrypted panel backup & restore** — export a panel's full state as a single bundle: ha-paneld config (including secrets) plus, on a rooted panel, the **HA Companion login** (its database + session captured over root). Seal it with a passphrase (AES-256-GCM + PBKDF2) or take a plain JSON bundle; restore auto-detects which, shows a decrypt/dry-run preview before the destructive apply, reapplies the config, then rewrites the Companion login with the correct owner uid + SELinux context and relaunches it — so a wiped or replacement panel gets its dashboard connection back with **no on-panel OAuth login**. This is the wall-panel equivalent of the cloud app-data backup these GMS-less panels don't have. (The login DB's SQLite WAL is checkpointed at capture so the restored login is never silently discarded.)
- **Uninstall an app from the Install tab** — a picker of removable (third-party / updated-system) apps with a one-tap root uninstall. ha-paneld itself and the System WebView provider are excluded from both the list and the endpoint, so you can't remove the tool or revert the WebView to the stock build that blanks the dashboard; a live uninstall on a remote panel stays attended-only.
- **One-tap WebView auto-heal** — a too-old System WebView is the most common reason a panel shows a blank Home Assistant dashboard, and these panels have no Play Store to update it. When ha-paneld detects the WebView is too old and the panel profile has a known-good build (NSPanel Pro → LineageOS 138, TPA10 → Cromite 147), the Install tab's warning now offers an **"Update WebView now"** button that downloads the correct `com.android.webview` from the ha-paneld WebView mirror and installs it over root — signature-pinned, like the app/Companion updaters — then reloads the dashboard. No adb, no F-Droid, no manual sideload. (#5)
- **Per-panel HA Companion version cap** — the Companion auto-updater can now be pinned below a build that's known to crash on a specific panel. On the Sonoff NSPanel Pro (Android 8.1) the Companion app 2026.6.5 and newer crash-loop on launch, so the updater is capped at the last good release and installs (or holds at) that instead of the newest one. The cap is a safety guard, not a preference, so even a manual "update now" honours it; panels without a known-bad build are unaffected.
- **Watchdog crash-loop protection** — if the dashboard app crashes immediately on every launch (e.g. after an incompatible update), the watchdog no longer relaunches it forever in a screen-flashing restart storm. After a few rapid relaunches it backs off, leaves the panel on whatever it can show (the admin launcher), and raises a **"Dashboard app is crash-looping"** health warning on the Install tab explaining how to recover. The warning clears automatically once the dashboard comes back healthy.
- **CPU profile stays in sync when changed outside ha-paneld** — the local-state → HA sync now covers the CPU scaling governor: if a thermal daemon or another app changes the governor, HA's "CPU profile" reflects it on the next heartbeat (same deadband/settle rule as brightness and volume, so no broker flooding). Recent "changed outside MQTT" events (brightness, volume, backlight, governor) are also surfaced on the info page and in the `/diag` dump for debugging.
- **Detect + repair a Companion with no internal URL** — a Home Assistant Companion whose server has an empty internal URL makes it request a host-less address, which recent Home Assistant releases reject with a full-screen *"Missing 'Host' header"* — a blank dashboard that's easy to misread as a broken panel. On a rooted panel ha-paneld now detects this and the Install tab offers a one-tap **"Repair internal URL"** that copies the server's external URL into the empty internal URL and relaunches the Companion. The database is read via an immutable, read-only handle so the check never disturbs the app's data.

## v0.8.6-rc4 - 2026-07-03

### Added

- **Provisioning tames the recommended vendor apps by default** — `scripts/provision.sh` now disables a panel profile's known-intrusive vendor apps (on NSPanel Pro: the eWeLink control panel overlay + the factory test tools) as part of provisioning, so a freshly set-up panel isn't obstructed by vendor clutter. It's fully reversible and **can never strand the panel** (a home launcher is only tamed once ha-paneld's admin launcher is a fallback home, and the home role is handed over first); pass `--no-tame` to skip. A stock, un-provisioned panel is never modified on its own — only running provisioning applies it. The `:8888` tame picker also gains a one-click "tame all recommended".
- **Diagnostic sensors for Home Assistant (opt-in)** — a new **Diagnostics** section in the Configure tab exposes the panel's **IP address, CPU usage, memory usage, SoC temperature and boot time** as HA sensor entities. Every one is **off by default** (panel-local until you tick its "expose to HA" pip), and the values are pushed with a per-metric deadband so they never flood the broker. CPU and SoC temperature need root/su (unavailable on sandbox-walled panels); IP, memory and boot time work everywhere.

### Fixed

- **Home Assistant entity ids are anchored to the panel id, not the friendly name** — new MQTT entities now carry a `default_entity_id` of `<domain>.<panel_id>_<capability>`, so their entity ids stay `sensor.<panel>_ha_paneld_*` regardless of the device's friendly name and no longer drift when you rename it. The friendly (device) name is still used for the display name, so you keep pretty names AND stable ids (the same approach Zigbee2MQTT uses). Existing entities are unchanged — the id is applied only at first registration — so there's no churn; only newly-created or re-registered entities get the pinned id. (HA ignores the older `object_id` discovery field, which is why the ids were drifting.)
- **Vendor kiosk launchers (eWeLink) can be tamed and no longer hijack the Launcher button** — on Sonoff panels the eWeLink control panel registers itself as a HOME launcher, which made it both un-tameable (the brick-guard protects home launchers so the panel can't be stranded with no home) and the app the navbar Launcher button opened, obstructing the dashboard. Taming a vendor home launcher is now allowed as long as ha-paneld's own admin launcher remains as the fallback home — the home role is handed over before the app is disabled, so the panel can never be stranded — and the Launcher button skips known vendor kiosks, falling through to a real launcher or ha-paneld's admin launcher. eWeLink now appears in the tame picker's "Recommended" group on NSPanel Pro. On a panel where the Launcher button would only fall through to the admin launcher (no separate launcher installed), it's now shown **disabled** with an explanatory tooltip rather than duplicating the Admin launcher button.

## v0.8.6-rc3 - 2026-07-02

### Added

- **Home dashboard shows what "auto" resolves to** — an unset per-panel home dashboard now reads `auto (HA default view)` in the dashboard row and Configure placeholder, instead of a bare `—`, so it's clear a reload lands on Home Assistant's own default view.

### Changed

- **Publishing a setting to Home Assistant is now opt-in per setting** — each setting declares whether it appears in HA by default; the admin-oriented ones (self-update, Companion auto-update + channels, network ADB, Zigbee router, app watchdog, brightness bias) are now **panel-local by default** — configurable in the `:8888` web UI but not cluttering HA unless you enable their expose toggle. The everyday controls (wake-on-wave, prevent-idle-dim, navbar, auto-brightness, touch sound, CPU profile) stay in HA as before. Existing panels keep whatever they already expose; this only changes freshly-provisioned panels. Touch sound and boot-chime silence now default on for new panels.

### Fixed

- **The Launcher button always lands somewhere** — on kiosk panels with no dedicated launcher app (the Companion registers as home but is the dashboard, and the vendor pseudo-launcher may be tamed or absent), the navbar Launcher key did nothing and the launcher setting read a dead `—`. It now falls back to ha-paneld's own admin launcher (the same path the web UI's launcher action already used), and the setting reads `auto (ha-paneld admin launcher)` when nothing else resolves.
- **Local changes now reach HA — a general state-sync keeps MQTT truthful** — values changed OUTSIDE ha-paneld (auto-brightness or any local app writing the brightness setting, hardware volume keys, vendor firmware dimming the backlight node) previously left HA showing stale state until the next command. A per-channel sync now runs on the existing heartbeat: it publishes only when a value differs from the last-published state beyond a deadband AND has stopped moving (a fast-oscillating value publishes nothing until it settles; a slow ramp publishes at most once per tick; steady state publishes zero messages) — so HA tracks reality without MQTT flapping. Channels: commanded brightness, volume, and the effective backlight (below).
- **`light.<panel>_screen` now reports the REAL backlight level** — panel firmware (NSPanel Pro idle-dim; also observed on the TPA10) moves the hardware backlight without touching Android's brightness setting, so the HA entity showed a stale level until the next command. ha-paneld now reads the effective level from the backlight sysfs (plain file read where permitted, root, or two new helper-daemon verbs `BLREAD`/`BLSET` on no-root panels), reconciles HA whenever it drifts from the last reported value, and the sensors endpoint + dashboard report the same effective value. Brightness commands on no-root daemon panels now also drive the hardware node directly, so they take effect past a firmware dim. Daemon panels need the updated helper binary (redeployed automatically by `install-daemon.sh`).

## v0.8.6-rc2 - 2026-07-02

### Added

- **"auto" now shows what it picked** — the Dashboard app and Launcher app settings display the detected package when unset: the read-only dashboard rows show e.g. `auto (io.homeassistant.companion.android.minimal)`, and the Configure fields carry the same as a placeholder — so "auto" is never a mystery.

### Changed

- **The panel's immutable hardware id anchors its HA device** — discovery now carries a second device identifier derived from the Android device ID (alongside the panel_id one), so Home Assistant recognises the same physical panel across a panel_id change instead of creating a duplicate device. Existing devices gain the new identifier automatically on the next discovery publish.
- **Stable entity ids for new registrations** — every MQTT discovery payload now carries an `object_id` keyed to the panel's unique `panel_id`, so Home Assistant names newly-registered entities `<domain>.<panel_id>_<capability>` instead of deriving the id from the device's friendly display name. Friendly names remain purely cosmetic, and two panels sharing a display name can no longer collide into `_2` suffixes. Entities already registered in HA keep their existing ids (HA pins them by `unique_id`) — no churn on existing setups.
- **provision.sh vendor-strip guard modernised** — the replacement-home check now accepts the HA Companion (either variant) or ha-paneld's built-in admin launcher; the deprecated `l.l` sideload launcher is no longer referenced.
- **Configure tab renders instantly** — the Display card, form values and schema now read through the same cached probe snapshot as the dashboard (stale-while-revalidate: last-known values render immediately, a background refresh keeps them current), removing the multi-second block that recurred whenever the density probe cache had expired.

## v0.8.6-rc1 - 2026-07-02

### Added

- **Live log viewer** — a new **Logs** tab tails the panel's logs in the browser, no `adb logcat` needed: pick **App** (ha-paneld's own process log, works with no root) or **System** (the full device logcat, root panels), filter by level or text, pause/resume, and follow the tail. Served as a plain Server-Sent-Events stream at `/api/v1/logs/stream` (also `curl -N`-able). Tokens/passwords are redacted server-side by the same single pass that guards remote log shipping — the capture subprocess only runs while someone is watching (or shipping is on) and stops when the last viewer disconnects.
- **Live Sensors card** — the Dashboard now shows live readings from **all of the panel's sensors** — ambient light, proximity (near/far + raw), temperature, humidity, plus volume and brightness — refreshed every 2 seconds via a new `/api/v1/sensors` endpoint. Readings are the hardware's live values, shown **even when a sensor is hidden from Home Assistant** by the exposure config, with an age indicator per reading; the card renderer is reusable so other tabs can mount it.
- **Companion auto-update channel** — the Companion updater gains the same **Stable / Pre-release** channel select as ha-paneld's own auto-update (`select.<panel>_companion_update_channel`, default Stable; also on the Configure tab). Switching channel triggers an immediate check when Companion auto-update is on.
- **Config export/restore from the provision script** — `provision.sh --export FILE` saves a panel's complete settings bundle (includes secrets — protect the file), `--restore FILE` best-effort-imports those settings (including device-scoped config), and `--restore-fleet FILE` applies only portable keys. These JSON operations do not replace the later `.hpb` full app backup/restore flow.

### Changed

- **The web UI loads instantly** — the Dashboard previously gathered every root-probed value (Zigbee state, CPU governor, network-ADB, density, touch sound…) before sending any HTML, leaving a blank page for 10+ seconds on slower panels (and ~4s on the Configure tab). The page shell now renders immediately and the probe-backed values hydrate in place from a new `GET /api/v1/info`; the probes themselves run through a cached, single-flight snapshot (pre-warmed at start, refreshed at most every 15s, invalidated on config changes), which also makes `/api/v1/diag` and the Configure tab fast.
- **Config import is now best-effort** — a bundle exported from a different panel model or a different ha-paneld version restores everything it validly can: valid keys apply, invalid values are reported and skipped, unknown keys warn and skip (previously a single invalid value rejected the whole bundle). `?strict=1` keeps the old all-or-nothing behaviour; dry-run previews now list what would be skipped. Status reports `applied` / `partial` / `rejected`.
- **Clearer update-entity names** — "Self-update" is now **"ha-paneld auto-update"** and "Update channel" is **"ha-paneld auto-update channel"**, so it's obvious they apply to ha-paneld and not the Companion app. Display names only — entity ids are unchanged, so no Home Assistant entity churn. (#18)

### Fixed

- **False "credentials rejected" while connected** — after a connection rebuild, the superseded MQTT client could keep auto-reconnecting in the background before its teardown completed; its rejected attempts overwrote the live connection's status, so the UI warned about invalid MQTT credentials (and the watchdog force-rebuilt every couple of minutes) while the panel was in fact connected and healthy. Superseded clients are now ignored by the status listeners and told to stop reconnecting, and the credentials warning only appears when the rejection is persistent — a transient rejection during a broker restart renders as "reconnecting…" instead. This also explains (and ends) the long-standing pattern of NOT_AUTHORIZED loops from a broker that accepts a fresh client.
- **No more false Companion "update available" for the installed variant** — the update check compared the installed `-minimal` versionName against the release tag as if the variant suffix were a prerelease marker, so a panel on `2026.6.5-minimal` was offered "2026.6.5" as an upgrade. Versions are now compared variant-stripped, in both the banner check and the auto-updater. (#17)
- **A brightness command can never blank the panel** — the HA brightness command and auto-brightness could drive the backlight to 0 outside the screen-off path, leaving the panel dark until the never-blank watchdog re-lit it. The brightness setter now floors at a minimum-visible level; a deliberate screen-off remains the only path to 0.
- **Config saves are atomic** — the Configure form previously applied fields one by one, so an interruption mid-save could persist a half-written config (e.g. a broker without its credentials). All fields now commit in a single atomic write, and the saved config migrates across schema changes on upgrade.
- **A hung `su` can no longer stall the app** — one-shot root commands now run with a hard timeout and are force-killed on expiry, degrading to "no root" instead of occupying a thread forever when a root shell hangs on auth.

### Security

- **Cross-site request forgery (CSRF) protection** on the `:8888` control API — a state-changing request (e.g. a `POST` to reconfigure MQTT, reboot the panel, or play audio) is now refused when it carries a browser `Origin`/`Referer` from a different site, so a malicious web page a user on your LAN happens to visit can no longer silently drive the panel. The panel's own web UI and non-browser callers (Home Assistant `rest_command`, `curl`, monitors) are unaffected.
- **DNS-rebinding protection** — the API only answers to a `Host` of the panel's IP address, `localhost`, an `*.local` (mDNS) name, or a name you allowlist (the new `http_allowed_hosts` setting); other hostnames are refused. Reaching a panel by IP (the usual way) is unchanged.
- **App and Companion updates download over HTTPS only** — the installer now refuses a plaintext (`http`) redirect at any hop in the download chain, on top of the existing signature + package pin that already blocks installing a tampered APK.

## v0.8.5 - 2026-07-02

The reliability release: MQTT connectivity that survives broker restarts, network flaps and flaky address families; safe, pinned app updating; and the first cut of the redesigned web UI. This canonical note summarises the full 0.8.5 release-candidate line (the dated rc sections below record the development history).

### Added

- **HA Companion app auto-install / update** (opt-in) — self-heals a missing or out-of-date minimal Companion over root, gated by a **signer + package allowlist** (pinned official certificate; a MITM/compromised asset can't install).
- **ha-paneld self-update with Stable / Pre-release channels** (opt-in, default off — auto-pull from the release repo is a supply-chain decision each operator makes deliberately; same pinned-signer install path) with no silent downgrades.
- **IPv6 as a first-class transport** — the broker connect resolves with an address-family preference and the watchdog flips family when one path won't hold, so panels land on whichever network family actually works; IPv6 broker literals parse correctly; a Wi-Fi high-performance lock plus a partial wakelock (`keep_awake`, on by default) keep the radio and SoC from power-save-stalling idle connections; an IPv6-loopback test harness guards regressions.
- **Redesigned web UI (first iteration, evolving)** — tabbed multi-page app (Dashboard · Configure · Test · Install · Fleet · API), a declarative settings registry driving a schema-generated Configure form, per-entity **Home Assistant exposure toggles**, a canonical versioned **`/api/v1`** (flat paths 308-redirect; `GET /health` + `POST /play` stay at the root), config **export/import bundles** with on-panel revision history, and an interactive remote-control screenshot.
- **`/diag` diagnostics depth** — capture timestamp + uptime, and a paste-safe `MQTT state` line (connection state, broker-ACK liveness age, family preference) plus wakelock state.
- **Reload returns to the intended dashboard** — a per-panel home-dashboard setting that reload navigates back to.

### Fixed

- **MQTT reliability, end to end** — the headline of this release. Stuck-offline after an HA/broker restart (stalled auto-reconnect) self-heals via a service-level reconnect watchdog + network-regained nudge; half-open (CLOSE-WAIT) connections that still claim "connected" are caught by **broker-ACK liveness** with a 30 s keepalive and heartbeat; the watchdog runs on a dedicated thread immune to thread-pool starvation; and the watchdog itself can never be frozen by the wedged connection it supervises — its probe and rebuild run on guarded side-threads with detached teardown. Panels that used to sit "unavailable" in Home Assistant until an app restart now self-heal within ~2–3 minutes.
- **Never-blank-screen guard** — retained/stale MQTT screen-off commands are ignored, deliberate screen-offs are tracked, and a watchdog re-lights an unintentionally dark panel.
- **No zombie entities across upgrades** — discovery is published un-retained and actively pruned on version change, so removed/renamed entities disappear from Home Assistant instead of lingering.
- **ANR under a wedged broker** — sensor callbacks moved off the main thread.
- **Network-adb persistence** — re-asserted at boot/reconnect when enabled; ownership-aware off.

### Docs

- NSPanel Pro firmware-quirks-by-version table; helper daemon verb-contract CI cross-check; connection-cap unit tests.

## v0.8.5-rc11 - 2026-07-02

### Fixed

- **Panels no longer stay "unavailable" in Home Assistant after a broker drop** — the connection watchdog could freeze inside its own liveness probe: the heartbeat publish runs into the same internal client monitor that a wedged (half-open) connection holds, so the exact failure the watchdog exists to heal also disabled the watchdog, leaving the panel unavailable until a manual app restart. The watchdog loop now never makes a potentially-blocking MQTT call itself: the heartbeat and the connection rebuild each run on their own guarded side-thread (skipped while a previous one is still in flight, with a timeout that re-arms healing if a rebuild wedges), and tearing down a wedged client is fully detached from bringing up its replacement. A stalled connection now self-heals within ~2–3 minutes, flipping address family so it lands on whichever network path actually holds.

## v0.8.5-rc10 - 2026-07-02

### Fixed

- **ANR under a wedged MQTT connection** — the light/proximity/temperature/humidity sensor callbacks were delivered on the **main thread**, and each fans out to an MQTT publish. A HiveMQ publish can block on an internal monitor while the broker connection is (re)establishing, so a stalled broker plus a live light sensor could hang the UI thread → "ha-paneld isn't responding". Sensor callbacks now run on a dedicated background thread, so a slow/blocked publish can never stall the app. (Surfaced when the whole fleet reconnected to one broker at once after an update.)

## v0.8.5-rc9 - 2026-07-01

> **⚠ The web UI has been significantly revised in this release — and it is a work in progress.**
> The single-page info/control surface is now a tabbed, multi-page app (Dashboard · Configure · Test ·
> Install · Fleet · API), and configuration has moved to a schema-driven Configure tab. Expect the
> layout, grouping and navigation to **keep changing over the next few releases** as the design
> settles — treat the current arrangement as a first iteration, not the final shape.
> **Nothing has been removed**: every control, value and workflow from the previous UI is still
> present (some have moved — proximity tuning, display sizing and vendor taming now live on the
> Configure tab, with their values still visible on the Dashboard), the old flat HTTP paths redirect
> permanently (308) to the new `/api/v1` API — with `GET /health` and `POST /play` still served at the
> root for plain-`curl` automations — and the MQTT entities are unchanged by default. The redesign
> runs on its own track and does not slow the regular feature release pace.

HTTP UI redesign — a declarative settings registry, configurable Home Assistant exposure, a canonical config API, config bundles with on-panel history, and a tabbed multi-page web UI.

### Added

- **Declarative settings registry** — a single source of truth (`config/SettingsRegistry`) describing each setting's type, group, Basic/Advanced tier, portability scope, validation, and Home Assistant entity. Drives the config API, the generated form, and MQTT discovery so the three can't drift. Pure/unit-tested, with golden tests asserting byte-identical discovery payloads.
- **Configurable HA exposure** — every config entity gains a per-panel "expose to Home Assistant" toggle. Hiding one clears its retained discovery (the entity leaves HA entirely — zero recorder / state-machine cost). Set from the new Configure page or `POST /api/v1/config` (`ha_expose_<key>`).
- **Canonical config over HTTP** — the formerly MQTT-only settings (`wake_on_wave`, `prevent_idle_dim`, `watchdog`, `auto_brightness`, `brightness_bias`, `navbar`, `touch_sound`, `cpu_governor`, `network_adb`, `zigbee_router`, `ambient_lux`) are now settable via the config API, applied through the same path an HA command uses.
- **`/api/v1` namespace** — the machine API is now versioned and canonical under `/api/v1` (config, schema, bundles, perf, proximity, diag, action, tame, display, inspect, screenshot, input, status). The pre-0.8.5 flat paths respond **308 Permanent Redirect** (method + body preserved) to their `/api/v1` homes; `GET /health` and `POST /play` — the external contract endpoints — also stay served at the root for plain-`curl` callers. `provision.sh` and the OpenAPI spec/explorer target `/api/v1`.
- **Config bundles + revision history** — `GET /api/v1/config/export` (versioned, secrets excluded by default) and a transactional `POST /api/v1/config/import` (migrate → scope/secret filter → validate-all-or-reject → snapshot → apply; `?dry_run=1` previews the diff, `?mode=fleet` applies only portable keys). On-panel revision ring buffer with `GET /api/v1/config/revisions` + restore.
- **Tabbed web UI** — Dashboard / Configure / Test / Install / Fleet / API. **Configure** is a schema-driven Basic/Advanced form with inline expose pips and bundle backup/restore; **Test** adds an interactive screenshot (View/Control — tap the image to touch the panel) plus on-screen nav actions and a TTS test; **Install** surfaces panel-health warnings + the capabilities matrix. Self-contained, offline, no build step.

## v0.8.5-rc8 - 2026-07-01

### Added

- **IPv6 treated as a first-class transport for MQTT** — ha-paneld now resolves the broker and connects with an address-family preference, and the connection watchdog flips family on a stalled reconnect, so if one family won't hold (e.g. a flaky IPv6 path) the panel automatically lands on whichever works instead of flapping. It also holds a **Wi-Fi high-performance lock** (alongside the existing wakelock) so the radio never drops into power-save and stalls an idle connection. IPv6 broker literals (`tcp://[…]:1883`) are now parsed correctly, and a new test harness (broker-address parsing + a real IPv6-loopback connect test) guards against IPv6 regressions.

## v0.8.5-rc7 - 2026-07-01

### Fixed

- **MQTT reconnect watchdog now runs on a dedicated thread** — on panels with slow/contended root (`su`), blocking calls could exhaust the shared background thread pool and silently stall the watchdog, so a dropped MQTT connection never self-healed. The watchdog is now its own thread, immune to that starvation, so a stuck/half-open connection is always detected and rebuilt.

## v0.8.5-rc6 - 2026-07-01

### Changed

- **ha-paneld self-update now defaults to OFF** — automatic self-update is a supply-chain risk if control of the release repo were ever lost, so it is now strictly opt-in for new installs (the pinned-signer check still guards it when enabled). Turn it on per panel with the **Self-update** switch if you want a panel to track releases automatically.

## v0.8.5-rc5 - 2026-07-01

### Fixed

- **MQTT panels no longer silently stop updating after a broker/network flap** — a broker-side disconnect could leave the panel's socket half-open (CLOSE-WAIT) while the MQTT client still reported itself connected, so it published into a dead link and Home Assistant showed stale values, with the reconnect-watchdog none the wiser. Three layered fixes: ha-paneld now holds a **partial wakelock** so the SoC and network never suspend into that state (screen still sleeps freely; on by default, `keep_awake`); the MQTT connection sets an explicit **30 s keepalive** so a dead link is detected quickly (and Home Assistant is told the panel is unavailable sooner, rather than shown stale); and the reconnect watchdog is now **liveness-based** — it tracks broker-acknowledged publishes and forces a full reconnect when nothing has been acknowledged for a few minutes, even if the client still claims to be connected.

### Changed

- **Helper connection-cap now unit-tested** — the daemon's concurrent-connection limit is covered by tests (boundary + concurrent rejection + a race check); no behaviour change.

## v0.8.5-rc4 - 2026-07-01

### Added

- **ha-paneld self-update (stable / pre-release channels)** — ha-paneld can now update **itself** over root from GitHub releases, following a configurable channel. A **Self-update** switch (on by default on the stable channel) checks on the 24 h cadence; an **Update channel** select picks **Stable** or **Pre-release**; an **Update ha-paneld** button forces it on demand. Uses the same pinned-signer install path as the Companion updater. It never auto-**downgrades** — moving from a running pre-release back to stable waits for the stable channel to catch up, while a forward update (or a stable→pre-release switch) installs immediately.

### Changed

- **MQTT retain rework — no zombie entities across upgrades** — discovery config is now published un-retained and tracked, so when ha-paneld starts a newer version it actively prunes the discovery topics it no longer publishes (version-gated) and re-announces the current set. Entities removed or renamed between versions no longer linger as dead entries in Home Assistant, and deletions stick.
- **Reload returns to the intended dashboard** — a dashboard reload now navigates back to the configured home dashboard after reloading, instead of leaving the WebView wherever it happened to be.
- **Network-adb persistence redesign** — the network-adb switch no longer just writes a boot prop and hopes the OS honours it (some firmwares strip it). ha-paneld now re-asserts network-adb at every boot and MQTT reconnect while the switch is on, distinguishes "active (enabled elsewhere)" from "persistent via ha-paneld" in the status, and won't disable adb that another mechanism turned on.
- **App↔daemon contract cross-check (reliability)** — a CI check now verifies every helper verb the app sends is implemented by the native daemon, so a protocol drift between the Kotlin clients and the helper fails the build instead of silently breaking a control at runtime.

### Docs

- **NSPanel Pro firmware-quirks-by-version table** — consolidated the per-firmware behaviour (stock WebView 107, per-version adb-enable route, v4.0.0 F-Droid bundle, 4.0.12 proximity graded/binary, 4.5.x reboot loops) into one quick-reference table in the hardware docs.

## v0.8.5-rc3 - 2026-07-01

### Changed

- **HA Companion app installer hardened (security)** — the auto-install/update is now gated by a **signer + package allowlist**: the downloaded APK must declare the allowlisted package **and** be signed by the pinned official HA Companion certificate, otherwise it's refused. This closes the fresh-install / MITM vector (Android's same-signer rule only protects *updates*, not first installs). Downgrades remain allowed (`pm install -d`), reserved for future stable/pre-release channel switching.

### Added

- **`/diag` capture time + uptime** — a `[captured]` header line (ISO-8601 timestamp + device uptime) so a pasted diagnostics dump can be correlated with logs/events; the capabilities block stays time-free for regression-diff stability.

## v0.8.5-rc2 - 2026-07-01

### Added

- **HA Companion app auto-install / update** — ha-paneld can now install a missing Companion or update an out-of-date one over root, self-healing the render stack on these no-Play panels (the minimal Companion variant has no Play auto-update, so ha-paneld is the only update path). Fetches the latest **minimal** APK from `home-assistant/android` releases. Opt-in per panel via the new **Companion auto-update** switch (checked on the 24 h update cadence); an **Update Companion app** button forces it on demand. Leaves a Play-managed *full* Companion alone.

### Fixed

- **Never-blank-screen guard** — a stray or stale screen-off (notably a broker- or automation-**retained** screen-off replayed on reconnect) could leave a panel dark and apparently bricked, since a screen-off kills the backlight but nothing re-lit it. ha-paneld now **ignores retained inbound MQTT commands** (commands must be fresh; state/discovery stays retained), tracks whether a screen-off was **deliberate**, and runs a watchdog that re-lights an unintentionally-dark panel within a minute — while leaving a genuine, user-requested screen-off alone.

## v0.8.5-rc1 - 2026-07-01

### Added

- **Central log shipping** — optionally forward each panel's own-process logcat (its own `Log.*` output plus the Ktor/HiveMQ library logs — **no root, no `READ_LOGS`**) to a configurable sink for fleet-wide debugging without per-panel `adb logcat`. Two transports: **syslog** (TCP, RFC5424) or **HTTP** (NDJSON batches). Off by default; set the destination via `POST /config` or `provision.sh --log-host/--log-port/--log-proto`, with live status on the info page + `/diag`. Each line is redacted for tokens / passwords / URL secrets before it leaves the device; LAN-only by intent.
- **S9E GPIO diagnostic** — `/diag` now reports each gpiochip's base/ngpio/label plus the per-pin export state on Smatek S9E panels, so a missing-button-LED result can be told apart from a gpiochip-base shift.

### Fixed

- **Panels no longer get stuck offline after an HA restart or network flap** — the MQTT bridge relied solely on HiveMQ's built-in auto-reconnect, which could stall after a *transient* auth rejection during an HA/broker restart (the broker returns before its auth backend is ready) or when its reconnect thread was deferred by Android power management, leaving the panel showing "MQTT credentials rejected" and never recovering until a manual config save or app restart. Added a service-level **reconnect watchdog** that forces a fresh connection whenever the bridge stays non-connected, plus a **connectivity-regained callback** that reconnects the instant the network returns.
- **WebView age check reads the real engine version** — the *"System WebView is too old"* warning now derives the Chromium version from the WebView User-Agent (`Chrome/<v>`) instead of the package `versionName`, which a Cromite / LineageOS SystemWebView deliberately stamps to the OEM stock value to clear a signature-locked provider gate. A panel running a modern engine behind a stamped-old package (e.g. Cromite on the Tuya TPA10) is no longer falsely warned, and the info row now shows the real engine when it differs. The lookup escalates only when the package version looks old, so modern-package panels are unaffected.

### Docs

- README slimmed — reference, build and roadmap material moved into `docs/`.

## v0.8.4 - 2026-06-29

Highlights since 0.8.3: a hardened privileged helper, the full control surface on sandbox-walled (no-`su`) panels, opt-in vendor-app taming, a dashboard watchdog, an admin launcher, panel-health warnings, and preliminary Shelly Wall Display profiles. (The per-RC sections below detail the path to this release.)

### Added

- **Privileged helper hardened (security)** — the root helper (LED / true screen-off / buttons / density / CPU governor / screenshot for sandbox-walled panels) no longer listens on an unauthenticated loopback TCP port; it's now an **abstract UNIX socket authenticated by peer UID** (only ha-paneld accepted), with bounded parsing and a command-parser fuzz + unit suite gating it in CI.
- **Full control surface on sandbox-walled panels** — density, font scale, CPU governor, on-demand screenshot and the Performance / Top-processes cards now work on no-`su` panels (e.g. Tuya TPA10) via the helper, plus the ZHICAI SMT1019 `/dev/ledjni` LED. The helper installs on **every** sandbox panel now, with `/diag` flagging it if it's needed but missing.
- **Opt-in vendor-app taming** — an interactive per-package blocklist (the *Vendor taming* card) force-stops, boot-disables and strips the floating-overlay permission from intrusive vendor apps; profile-seeded candidates for NSPanel Pro, TPA10, SMT1019 and WF1589T; critical packages refused at both the app and daemon layer; default empty, fully reversible.
- **Boot-chime silencing** — `switch.<panel>_silence_boot_chime` mutes the firmware boot sound and the Companion startup notification (per-platform audio key, profile-selected).
- **Dashboard watchdog** — `switch.<panel>_watchdog` relaunches the dashboard app if it crashes or stays backgrounded.
- **Admin launcher + default-home assertion** — a built-in admin app drawer reachable from the navbar **Launcher** button; on root panels `ensureDashboardHome()` keeps the dashboard as the boot home.
- **In-app update checker** — polls GitHub releases for ha-paneld and the installed HA Companion; banners in the `:8888` UI and a `/diag` line (for no-Play panels).
- **Panel-health warnings** — the info page flags a **system WebView too old** to render the HA dashboard (banner + highlighted version + update link) and **no dashboard app detected** (soft, renderer-aware — only when none of the HA Companion, Fully Kiosk or a configured `dashboard_package` is present; ha-paneld runs fine without one).
- **Shelly Wall Display device profiles (preliminary)** — `ShellyWallDisplay` (legacy MT6580) and `ShellyWallDisplayV2` (arm64) cover the full family, wired into `detect()`, plus a daily availability + weekly Wayback monitor for the Shelly OTA endpoints. Hardware verification ongoing.

### Changed

- **Helper renamed** `hapaneld-ledd` → **`hapaneld-helper`** (it does far more than LEDs now); panels upgrade automatically on redeploy.
- **Navbar** — every mode gains a **Reload** key; narrow-mode adds pop-up brightness/volume sliders; the volume % now syncs on any external volume change; navigate-to-the-current-URL reloads instead of no-opping.
- **Font scale via the helper** on sandbox panels (previously density-only).
- **Parser fuzzer moved off CI** — runs locally on demand (`make fuzz`); UART I/O could hang the CI runner.
- **Firmware URL monitor cadence** — daily polling with a 1-hour retry if any URLs are unreachable.

### Fixed

- **Wake ANR** — proximity-triggered wake no longer calls `Su.run()` on the main thread.
- **Navbar swipe / overscan / tap pass-through** corrected.
- **Vendor-renamed critical packages** can no longer be tamed (name-normalisation guard).
- **install-daemon.sh** detects systemless (Magisk bind-mount) root correctly; long WebView renderer process names are trimmed.

### Removed

- **Thread Mesh Router (preview) deferred** — the experimental Thread NCP flash/commission preview from the rc2–rc4 prereleases is pulled from 0.8.4 pending on-hardware UART validation; the **Zigbee-router** switch is unaffected; Thread returns in a later release (0.8.6+).

### Docs

- Recorded the NSPanel Pro stock WebView version (`107.0.5304.105`, Chromium 107), confirmed on a unit freshly flashed to firmware 3.5.1.

## v0.8.4-rc6 - 2026-06-29

### Added

- **Panel-health warnings** — the `:8888` info page now surfaces the states that silently stop a panel from showing the dashboard. **System WebView too old**: if the WebView's Chromium major is below the HA-frontend threshold (matches `provision.sh`'s `check_webview`), a banner explains why the dashboard renders blank/broken and links the update steps, and the version is highlighted in the panel-information table (a Cromite-swap caveat is noted, since that reports the stale OEM version). **No dashboard app detected**: a soft, renderer-aware notice that fires only when none of the HA Companion, Fully Kiosk (`de.ozerov.fully`), or a configured `dashboard_package` is installed — ha-paneld itself runs fine without one. Available-update notices already banner here. Decision logic is pure and unit-tested (`PanelHealthTest`).

### Removed

- **Thread Mesh Router (preview) deferred** — the experimental Thread NCP flash + commission support previewed in the `0.8.4-rc2`–`rc4` prereleases has been pulled from 0.8.4. Driving the EFR32MG21 radio to a working OpenThread NCP needs more on-hardware validation (finalising the firmware's Spinel UART pin configuration), so it's parked rather than shipped half-done. The **Zigbee-router** switch is unaffected. Thread support is planned to return in a later release (0.8.6+).

## v0.8.4-rc5 - 2026-06-26

### Added

- **Shelly Wall Display device profiles (preliminary)** — two new profiles cover the full Shelly Wall Display family: `ShellyWallDisplay` (legacy MT6580, covers Stargate/4" + Atlantis + Pegasus/X2-6.9") and `ShellyWallDisplayV2` (arm64, covers Blake/XL + Jenna/X2i + Cally/XLi + Maverick/U1 + Dayna/D1). Both are wired into `detect()`. Deployment requires ADB (all models) or the Shelly AppStore (modern/v2.6.0+). Hardware verification still in progress — relay control routes through the HA Shelly integration (not sysfs), no root on any model.
- **Shelly Wall Display firmware monitor** — daily availability monitor and weekly Wayback Machine archival for the Shelly Wall Display OTA endpoints, surfaced in Discussion #14.

### Changed

- **Firmware URL monitor cadence** — switched to daily polling (was hourly) with a 1-hour retry if any URLs are unreachable.

## v0.8.4-rc4 - 2026-06-26

### Added

- **Boot chime silencing** — an opt-in toggle (`switch.<panel>_silence_boot_chime`) suppresses the Sonoff start-up sound via `Settings.System`, surfaced as an MQTT entity and HTTP Controls-card switch.
- **Dashboard watchdog** — if the dashboard WebView crashes or is moved to the background, ha-paneld relaunches it automatically. Configurable delay; no root needed.
- **Admin launcher** — a minimal on-demand launcher (long-press the ha-paneld notification, or `POST /admin`) pops up for installing apps and changing settings while keeping the dashboard as the default home. Dismisses itself when done.
- **In-app update checker** — polls the GitHub releases API 30 s after service start and every 24 h; shows a banner in the web UI and a `/diag` line when a newer stable version is available for ha-paneld or the installed HA Companion build.

### Changed

- **Navigate reload** — navigating to the URL already displayed now reloads the page (previously a no-op), and the navbar gains a dedicated reload button for panels without a gesture bar.
- **Navbar volume % always in sync** — volume percentage now updates immediately when any external source (HTTP Controls card, HA-driven media player) changes the volume, not only on navbar ± presses.
- **Navbar brightness/volume sliders** — narrow-mode layout adds compact slider controls alongside the ± buttons.

### Fixed

- **Wake ANR eliminated** — proximity-triggered wake was calling `Su.run()` on the main thread; moved to a background coroutine.
- **Top-process name truncated** — long WebView renderer cmdlines (e.g. `com.android.webview:sandboxed_process0:org.chromium…`) are now trimmed to the package prefix.
- **install-daemon.sh root detection** — systemless root (Magisk overlay) is now detected correctly on panels whose `/system` is a bind-mount.

## v0.8.4-rc3 - 2026-06-25

### Added

- **Tame candidates for TPA10, SMT1019, WF1589T** — device profiles now seed curated tame candidates for panels beyond the NSPanel Pro so the vendor-taming picker has profile-informed suggestions on those panels.

### Fixed

- **Navbar swipe, overscan, tap pass-through** — directional swipe detection corrected (was triggering on vertical flings); always-on bar respects display overscan insets; taps on the bar no longer pass through to the content layer behind it.
- **Vendor-renamed critical packages protected** — packages that pass the is-critical check by their declared name but ship under a vendor-renamed package ID could previously be tamed; an additional name-normalisation guard closes that gap.

### Changed

- **Parser fuzzer moved off CI** — the helper command-parser fuzzer runs locally on demand (`make fuzz`) rather than as a CI step; UART I/O could hang the CI runner indefinitely with no timeout.

## v0.8.4-rc2 - 2026-06-25

### Added

- **Opt-in vendor-package taming** — a new Configure card surfaces an interactive per-package tick list of vendor apps that can be **force-stopped, boot-disabled, and stripped of the floating-overlay permission** so they can't draw above the dashboard. Profiled panels (NSPanel Pro) show the profile's curated candidates (e.g. `com.eWeLinkControlPanel`, unticked by default); generic panels enumerate live by overlay/launcher heuristic. Ticking and un-ticking applies immediately on Save (no reboot). Reversible: unticking re-enables. Critical system packages (`android`, `com.android.systemui`, etc.) are refused at both the app and daemon layer regardless of input. Default is empty — nothing is ever touched until the user opts in. Motivated by the Sonoff/CoolKit control-panel app drawing a floating widget over the dashboard after a firmware update.

### Fixed

- **Font scale on sandbox-walled panels** — display-sizing font-scale changes now route through the helper daemon when `su` is unavailable (TPA10), matching the existing density path. The stale "root only" note in the display-sizing card and `docs/display-sizing.md` is corrected.

## v0.8.4-rc1 - 2026-06-24

### Security

- **Privileged root helper hardened** — the helper that performs the root actions a sandboxed app can't (LED, true screen-off, hardware buttons, display density, CPU governor, screenshots, perf snapshots) **no longer listens on an unauthenticated loopback TCP port**. It previously bound `127.0.0.1:8889`, which **any** local app holding `INTERNET` could connect to and use to `REBOOT` the panel, change the CPU governor / display density, or `SCREENCAP` the screen — a real privacy + denial-of-service surface. It now uses an **abstract-namespace UNIX socket authenticated by peer UID** (`SO_PEERCRED`): only ha-paneld itself is accepted (plus root/shell for adb), and every other local app is rejected before it can issue a single command. Hardened further with airtight bounded parsing (exact-match commands, width-bounded arguments, overlong lines dropped not mis-split), all command execution funnelled through **one audited seam** with whitelisted arguments, connection caps + idle timeouts, and a **command-parser fuzzing + unit-test suite** against hostile local input.

### Added

- **Full control surface on sandbox-walled (no-root-shell) panels** — panels that can't `su` (e.g. the Tuya TPA10) now get, routed through the privileged helper, the controls that were previously root-shell-only: **display density** and **font scale**, **CPU governor**, **on-demand screenshot** (info page + `/screenshot.png` / HA camera image), and the **Performance / Top-processes / Responsiveness** cards.
- **Helper is now the control path for every sandbox panel, not just LED ones** — a panel's profile declares `usesDaemon` independently of its LED mechanism, so the daemon is installed on any no-`su` panel. `/diag` flags *needed-but-missing* so a sandbox panel without the daemon surfaces clearly.
- **ZHICAI SMT1019 RGB LED** — the LED ioctl (`/dev/ledjni`) is root-only; the helper now drives it so the LED works on the SMT1019 (it was reported unavailable in 0.8.3).
- **Proximity calibration** — device profiles can declare a default polarity; user-captured near/far calibration is stored per panel.
- **TPA10 vendor-app disable offer** — provisioning detects the Tuya vendor stack and offers a one-tap disable.
- **WebView age warning** — provisioning warns when the system WebView is too old to render the HA frontend, with a link to the sideload instructions.

### Changed

- **Root helper renamed `hapaneld-ledd` → `hapaneld-helper`** and restructured — the binary, its UNIX socket (`@hapaneld-helper`), and init service (`hapaneld_helper`) were renamed to reflect its broader role. Source split by capability; all command execution behind one audited seam. **No behaviour change — but panels running the helper must be redeployed**: `install-daemon.sh` removes the old `hapaneld-ledd` install automatically.

## v0.8.3 - 2026-06-19

### Changed

- **Lower-latency root actions** — root commands now run through a single long-lived `su` shell instead of forking `su` afresh per call (each fork+auth cost ~200–300 ms). Navbar **Back / Launcher / Recents** and other root-gated actions respond noticeably faster. Transparently falls back to a per-call `su` if the persistent shell is unavailable or a command stalls, so it's never worse than before.
- **Navbar auto-hide lingers longer** — the *Swipe reveal* bar now stays ~5 s (was 4 s) before sliding away.
- **Info-page card headers demarcated** — each card's title is now a subtly set-off header bar (slight tint + divider), separating it from the content. Groundwork for upcoming drag-to-reorder + collapsible cards.

### Added

- **Backlight no longer idle-dims** — several panel firmwares dim the hardware backlight at the screen-off timeout even while the OS keeps the screen on, so the panel went very dim ~60 s after the last touch despite full brightness. ha-paneld now holds the screen-off timeout high to defer that, **on by default** (`switch.<panel>_prevent_idle_dim`) — these are mains-powered wall panels; turn it off to restore the firmware's own dimming. No root needed (`WRITE_SETTINGS`).
- **Live screenshot in the info page + `/screenshot.png`** — the HTTP UI now shows a live panel screenshot (root `screencap`), scaled to a single column and **click-to-open full size** in a new tab. The `/screenshot.png` endpoint is also usable directly as a Home Assistant camera `still_image_url` / Picture-card image. LAN-only (like the rest of the surface), captured on demand — no background polling. Root required.
- **Smatek S9E proximity** — the S9E's `SensorManager` proximity registers but never delivers events, so `binary_sensor.<panel>_proximity` (and wake-on-wave) didn't work. ha-paneld now reads the raw proximity GPIO (gpio18: 1 = near, 0 = far) over root instead, on panels whose profile declares one. Reporter-confirmed (GitHub #5).
- **Touch-click feedback** — an opt-in click on every screen tap, produced by ha-paneld itself: a 1 px system-overlay touch watcher catches each tap (without consuming it) and plays the OS key-click via SoundPool, so it works even on the WebView dashboard where Android's own touch sounds never fire. Rides the existing `switch.<panel>_touch_sound`.
- **ZHICAI SMT1019 (WF2489T) support** — a dedicated profile for the rk3576 ZHICAI SMT1019 (`ro.product.device` `WF2489T`), a locked-down unit with no root and a firmware-restricted RGB LED (the `/dev/ledjni` ioctl is denied to sandboxed apps). The profile declares the LED unavailable so it's no longer mis-reported as present, uses the no-root screen-off path, and labels the device correctly. From the reporter's /diag (GitHub #8).

### Fixed

- **Touch sound re-applied at startup** — the touch-sound switch raised the system-stream volume only when toggled, so a panel that booted with it already enabled stayed silent (volume left at 0); it's now re-applied on boot.
- **Navbar brightness/volume now sync to Home Assistant** — stepping brightness or volume from the soft navbar changed the panel but never published the new state, so `light.<panel>_screen` and `number.<panel>_volume` went stale in HA. Both now publish on change.
- **Navbar volume ± reliable on all panels** — on a panel whose audio stream reports a small max number of steps, the old percent round-trip could round back to the *same* raw level, so a tap changed nothing and the system volume slider never appeared. The buttons now step the raw stream level directly, so every tap moves the volume **and** flashes the slider.
- **No navbar flash on auto-hide** — after the bar slid off the bottom edge it could flash back into view for a single frame before disappearing; it now hides cleanly.
- **Swipe-reveal no longer scrolls the dashboard** — a swipe-up to reveal the navbar could also scroll/displace the dashboard behind it (blank space at the bottom, top cropped). The reveal strip now consumes the whole gesture (it was letting un-consumed move events fall through), and its capture zone is taller so a fast off-screen swipe lands on it reliably.
- **Opening the config UI no longer crashes the daemon** — the foreground service re-ran its full startup on every `onStartCommand`, so anything that re-issued `startForegroundService` (e.g. opening the app) started a *second* HTTP server, which threw `BindException: Address already in use` on `:8888` and killed the whole process (taking down MQTT, sensors and the navbar with it). Subsystem startup is now one-time per service instance.
- **Screenshot card no longer reflows the info page** — the live screenshot reserves the panel's aspect-ratio box up front (a loading shimmer fills it, and stops with a glyph if the capture fails), so the image arriving no longer shoves the rest of the page down.
- **Proximity no longer stuck "near" on binary sensors** — the uncalibrated binary reading assumed the far value equals the sensor's max range, but some panels report far=1 with max=9 so it read NEAR forever; near is now `raw < 0.5` (0 = near).
- **Binary proximity card hides its no-op controls** — on a binary sensor there is nothing to calibrate, so the Capture / Sensitivity / Reset row is hidden and the card just shows the live near/far state and a one-line note.
- **Screen brightness reflects (and drives) the real backlight** — ha-paneld now reads and writes the hardware backlight node directly, not just the Android `SCREEN_BRIGHTNESS` setting, so HA shows the actual backlight level and the slider moves it on panels whose firmware idle-dims the backlight behind the OS.
- **Launcher button opens an actual launcher** — it no longer lands on the Home Assistant app (which registers as a home screen); it prefers the device's real default launcher and skips dashboard/kiosk home apps.
- **Wake-on-wave updates the screen entity in Home Assistant** — a proximity wake now publishes the ON state, so `light.<panel>_screen` no longer stays OFF after the screen wakes (reported in GitHub #6).
- **Zigbee no longer reported as "none" on 4.x firmware** — the gateway is detected by its `zgateway` binary, covering the 4.x `/vendor/bin/siliconlabs_host/run.sh` layout the old marker-file check missed.
- **Narrower gutters in a single-column layout** — on a one-column display the info page uses small page + inter-card margins instead of the desktop spacing, reclaiming width.

### Docs

- **F-Droid install guide** — [docs/fdroid.md](fdroid.md): add the ha-paneld F-Droid repository and install / auto-update straight on the panel, no PC.

## v0.8.2 - 2026-06-16

Adds a soft on-screen navigation bar for panels whose firmware hides the native one, and completes Smatek S9E hardware support.

### Added

- **Soft navigation bar** — an on-screen overlay bar (`select.<panel>_navbar`: **Off / Always on / Swipe reveal**) for panels whose firmware suppresses the native Android navbar (e.g. NSPanel Pro). Buttons: **Back**, **Launcher** (the device launcher/app-drawer), **Recents**, and — since these panels have no physical keys — **Brightness ±** and **Volume ±** (tap to step, press-and-hold to ramp; volume shows the system slider). On wide panels (e.g. the landscape TPA10) the live brightness/volume **percentage** shows between each ± pair. Back/Recents fire via root `input keyevent` where the app can `su` (no accessibility service needed), falling back to the accessibility service only where su is sandbox-blocked; **Recents is omitted on panels whose firmware has no overview screen** rather than presenting a dead control. Button presses show a highlight **held until the action completes** (so a ~250 ms root key-injection doesn't look ignored), and *Swipe reveal* hides the bar behind a bottom-edge strip that slides it up on touch and auto-hides.
- Drawing the overlay needs `SYSTEM_ALERT_WINDOW`; it's self-granted via in-app `su` on SuperSU panels, and `provision.sh` grants it for sandbox-walled panels (Tuya TPA10) that can't.

### Fixed

- **Smatek S9E now detected** — it was falling back to the generic profile (reported via its generic `rk3566_r` Build fields), which hid its two mains relays and four button LEDs. Detection now also matches the vendor model code in `ro.product.version` (`S9…`), so the S9E picks up its profile (relays, button LEDs at gpio 147–150, Smatek/S9E labels).
- **S9E relays visible on current firmware** — the relay sysfs class was renamed `st_relay` → `strelay` between S9E firmware 1.0.2 and 1.1.0, so ha-paneld now probes both names and uses whichever the panel actually exposes. Previously only the original 1.0.2 image worked; relays now surface on the 1.1.0+ firmware most panels ship. *Still untested on hardware.*
- **S9E button LEDs export their GPIOs** — gpio 147–150 aren't exported at boot (the firmware exports only gpio113), so the LED nodes didn't exist and the lights never appeared; ha-paneld now exports (and sets to output) each pin on demand before use. *Still untested on hardware.*

## v0.8.1 - 2026-06-10

Locks the HTTP control surface to the local network, makes the info page screenshot-safe, and adds a one-click jump to the panel's Home Assistant device page.

### Added

- **LAN-only control surface** — the HTTP API/UI (`:8888`) now refuses any request whose source isn't local (loopback / RFC1918 / link-local / IPv6 ULA). On a dual-stack panel this **closes the surface off from the public internet over a routable IPv6**, rather than relying on the home router to firewall inbound v6.
- **Screenshot-safe info page** — identity and network values (Device ID, MQTT broker, globally-routable IP/IPv6) are **blurred by default**; click a value, or the **Reveal** toggle, to show them (auto re-blurs). Only *globally-routable* addresses blur — a LAN RFC1918 / ULA address stays visible. Config-form fields blur the text only (the field outline stays crisp, and focusing a field reveals it for editing).
- **"Open in Home Assistant"** — when the panel's MQTT credentials are also a Home Assistant user, the info page resolves the panel's **own HA device page** and links straight to it. Works for **non-admin** HA users and across reverse-proxy / tunnel setups (it resolves the device id from HA's entity registry over the WebSocket API, and finds HA via the MQTT broker host when mDNS can't reach it).
- **Screen diagonal** on the info page — calculated from resolution + dpi (assuming square pixels); click to toggle inches ↔ cm, with width × height on hover.
- **Recommended display density per model** — the density "rec" button now suggests **160 dpi (86P) / 250 dpi (120P)** at text-scale 1.0.

### Fixed

- **Config-form race** — saving the config form twice in quick succession could leave MQTT stopped or half-connected (the "resubmit until it sticks" symptom); config reloads are now serialized so they can't interleave. The setup banner no longer reports "needs the MQTT broker" while the broker is merely mid-reconnect.

### Docs

- First **unit-test harness** — JVM tests (no emulator) covering the config-reload serialization and the setup-banner logic, so those regressions are guarded.

## v0.8.0 - 2026-06-08

On-panel auto-brightness, a major hardening of NSPanel Pro hardware support, and an overhauled info / diagnostics page.

### Added

- **Auto-brightness** *(opt-in)* — `switch.<panel>_auto_brightness` drives the backlight from a lux stream (the panel's own ambient-light sensor, or HA-fed `number.<panel>_ambient_lux` on sensor-less panels), with an asymmetric response (snappy on lights-on, smoothed on daylight drift) and a Dimmer↔Brighter bias. Off by default.
- **Controls card** (software nav bar) — Back / Recents / Launcher (reach Settings & other apps), plus Vol−/Vol+ and a confirmed Reboot, for panels with no physical nav bar. Each button **disables itself when its capability is absent** (Back/Recents need the accessibility service; Launcher/Reboot need root).
- **Auto-reload on update** — an open info-page tab reloads itself when the app is updated (reload-banner fallback while you're mid-edit in a field).
- **Debug sensor trace** *(instrumentation)* — `GET /sensortrace` exposes a RAM ring-buffer of raw lux / proximity samples + the auto-brightness internals (CSV or JSON) for filter fit-testing.

### Changed

- **Panel info reorganised** into separate cards — **Panel information**, **Networking**, **ha-paneld profile** (the device-profile-declared rows, linked to the profile source), alongside **Capabilities** — so it scales across columns instead of one ever-growing card.
- **Richer hardware readout** — a **Model · firmware** row (distinguishes 86P / 120P / 86P-Gen2), Zigbee provenance, **total eMMC** storage (matches the box spec), and Light / Proximity rows showing **technology · value-type · range** (e.g. `Infrared · Integer · 0–9 cm`). Proximity graded-vs-binary is decided authoritatively from the firmware version where the profile knows the rule.
- **Info-page layout** rebuilt on native CSS multi-column masonry — balanced columns with near-zero layout-shift (CLS) from phone to 15″ panels; live tables wrap full values (touch can't hover a tooltip); dark-mode polish throughout.
- **Diagnostics (`/diag`) rewritten** — terse, version-stamped, reflects every field ha-paneld detects, **safe to paste into a public issue** (network addresses + instance identifiers omitted), and structured for ingest into a regression-test harness.
- **Network ADB readout** distinguishes *active now* from *persistent* (survives a reboot).

### Fixed

- **NSPanel Pro Zigbee** — detects both the stock **vendor-native** gateway and the NSPanelTools-managed one; the `switch.<panel>_zigbee_router` works on both, an explicit *off* persists across reboot, and the **120P vendor-guard CPU-spin** is resolved (ha-paneld no longer starts a duplicate guard).
- **Accurate relay reporting** — Gen1 NSPanel Pro (86P / 120P) correctly shows **no relays**; the PX30 kernel's phantom `st_relay` nodes are no longer mistaken for hardware.

### Docs

- **Firmware backup & restore guide** for button-less (Rockchip) panels.

## v0.7.1 - 2026-06-07

Hardware buttons, CPU/display controls, and per-panel identity.

- **Hardware buttons instrumented via the daemon's evdev reader** — keys Android doesn't deliver to
  apps now reach HA:
    - **WF1589T power button** — ha-paneld **suppresses the button's built-in screen-lock** and
      instead publishes each press as an `event.<panel>_button` event, so its action is **decided by
      Home Assistant** (an automation), not hard-wired to lock the panel. (The PMIC's long-press
      hardware power-off is unchanged.)
    - **TPA10 5th (orange) button** — reports `SW_MUTE_DEVICE` (a switch, not a key), which is why
      stock firmware never surfaced it; now published as a `KEYCODE_MUTE` event.
- **CPU profile tiers** — the CPU governor select is now three intent-based options — **Performance /
  Efficiency / Auto** — instead of raw kernel governor names. Auto maps to the SoC's dynamic governor
  (ramps up on interaction, idles low — best for a mains, 24/7 panel).
- **Display sizing** *(experimental / R&D)* — set display **density** and **text size** to match an HA
  dashboard to a desktop browser (Android panels often ship these mismatched to the physical screen).
  Root panels only; the right per-panel values aren't dialled in yet — see
  [docs/display-sizing.md](display-sizing.md).
- **Per-panel HA device identity** — manufacturer/model defaults per panel (Sonoff / NSPanel Pro,
  Tuya / TPA10, Electron / WF1589T, Smatek / S9E; inferred from `Build.*` on unknown panels). The
  default model carries a " (ha-paneld)" suffix so the device is distinguishable from a co-installed
  integration managing the same hardware; the Configure form's value overrides it verbatim.
- **Theme-aware App UI** — the on-panel standing screen follows the panel's light/dark setting.
- **Stable HTTP performance table** — optional rows latch with a `–` placeholder instead of vanishing,
  so the page no longer jumps as metrics come and go.
- **README hero renders on both GitHub themes** (light/dark `<picture>` wordmark).
- **Security policy** — `SECURITY.md` + GitHub Private Vulnerability Reporting enabled.
- **Docs** — TPA10 hardware doc now covers all three button classes, incl. the recessed pin-hole
  (Rockchip factory-reset / MASKROM-loader, not a Linux input).

## v0.7.0 - 2026-06-06

Architecture-focused release (no new entities).

- **Device-profile architecture** — each supported panel now has a single canonical silo
  (`device/<panel>.kt`) declaring its quirks/paths; the LED, Zigbee and relay controllers read the
  active profile (detected once at startup) instead of hardcoding device specifics, while still
  runtime-probing to confirm. An unrecognised panel falls back to a Generic profile and works for
  whatever it physically has. The detected platform is shown on the info page. No change to the HA
  entities. Design: [docs/architecture/device-profiles.md](architecture/device-profiles.md).
- **Security hardening** — the Zigbee role-switch is allowlisted before any shell interpolation; the
  security posture (LAN-trust, network-layer access control, HA-auth as the future path) is documented
  in [docs/architecture/security.md](architecture/security.md).
- **Docs** — a "Why not Fully Kiosk?" section; releases are now cut as contenders (tagged only on
  approval), per [docs/RELEASING.md](RELEASING.md).

## v0.6.3 - 2026-06-05

Small fixes and polish; documents the 0.7.0 roadmap.

- **Better entity icons** — `mdi:adb` for Network ADB and `mdi:monitor-dashboard` for Navigate
  (the previous `mdi:android-debug-bridge` isn't a valid MDI name and rendered blank).
- **Fleet update fix** — `scripts/update-fleet.sh` no longer reads panels from a non-tty stdin when they
  were given as args (a pipeline/CI stdin had clobbered the panel list).
- **Roadmap** — documents the **0.7.0 device-profile architecture refactor** (architecture only, no new
  features): [docs/architecture/device-profiles.md](architecture/device-profiles.md). DLNA renderer
  reframed as under-consideration (it would be a separate HA device; the TTS recipe already covers announce).

## v0.6.2 - 2026-06-05

New controls (root/su panels):

- **CPU governor** (`select.<panel>_cpu_governor`) — set the scaling governor across all cores
  (powersave ↔ performance) to trade panel heat/noise against dashboard responsiveness; options come
  from the kernel's `scaling_available_governors`. Automatable from HA (e.g. powersave when empty).
- **Persistent network adb** (`switch.<panel>_network_adb`, opt-in) — keep `adb tcpip 5555` across
  reboots via `persist.adb.tcp.port`, plus a `provision.sh --persist-adb` flag. Leaves a standing LAN
  adb port, so it's off by default.
- **Smatek S9E button LEDs** (`light.<panel>_button_led1..4`) — the four button LEDs (gpio147–150).
  Experimental/untested like the rest of S9E.

Other changes:

- **IPv6** — the HTTP server now binds dual-stack (`::`), so the panel UI/API answer on IPv6 as well as
  IPv4, and the info page shows the panel's IPv6 address.
- **Navigate is local-only** (`text.<panel>_navigate`) — any scheme + host is stripped from the posted
  value and navigation is driven via the in-app `homeassistant://navigate/<path>` deep link. This stops
  the HA Companion opening a disorienting WebView for external URLs; the entity now holds a local path
  (defaults to `/` instead of `unknown`).
- **More robust Zigbee gateway detection** — the router switch is now gated on the guard script ha-paneld
  actually invokes, not just the `package_version` marker. This still shows on a configured panel that
  lost only the marker, and correctly hides on panels left with an empty `siliconlabs_host` dir + an
  orphaned `zgateway` (where ON could not restart it and it wouldn't survive a reboot).
- **Zigbee toggle no longer blocks MQTT** — the slow vendor lifecycle (OFF ~8 s; ON's gateway spawns on
  a ~30 s timer) now runs off the MQTT callback thread, publishing optimistically then reconciling to
  the real running state (polling for the slow start).
- **Fleet updates that don't leave panels dead** — `adb install -r` puts the app in Android's *stopped*
  state, which never auto-starts (not even via `START_STICKY`) until something launches it, so a bare
  install loop leaves panels installed-but-dead (entities `unavailable` in HA). `scripts/provision.sh`
  now retries the launch and explains why it's mandatory, and a new `scripts/update-fleet.sh` wraps
  provision.sh across many panels (downloading the release once) so every panel is installed **and
  launched and verified**.
- Docs: refreshed the on-panel launcher screenshot to the v0.6.x responsive UI (480×480).

## v0.6.1 - 2026-06-05

Zigbee router control for the Sonoff NSPanel Pro (the only panel with a Zigbee radio).

- **`switch.<panel>_zigbee_router`** — turn the panel's built-in Silicon Labs EFR32 into a Zigbee
  router/repeater (extends an existing mesh) and back off. ON starts the Sonoff `zgateway` host stack
  (kept alive by its own supervisor) and ensures router mode; OFF stops it and frees the radio.
- **Info-page Zigbee row** — shows the installed gateway driver and version (e.g. `sonoff 3.7.1`),
  whether it's running, and the current network role.
- Implementation is local and credential-free: ha-paneld talks to the panel's on-device mosquitto
  broker (`zigbee/system/network-role/…`); no firmware reflash, no `/dev/ttyS5` handling. The router
  appears as a normal device in your ZHA/Zigbee2MQTT coordinator, with its own signal/last-seen there.
- **Smatek S9E (barebones, experimental/untested)** — on-board **relays** as `switch.<panel>_relay1/2`
  (`/sys/class/st_relay`, gated on presence), and the four buttons reported as `event.<panel>_button`
  (`KEYCODE_F1`–`F4`). Derived from vendor paths in seaky#98; no S9E was available to validate, and the
  relays switch mains loads — treat as experimental. Button LEDs + proximity radar documented, not yet
  wired. See [docs/hardware/s9e.md](hardware/s9e.md).

## v0.6.0 - 2026-06-05

New entities and on-panel UX — all app-side (no daemon or root changes).

- **Temperature + humidity sensors** — `sensor.<panel>_{temperature,humidity}` where the panel has
  them (e.g. the TPA10's CHT8305), read via `SensorManager`. Recorder-friendly (on-change only, sane
  deltas + min interval, rounded values); the illuminance gate was also relaxed (2 s → 15 s).
- **Button-backlight light** — `light.<panel>_buttons` (brightness) on sysfs-LED panels, via the
  helper daemon.
- **Remote nav actions** — `button.<panel>_{back,recents}` via the accessibility service
  (`performGlobalAction`); uniform from HA dashboards/scripts, no root.
- **Wake-on-wave** — local, instant screen wake on proximity-near (`switch.<panel>_wake_on_wave`,
  default on where a proximity sensor exists). Sleep stays HA's job.
- **Auto-return to dashboard** — after an app update the launcher UI bounces back to the Home
  Assistant app once connected (touch cancels; configurable).
- **Config QR code** — scan the panel's config URL instead of typing it; the App UI is now responsive
  (fits 480×480 without scrolling, larger on roomy panels).
- **Touch-sound switch** — `switch.<panel>_touch_sound` for consistent UI click sound across the fleet.
- **TTS recipe** — `docs/tts.md`: render with any HA engine (Piper/Cloud) → panel `/play`.

## v0.5.1 - 2026-06-05

- **Removed the device admin.** It was effectively unused — screen-off already powers the backlight
  via the root helper daemon / `su` (`bl_power`), never `lockNow()` — but once activated on an older
  build it **blocked the app's own uninstall** (`DELETE_FAILED_DEVICE_POLICY_MANAGER`), and Android
  won't remove a non-test admin via `dpm`. Dropping it removes that trap entirely; fresh installs are
  never affected. Upgrading from a build where you'd activated it: deactivate it under **Settings →
  Security → Device admin apps** before uninstalling.

## v0.5.0 - 2026-06-05

The first release aimed at general use — a redesigned web UI, an in-app config screen, deeper
performance insight, and proper release signing. Especially for Tuya TPA10 owners.

**Web UI**

- Redesigned as a responsive **masonry dashboard** — single column on a phone, multiple columns on a
  wide screen, with full-width charts and labelled gridlines you can actually read.
- A **new app icon** — a wall panel showing the Home Assistant mark.
- **REST API explorer** at `/api` (try any endpoint from the browser) plus an OpenAPI spec at
  `/openapi.json` you can import into Swagger or Postman for fleet tooling.

**On the panel**

- Tapping the launcher icon now opens a proper **info screen** (status, the config URL, and buttons to
  open configuration or the Home Assistant app) instead of dropping back to the launcher.
- **Configure on the panel itself** — the config page opens in an in-app browser, so kiosk panels with
  no browser installed still work.

**Performance**

- **CPU clock** (current vs hardware max) so thermal throttling is visible, plus a **responsiveness**
  metric that reflects how the dashboard actually feels.
- **Top processes** — live top-5 by CPU, to confirm the dashboard is getting the CPU. Needs root.
- **1-click WebView DevTools** relay for deep dashboard debugging — no `adb` cable needed.
- Instrumentation now **only samples while you're viewing the page** and can be **switched off**, so
  the performance tool isn't itself a constant background cost.

**Proximity**

- Calibrate the near/far cutoff from the web page. A guided "capture near / capture far" flow handles
  the wildly different sensor scales and inverted polarity across panels; sensitivity (High/Med/Low)
  controls flap resistance. The raw value stays on the panel (shown live); only a clean on/off goes to
  Home Assistant, so a graded sensor can't flood the recorder. Fixes proximity being effectively dead
  on panels whose raw value exceeds the reported sensor range.

**Fleet & releases**

- **Config API** — read/update any setting over HTTP (`GET /config`; partial-merge `POST /config`).
- **One-command provisioning** (`scripts/provision.sh`) — install, grant permissions, set id/MQTT;
  idempotent, with a `--verify` check; runs on Linux, macOS, and Windows (Git Bash/WSL).
- Releases are now **signed with a dedicated release key**; the README documents the signing model and
  the build/fork steps.

## v0.4.2 - 2026-06-04

Self-diagnostics, so you can tell what works on *your* panel — and get help when it doesn't.

- **Capabilities matrix** on the web page: every feature (screen-off, LED, buttons, root, brightness)
  shown as working / degraded / unavailable, each with the exact command to fix a shortfall on your
  firmware.
- **`/diag` dump** — one-click hardware / firmware / SELinux / sensor report to paste into a bug
  report, so a panel can be diagnosed without the maintainer owning that hardware.
- New **[performance tuning guide](performance.md)** — why HA wall panels get slow (the
  WebSocket event firehose) and how to fix it.

## v0.4.1 - 2026-06-04

First public release. Live diagnostics and navigation for keyless panels.

- **Live performance view** on the web page — CPU / GPU / RAM history chart, load average, temperature.
- **Launcher** and **Home** buttons for panels with no physical Home/Back keys.
- **True screen-off** on Sonoff PX30 panels (real backlight-off, not just dimming) — no lock screen / PIN.
- HA device card enriched with configurable manufacturer / model, firmware and serial, plus a **Visit**
  link to the panel's web page.
- MQTT auto-reconnect and retained-state restore after a broker or panel restart.

## v0.4.0 - 2026-06-04

Hardware control across panel types, and a configuration web page.

- **RGB LED**: clean-room native driver for rk3576 panels (`/dev/ledjni` — no vendor blob, no root)
  and a root helper daemon for sysfs-LED panels (Tuya TPA10).
- **Panel web page** for status and configuration — no per-device app UI needed.
- **True screen-off** and **boot-persistence** for the helper daemon.
- Reproducible **Docker build** so anyone can build the APK without a local toolchain.

## v0.3.0 - 2026-06-03

The control API — Home Assistant can now drive the panel, validated on real hardware.

- **Brightness** and **sleep / wake** (`light.<panel>_screen`).
- **RGB LED** (`light.<panel>_led`), **navigate-to-URL** (`text.<panel>_navigate`), and
  **hardware-button events** (`event.<panel>_button`).
- **TTS volume** control (0–100%); TTS plays on the media stream, not the quiet accessibility stream.
- **Reload dashboard** and **reboot** buttons (root).
- Panel **light + proximity** sensors exposed to HA.
- Builds signed with a stable keystore so updates install in place (no uninstall dance).

## v0.1.0-MVP - 2026-06-03

Initial release — the TTS receiver, reimplemented as a proper Android app.

- **`POST /play`** TTS-announce HTTP contract (replaces the bash receiver).
- **MQTT auto-discovery**, **mDNS** advertisement, and a foreground service that survives reboots.
