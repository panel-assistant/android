#!/usr/bin/env python3
"""Deterministic, provider-neutral catalogue validation and translation-batch export."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any
import unicodedata

SCHEMA = 1
SOURCE_ROOT_KEYS = {"schema", "locale", "sourceRevision", "strings"}
SOURCE_RECORD_KEYS = {
    "text", "sourceHash", "surface", "context", "risk", "siblings",
    "placeholders", "frozen", "softMaxChars", "hardMaxChars",
}
TARGET_ROOT_KEYS = {"schema", "locale", "sourceRevision", "strings"}
TARGET_RECORD_KEYS = {"text", "sourceHash", "state"}
CANDIDATE_ROOT_KEYS = {
    "schema", "targetLocale", "sourceRevision", "sourceCatalogueHash", "translations",
}
CANDIDATE_RECORD_KEYS = {"key", "translation"}
LOCALES = {"de", "fr", "it", "es", "zh-Hans", "nl", "pl", "uk"}
SOURCE_SURFACES = {
    "settings", "shell", "dashboard", "configure", "setup", "profiles", "entities", "install", "logs",
    "fleet", "api",
}
STATES = {"english-fallback", "machine-draft", "machine-cross-checked", "community-corrected"}
KEY_RE = re.compile(r"[a-z0-9][a-z0-9._-]*\Z")
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")
REV_RE = re.compile(r"[0-9a-f]{40}\Z")
PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[a-zA-Z]|\{[a-zA-Z_][a-zA-Z0-9_]*\}")
LATIN_RE = re.compile(r"[A-Za-z\u00c0-\u024f]")
HAN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
UKRAINIAN_CYRILLIC_RE = re.compile(r"[\u0410-\u044f\u0490\u0491\u0404\u0454\u0406\u0456\u0407\u0457]")
RUSSIA_OR_RUSSIAN_CYRILLIC_RE = re.compile(
    r"(?<!\w)(?:росси|росі|русск)[\u0400-\u04ff]*(?!\w)",
    re.IGNORECASE,
)
ENGLISH_WORD_RE = re.compile(r"[A-Za-z][A-Za-z'-]{2,}")
TARGET_SCRIPT_POLICIES = {
    "de": "latin",
    "es": "latin",
    "fr": "latin",
    "it": "latin",
    "zh-Hans": "han",
    "nl": "latin",
    "pl": "latin",
    "uk": "ukrainian-cyrillic",
}
# A locale remains unsupported until it is present in LOCALES and has a catalogue. These
# requirements reserve script policies for later locales so extending LOCALES cannot silently
# inherit a nearby language's policy or select a convenient but incorrect policy.
REQUIRED_TARGET_SCRIPT_POLICIES = {
    "uk": "ukrainian-cyrillic",
}
UKRAINIAN_ALPHABETIC_CHARACTERS = frozenset(
    "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя"
    "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ"
    "ʼ"
)
REQUIRED_FROZEN_LITERALS = ("Home Assistant", "dB")
RENDERABLE_STATES = {"machine-cross-checked", "community-corrected"}
# Mirrors Kotlin AppLocale.EARLY_ACCESS_LOCALES: for exactly these locales, Strings.resolve()
# also renders a current machine-draft record instead of falling back to English.
EARLY_ACCESS_LOCALES = {"nl", "pl", "uk"}


def held_for_review(locale: str, record: dict[str, Any], source_record: dict[str, Any]) -> bool:
    """Early-access locales hold a consequential string at English until it has been reviewed."""
    return (
        locale in EARLY_ACCESS_LOCALES
        and source_record["risk"] == "consequential"
        and record["state"] == "english-fallback"
    )

MAX_TARGET_TEXT_CHARS = 16_384
MAX_TARGET_TEXT_BYTES = MAX_TARGET_TEXT_CHARS * 4
MAX_REPLACEMENT_FILE_BYTES = MAX_TARGET_TEXT_BYTES + 2
TARGET_PARAGRAPH_COUNTS = {
    "configure.zigbee.join_confirm": 3,
}
TARGET_NEWLINE_RUNS = {
    "profiles.modal.delete_detail": (1, 2),
}
UNCHANGED_TARGET_EXCEPTIONS = {
    ("de", "configure.enum.voice_sensitivity.normal"): "Normal",
    ("de", "configure.group.dashboard"): "Dashboard",
    ("de", "configure.group.system"): "System",
    ("de", "dashboard.camera.label.bitrate"): "Bitrate",
    ("de", "dashboard.camera.label.encoder"): "Encoder",
    ("de", "dashboard.card.screenshot"): "Screenshot",
    ("de", "dashboard.card.updates"): "Updates",
    ("de", "dashboard.controls.dashboard"): "Dashboard",
    ("de", "dashboard.controls.launcher"): "Launcher",
    ("de", "dashboard.fact.display"): "Display",
    ("de", "dashboard.fact.firmware"): "Firmware",
    ("de", "dashboard.responsiveness.tap_percentiles"): "~p50 {p50} ms · ~p95 {p95} ms",
    ("de", "dashboard.runtime.database.schema"): "Schema {version}",
    ("de", "dashboard.runtime.mqtt.seconds"): "{seconds} s",
    ("de", "entities.dynamic.default_dashboard"): "Dashboard",
    ("de", "entities.issue.default_dashboard"): "Dashboard",
    ("de", "logs.level.debug"): "Debug+",
    ("de", "logs.level.info"): "Info+",
    ("de", "logs.source.app"): "App",
    ("de", "logs.source.system"): "System",
    ("de", "logs.state.app_live"): "App · live",
    ("de", "logs.state.system_live"): "System · live",
    ("de", "logs.state.webview_live"): "Browser · live",
    ("de", "profiles.action.rollback"): "Rollback",
    ("de", "setup.progress.name"): "Name",
    ("de", "setup.progress.server"): "Server",
    ("de", "shell.nav.dashboard"): "Dashboard",
    ("es", "configure.enum.voice_sensitivity.normal"): "Normal",
    ("es", "configure.proximity.experimental"): "experimental",
    ("es", "logs.level.error"): "Error+",
    ("es", "logs.level.info"): "Info+",
    ("es", "shell.runtime.duration_minutes"): "{count} min",
    ("es", "shell.runtime.duration_seconds"): "{count} s",
    ("fr", "logs.action.pause"): "Pause",
    ("fr", "entities.dynamic.source"): "Source",
    ("fr", "entities.issue.source"): "Source",
    ("fr", "entities.row.option.auto"): "Auto",
    ("fr", "install.shared.version"): "Version",
    ("fr", "profiles.editor.codemirror.diagnostics"): "Diagnostics",
    ("fr", "profiles.section.validation"): "Validation",
    ("fr", "shell.runtime.duration_minutes"): "{count} min",
    ("fr", "shell.runtime.duration_seconds"): "{count} s",
    ("fr", "settings.dashboard_zoom.label"): "Zoom (%)",
    ("it", "configure.group.dashboard"): "Dashboard",
    ("it", "configure.option.auto_detail"): "auto ({value})",
    ("it", "dashboard.camera.label.bitrate"): "Bitrate",
    ("it", "dashboard.camera.label.encoder"): "Encoder",
    ("it", "dashboard.camera.watchers.one"): "{count} client",
    ("it", "dashboard.capability.root_su"): "Root (su)",
    ("it", "dashboard.card.screenshot"): "Screenshot",
    ("it", "dashboard.controls.dashboard"): "Dashboard",
    ("it", "dashboard.controls.launcher"): "Launcher",
    ("it", "dashboard.fact.firmware"): "Firmware",
    ("it", "dashboard.fact.ha_renderer"): "Renderer HA",
    ("it", "profiles.report.hardware"): "Hardware",
    ("it", "dashboard.live.volume"): "Volume",
    ("it", "dashboard.responsiveness.tap_percentiles"): "~p50 {p50} ms · ~p95 {p95} ms",
    ("it", "dashboard.runtime.database.schema"): "schema {version}",
    ("it", "dashboard.runtime.mqtt.seconds"): "{seconds} s",
    ("it", "dashboard.sensors.volume"): "Volume",
    ("it", "dashboard.value.auto_detail"): "auto ({value})",
    ("it", "logs.level.debug"): "Debug+",
    ("it", "logs.level.info"): "Info+",
    ("it", "logs.source.app"): "App",
    ("it", "logs.state.app_live"): "App · live",
    ("it", "logs.state.webview_live"): "Browser · live",
    ("it", "entities.dynamic.default_dashboard"): "Dashboard",
    ("it", "entities.issue.default_dashboard"): "Dashboard",
    ("it", "shell.nav.dashboard"): "Dashboard",
    ("it", "settings.camera_kbps.label"): "Bitrate (kbps)",
    ("it", "settings.dashboard_zoom.label"): "Zoom (%)",
    ("it", "settings.mqtt_password.label"): "Password",
    ("it", "settings.zigbee_router.label"): "Router Zigbee",
    ("it", "shell.runtime.duration_minutes"): "{count} min",
    ("it", "shell.runtime.duration_seconds"): "{count} s",
    ("it", "setup.mqtt.password.label"): "Password",
    ("it", "setup.progress.server"): "Server",
    ("nl", "configure.auto_sleep.value.live"): "Live",
    ("nl", "configure.badge.skunk_works"): "skunk-works",
    ("nl", "configure.dashboard.group_ha"): "Home Assistant dashboards",
    ("nl", "configure.duration.hours_minutes"): "{hours} h {minutes} min",
    ("nl", "configure.duration.minutes"): "{count} min",
    ("nl", "configure.enum.cpu_governor.auto"): "Auto",
    ("nl", "configure.enum.log_ship_protocol.http"): "HTTP protocol",
    ("nl", "configure.enum.log_ship_protocol.syslog_tcp"): "Syslog over TCP",
    ("nl", "configure.enum.log_ship_protocol.syslog_udp"): "Syslog over UDP",
    ("nl", "configure.enum.navbar_mode.native"): "Native",
    ("nl", "configure.group.camera"): "Camera",
    ("nl", "configure.group.dashboard"): "Dashboard",
    ("nl", "configure.option.auto"): "auto",
    ("nl", "configure.option.auto_detail"): "auto ({value})",
    ("nl", "dashboard.action.stop"): "Stop",
    ("nl", "dashboard.camera.label.camera"): "Camera",
    ("nl", "dashboard.camera.label.encoder"): "Encoder",
    ("nl", "dashboard.camera.session.open"): "open",
    ("nl", "dashboard.camera.watchers.many"): "{count} clients",
    ("nl", "dashboard.camera.watchers.one"): "{count} client",
    ("nl", "dashboard.camera.watchers.streaming"): "{watching}, {count} streaming",
    ("nl", "dashboard.capability.root_su"): "Root (su)",
    ("nl", "dashboard.card.screenshot"): "Screenshot",
    ("nl", "dashboard.card.updates"): "Updates",
    ("nl", "dashboard.common.live"): "· live",
    ("nl", "dashboard.controls.dashboard"): "Dashboard",
    ("nl", "dashboard.controls.launcher"): "Launcher",
    ("nl", "dashboard.fact.camera"): "Camera",
    ("nl", "dashboard.fact.firmware"): "Firmware",
    ("nl", "dashboard.fact.ha_renderer"): "HA renderer",
    ("nl", "dashboard.fact.model"): "Model",
    ("nl", "dashboard.fact.platform"): "Platform",
    ("nl", "dashboard.footer.api_explorer"): "REST API explorer",
    ("nl", "dashboard.link.open"): "Open",
    ("nl", "dashboard.live.volume"): "Volume",
    ("nl", "dashboard.noisy.payload"): "Payload",
    ("nl", "dashboard.performance.clock_max"): "/ {gigahertz} GHz max",
    ("nl", "dashboard.renderer.cpu_proxy"): "renderer CPU proxy",
    ("nl", "dashboard.responsiveness.heap_churn"): "· heap/OOM churn",
    ("nl", "dashboard.responsiveness.tap_percentiles"): "~p50 {p50} ms · ~p95 {p95} ms",
    ("nl", "dashboard.runtime.database.schema"): "schema {version}",
    ("nl", "dashboard.runtime.mqtt.seconds"): "{seconds}s",
    ("nl", "dashboard.sensors.volume"): "Volume",
    ("nl", "dashboard.value.auto_detail"): "auto ({value})",
    ("nl", "entities.dynamic.default_dashboard"): "Dashboard",
    ("nl", "entities.issue.default_dashboard"): "Dashboard",
    ("nl", "entities.row.option.auto"): "Auto",
    ("nl", "install.components.download_apk"): "Download APK",
    ("nl", "install.components.update_webview"): "Update WebView",
    ("nl", "install.display.reset"): "Reset",
    ("nl", "install.presentation.version_downgrade"): "Downgrade",
    ("nl", "install.presentation.version_upgrade"): "Upgrade",
    ("nl", "install.radio.efr32"): "EFR32 radio",
    ("nl", "install.tame.tag.overlay"): "overlay",
    ("nl", "logs.level.debug"): "Debug+",
    ("nl", "logs.level.info"): "Info+",
    ("nl", "logs.level.verbose"): "Verbose+",
    ("nl", "logs.source.app"): "App",
    ("nl", "logs.state.app_live"): "app · live",
    ("nl", "logs.state.webview_live"): "browser · live",
    ("nl", "profiles.action.fork"): "Fork",
    ("nl", "profiles.editor.codemirror.regexp"): "regexp",
    ("nl", "profiles.report.hardware"): "Hardware",
    ("nl", "profiles.severity.info"): "info",
    ("nl", "settings.camera_enabled.label"): "Camera",
    ("nl", "settings.dashboard_zoom.label"): "Zoom (%)",
    ("nl", "settings.log_ship_protocol.label"): "Protocol",
    ("nl", "settings.model.label"): "Model",
    ("nl", "settings.mqtt_broker.label"): "Broker URL",
    ("nl", "settings.wake_on_wave.label"): "Wake on wave",
    ("nl", "settings.zigbee_router.label"): "Zigbee router",
    ("nl", "setup.progress.server"): "Server",
    ("nl", "shell.menu.label"): "Menu",
    ("nl", "shell.nav.dashboard"): "Dashboard",
    ("nl", "shell.nav.logs"): "Logs",
    ("nl", "shell.runtime.duration_minutes"): "{count} min",
    ("nl", "shell.runtime.duration_seconds"): "{count} s",
    ("pl", "configure.badge.skunk_works"): "skunk-works",
    ("pl", "configure.duration.hours_minutes"): "{hours} h {minutes} min",
    ("pl", "configure.duration.minutes"): "{count} min",
    ("pl", "configure.enum.cpu_governor.auto"): "Auto",
    ("pl", "configure.enum.dashboard_theme.light"): "Light",
    ("pl", "configure.group.system"): "System",
    ("pl", "configure.option.auto"): "auto",
    ("pl", "configure.option.auto_detail"): "auto ({value})",
    ("pl", "dashboard.capability.root_su"): "Root (su)",
    ("pl", "dashboard.controls.launcher"): "Launcher",
    ("pl", "dashboard.fact.ha_renderer"): "HA renderer",
    ("pl", "dashboard.fact.http_port"): "HTTP port",
    ("pl", "dashboard.fact.model"): "Model",
    ("pl", "dashboard.fact.system_webview"): "System WebView",
    ("pl", "dashboard.footer.api_explorer"): "REST API explorer",
    ("pl", "dashboard.performance.clock_max"): "/ {gigahertz} GHz max",
    ("pl", "dashboard.renderer.cpu_proxy"): "renderer CPU proxy",
    ("pl", "dashboard.responsiveness.tap_percentiles"): "~p50 {p50} ms · ~p95 {p95} ms",
    ("pl", "dashboard.runtime.mqtt.seconds"): "{seconds}s",
    ("pl", "dashboard.value.auto_detail"): "auto ({value})",
    ("pl", "entities.row.option.auto"): "Auto",
    ("pl", "install.backup.dynamic.preview_panel"): "Panel",
    ("pl", "install.radio.efr32"): "EFR32 radio",
    ("pl", "logs.level.debug"): "Debug+",
    ("pl", "logs.level.info"): "Info+",
    ("pl", "logs.source.system"): "System",
    ("pl", "profiles.action.import"): "Import",
    ("pl", "profiles.editor.codemirror.regexp"): "regexp",
    ("pl", "profiles.severity.info"): "info",
    ("pl", "settings.model.label"): "Model",
    ("pl", "settings.mqtt_broker.label"): "Broker URL",
    ("pl", "settings.panel_id.label"): "Panel ID",
    ("pl", "settings.wake_on_wave.label"): "Wake on wave",
    ("pl", "settings.watchdog_enabled.label"): "App watchdog",
    ("pl", "settings.zigbee_router.label"): "Zigbee router",
    ("pl", "shell.menu.label"): "Menu",
    ("pl", "shell.runtime.duration_minutes"): "{count} min",
    ("pl", "shell.runtime.duration_seconds"): "{count} s",
}
TARGET_LITERAL_EXCEPTIONS = {
    ("de", "install.presentation.status_no_renderer"): ("ℹ",),
    ("es", "install.presentation.status_no_renderer"): ("ℹ",),
    ("fr", "install.presentation.status_no_renderer"): ("ℹ",),
    ("it", "install.presentation.status_no_renderer"): ("ℹ",),
    ("nl", "install.presentation.status_no_renderer"): ("ℹ",),
    ("pl", "install.presentation.status_no_renderer"): ("ℹ",),
    ("uk", "logs.source.webview_hint"): ("JavaScript", "DevTools"),
    ("zh-Hans", "logs.source.webview_hint"): ("JavaScript", "DevTools"),
    ("uk", "logs.note.sources"): ("JavaScript",),
    ("zh-Hans", "logs.note.sources"): ("JavaScript",),
    ("uk", "settings.kiosk_companion_packages.help"): ("root",),
    ("uk", "install.apk.dynamic.paste_url"): ("https://",),
    ("uk", "install.apk_status.invalid_url"): ("https://",),
    ("uk", "install.presentation.status_zigbee_legacy_watchdog"): ("LD_LIBRARY_PATH",),
    ("uk", "settings.kiosk_lock.help"): ("root",),
    ("uk", "api.approval.conditional"): ("Hardened",),
    ("uk", "configure.oauth.enter_url"): ("URL",),
    ("uk", "configure.oauth.valid_url_first"): ("URL",),
    ("uk", "configure.proximity.detail.local"): ("Touch-to-wake",),
    ("uk", "configure.proximity.message.test_cancelled"): ("Wave",),
    ("uk", "configure.proximity.setup.local"): ("Touch-to-wake",),
    ("uk", "configure.proximity.setup.no_source"): ("Wake-to-wave", "Touch-to-wake"),
    ("uk", "configure.zigbee.join_confirm"): ("Repeater",),
    ("uk", "configure.zigbee.join_help"): ("permit-join", "Repeater"),
    ("uk", "dashboard.actions.remote_input_disabled"): ("Hardened",),
    ("uk", "dashboard.banner.companion_url.explanation"): ("URL",),
    ("uk", "dashboard.banner.companion_url.repair"): ("URL",),
    ("uk", "dashboard.banner.companion_url.title"): ("Companion", "URL"),
    ("uk", "dashboard.banner.proximity_learning.title"): ("Wake on wave",),
    ("uk", "dashboard.banner.proximity_learning.touch_available"): ("Touch-to-wake",),
    ("uk", "dashboard.banner.setup_needs.valid_broker_url"): ("URL",),
    ("uk", "dashboard.banner.zoom.explanation"): ("Companion",),
    ("uk", "dashboard.capability.note.daemon_sandbox_path"): ("root",),
    ("uk", "dashboard.capability.note.led_unreachable"): ("root",),
    ("uk", "dashboard.capability.note.preferred_route_prefix"): ("root",),
    ("uk", "dashboard.capability.note.rockchip_led_direct"): ("Rockchip", "app-direct"),
    ("uk", "dashboard.capability.note.rockchip_led_helper"): ("Rockchip", "ioctl", "root"),
    ("uk", "dashboard.capability.note.root_or_helper"): ("root",),
    ("uk", "dashboard.capability.root_su"): ("root",),
    ("uk", "dashboard.inspect.enable_in_companion"): ("Companion",),
    ("uk", "dashboard.inspect.hardened_disabled"): ("Hardened", "Relaxed"),
    ("uk", "dashboard.inspect.instructions"): ("Companion",),
    ("uk", "dashboard.responsiveness.heap_churn"): ("heap",),
    ("uk", "dashboard.responsiveness.tap_breakdown"): ("ms",),
    ("uk", "dashboard.runtime.mqtt.config_error"): ("config-error",),
    ("uk", "dashboard.screenshot.remote_input_disabled"): ("Hardened",),
    ("uk", "dashboard.screenshot.view_only"): ("Hardened",),
    ("uk", "dashboard.state_stream.main_thread_detail"): ("ms",),
    ("uk", "entities.issue.button-card-limited-support.reason"): ("config-template",),
    ("uk", "install.apk_status.disabled"): ("install",),
    ("uk", "install.apk_status.no_root"): ("root", "helper"),
    ("uk", "install.backup.companion.helper_required"): ("helper",),
    ("uk", "install.backup.description.config_only"): ("config",),
    ("uk", "install.backup.description.with_companion"): ("config",),
    ("uk", "install.backup.dynamic.restore_confirm"): ("Companion",),
    ("uk", "install.components.play_managed"): ("Play",),
    ("uk", "install.components.root_update_required"): ("daemon",),
    ("uk", "install.presentation.companion_prior_files_retained"): ("Companion",),
    ("uk", "install.presentation.companion_relaunch_unconfirmed"): ("Companion",),
    ("uk", "install.presentation.guard_db_arm_not_started"): ("Guard DB", "ARM"),
    ("uk", "install.presentation.guard_db_candidate_discard_finished"): ("Guard",),
    ("uk", "install.presentation.guard_db_candidate_staging_finished"): ("Guard",),
    ("uk", "install.presentation.guard_db_retirement_settled"): ("Guard DB",),
    ("uk", "install.presentation.install_guard_db_owned"): ("Guard DB",),
    ("uk", "install.presentation.restore_app_state_invalid"): ("app_state",),
    ("uk", "install.presentation.restore_companion_section_invalid"): ("Companion",),
    ("uk", "install.presentation.restore_entity_object_invalid"): ("entity_state",),
    ("uk", "install.presentation.status_external_renderer_crash_loop"): ("Companion",),
    ("uk", "install.presentation.status_no_renderer"): ("ℹ",),
    ("uk", "install.presentation.status_storage_critical"): ("WAL",),
    ("uk", "install.presentation.status_storage_warning"): ("WAL",),
    ("uk", "install.progress.companion_repair_failed"): ("root",),
    ("uk", "install.progress.companion_repair_started"): ("Companion",),
    ("uk", "install.progress.companion_repairing"): ("Companion",),
    ("uk", "install.progress.start_failed"): ("root", "daemon"),
    ("uk", "install.status.companion_url_missing.one"): ("Companion", "Host"),
    ("uk", "install.status.companion_url_missing.other"): ("Companion", "Host"),
    ("uk", "profiles.issue.expected-boolean"): ("boolean",),
    ("uk", "runtime.mdns.not_running"): ("mDNS",),
    ("uk", "runtime.mdns.recovering"): ("mDNS",),
    ("uk", "runtime.mdns.stale_address"): ("mDNS",),
    ("uk", "runtime.mdns.unresponsive.one"): ("mDNS",),
    ("uk", "runtime.mdns.unresponsive.other"): ("mDNS",),
    ("uk", "runtime.power_safety.action.acknowledgeable"): ("stay-awake",),
    ("uk", "runtime.power_safety.action.repair_degraded"): ("root",),
    ("uk", "runtime.power_safety.action.repair_limited"): ("stay-awake",),
    ("uk", "runtime.power_safety.action.review"): ("stay-awake",),
    ("uk", "settings.ha_client_id.help"): ("config",),
    ("uk", "settings.ha_refresh_token.help"): ("config",),
    ("uk", "settings.ha_token_expiry.help"): ("access-token", "access", "refresh"),
    ("uk", "settings.ha_token_expiry.label"): ("access-token",),
    ("uk", "settings.home_dashboard.help"): ("Custom",),
    ("uk", "settings.launcher_package.help"): ("Blank", "Panel admin"),
    ("uk", "settings.room_temp_offset.help"): ("°C",),
    ("uk", "settings.webview_auto_update.help"): ("Play",),
    ("zh-Hans", "install.presentation.status_no_renderer"): ("ℹ",),
    ("zh-Hans", "install.apk.dynamic.paste_url"): ("https://",),
    ("zh-Hans", "install.apk_status.invalid_url"): ("https://",),
    ("zh-Hans", "install.presentation.status_zigbee_legacy_watchdog"): ("LD_LIBRARY_PATH",),
    ("zh-Hans", "entities.dynamic.body"): ("ID",),
    ("zh-Hans", "entities.issue.auto-entities-options-dynamic.summary"): ("Auto-entities",),
    ("zh-Hans", "entities.issue.auto-entities-options-javascript.summary"): ("Auto-entities",),
    ("zh-Hans", "entities.issue.auto-entities-seed-row-dynamic.summary"): ("Auto-entities",),
    ("zh-Hans", "entities.issue.auto-entities-typed-row-dynamic.summary"): ("Auto-entities",),
    ("zh-Hans", "entities.issue.kio\u0073k-mode-dynamic-javascript.recommendation"): ("Kiosk",),
    ("zh-Hans", "entities.status.unresolved_help"): ("ID",),
    ("zh-Hans", "settings.camera_exposure.help"): ("EV",),
    ("zh-Hans", "settings.kiosk_companion_packages.help"): ("root",),
    ("zh-Hans", "settings.kiosk_lock.help"): ("root",),
    ("zh-Hans", "settings.voice_enabled.help"): ("Assist",),
    ("zh-Hans", "settings.voice_pipelines.help"): ("Assist", "ID"),
    ("zh-Hans", "settings.webview_auto_update.help"): ("Google Play",),
    ("zh-Hans", "setup.mqtt.help.body"): ("broker",),
}
CONTEXT_ROOT_KEYS = {
    "schema", "id", "productContext", "instruction", "license", "notice", "sources", "terms",
}
CONTEXT_SOURCE_KEYS = {"id", "repository", "revision", "artifact", "artifactSha256", "license"}
CONTEXT_TERM_KEYS = {"id", "meaning", "english", "source", "sourceKey", "translations"}


class CatalogueError(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CatalogueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CatalogueError(f"{path}: {error}") from error
    if not isinstance(value, dict):
        raise CatalogueError(f"{path}: root must be an object")
    return value


def exact_keys(value: dict[str, Any], expected: set[str], owner: str) -> None:
    if set(value) != expected:
        raise CatalogueError(f"{owner}: expected keys {sorted(expected)}, got {sorted(value)}")


def source_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def contains_literal(text: str, literal: str) -> bool:
    return re.search(
        rf"(?<![A-Za-z0-9_]){re.escape(literal)}(?![A-Za-z0-9_])",
        text,
    ) is not None


def unprotected_text(text: str, source_record: dict[str, Any]) -> str:
    result = text
    protected = list(source_record["placeholders"]) + list(source_record["frozen"])
    for token in sorted(protected, key=len, reverse=True):
        result = result.replace(token, " ")
    return result


def remove_literal(text: str, literal: str) -> str:
    return re.sub(
        rf"(?<![A-Za-z0-9_]){re.escape(literal)}(?![A-Za-z0-9_])",
        " ",
        text,
    )


def validate_target_script_policies() -> None:
    """Require an explicit, supported script policy for every target locale."""
    if set(TARGET_SCRIPT_POLICIES) != LOCALES:
        raise CatalogueError("target script policies must exactly cover supported locales")
    invalid = sorted(
        locale for locale, policy in TARGET_SCRIPT_POLICIES.items()
        if policy not in {"latin", "han", "ukrainian-cyrillic"}
    )
    if invalid:
        raise CatalogueError(f"target script policy is invalid for: {', '.join(invalid)}")
    incorrect_required = sorted(
        locale for locale, required_policy in REQUIRED_TARGET_SCRIPT_POLICIES.items()
        if locale in LOCALES and TARGET_SCRIPT_POLICIES.get(locale) != required_policy
    )
    if incorrect_required:
        raise CatalogueError(
            f"target script policy violates locale requirement for: {', '.join(incorrect_required)}"
        )


def validate_target_language(key: str, text: str, locale: str, source_record: dict[str, Any]) -> None:
    validate_target_script_policies()
    policy = TARGET_SCRIPT_POLICIES.get(locale)
    if policy is None:
        raise CatalogueError(f"{locale}: target locale is unsupported")
    source_visible = unprotected_text(source_record["text"], source_record)
    target_visible = unprotected_text(text, source_record)
    if not LATIN_RE.search(source_visible):
        if policy != "ukrainian-cyrillic" or not any(
            character.isalpha() for character in target_visible
        ):
            return
    for literal in TARGET_LITERAL_EXCEPTIONS.get((locale, key), ()):
        target_visible = remove_literal(target_visible, literal)
    if policy == "han":
        if not HAN_RE.search(target_visible):
            raise CatalogueError(f"{key}: {locale} target has no Han text")
        residual = sorted({word.casefold() for word in ENGLISH_WORD_RE.findall(target_visible)})
        if residual:
            raise CatalogueError(f"{key}: residual English words: {', '.join(residual)}")
        if any(character.isalpha() and not HAN_RE.fullmatch(character) for character in target_visible):
            raise CatalogueError(f"{key}: {locale} target has unexpected script")
    elif policy == "latin":
        if not LATIN_RE.search(target_visible) or any(
            character.isalpha() and not LATIN_RE.fullmatch(character)
            for character in target_visible
        ):
            raise CatalogueError(f"{key}: {locale} target has unexpected script")
        if (
            target_visible.strip().casefold() == source_visible.strip().casefold()
            and UNCHANGED_TARGET_EXCEPTIONS.get((locale, key)) != text
        ):
            raise CatalogueError(f"{key}: target is unchanged English")
    elif policy == "ukrainian-cyrillic":
        if not UKRAINIAN_CYRILLIC_RE.search(target_visible):
            raise CatalogueError(f"{key}: {locale} target has no Ukrainian Cyrillic text")
        if RUSSIA_OR_RUSSIAN_CYRILLIC_RE.search(target_visible):
            raise CatalogueError(f"{key}: {locale} target contains prohibited Cyrillic locale term")
        unexpected = sorted({
            character
            for character in target_visible
            if character.isalpha() and character not in UKRAINIAN_ALPHABETIC_CHARACTERS
        })
        if unexpected:
            codepoints = ", ".join(f"U+{ord(character):04X}" for character in unexpected)
            raise CatalogueError(f"{key}: {locale} target has unexpected script: {codepoints}")


def validate_target_text_hygiene(key: str, text: str) -> None:
    paragraphs = text.split("\n\n")
    allows_paragraph_breaks = (
        len(paragraphs) == TARGET_PARAGRAPH_COUNTS.get(key)
        and all(paragraph and "\n" not in paragraph for paragraph in paragraphs)
    )
    expected_runs = TARGET_NEWLINE_RUNS.get(key)
    newline_parts = re.split(r"\n+", text)
    allows_structured_breaks = (
        expected_runs is not None
        and tuple(len(run) for run in re.findall(r"\n+", text)) == expected_runs
        and all(newline_parts)
    )
    unsafe = sorted({
        f"U+{ord(character):04X}"
        for character in text
        if unicodedata.category(character) in {"Cc", "Cf"}
        and not (character == "\n" and (allows_paragraph_breaks or allows_structured_breaks))
    })
    if unsafe:
        raise CatalogueError(f"{key}: target contains unsafe control or format characters: {', '.join(unsafe)}")


def string_list(value: Any, owner: str, *, distinct: bool = True) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise CatalogueError(f"{owner}: expected an array of strings")
    if any(not item for item in value):
        raise CatalogueError(f"{owner}: empty value")
    if distinct and len(value) != len(set(value)):
        raise CatalogueError(f"{owner}: duplicate value")
    return value


def validate_source(path: Path) -> dict[str, Any]:
    root = read_json(path)
    exact_keys(root, SOURCE_ROOT_KEYS, "source root")
    if root["schema"] != SCHEMA or root["locale"] != "en":
        raise CatalogueError("source root: unsupported schema or locale")
    if not isinstance(root["sourceRevision"], str) or not REV_RE.fullmatch(root["sourceRevision"]):
        raise CatalogueError("source root: sourceRevision must be a 40-character lowercase Git SHA")
    strings = root["strings"]
    if not isinstance(strings, dict) or not strings:
        raise CatalogueError("source root: strings must be a non-empty object")
    if list(strings) != sorted(strings):
        raise CatalogueError("source root: strings must use canonical key order")
    for key, record in strings.items():
        if not KEY_RE.fullmatch(key) or not isinstance(record, dict):
            raise CatalogueError(f"invalid source record: {key}")
        exact_keys(record, SOURCE_RECORD_KEYS, key)
        text = record["text"]
        if not isinstance(text, str) or not text:
            raise CatalogueError(f"{key}: text must be non-empty")
        if record["sourceHash"] != source_hash(text):
            raise CatalogueError(f"{key}: sourceHash mismatch")
        if record["surface"] not in SOURCE_SURFACES or not isinstance(record["context"], str) or not record["context"].strip():
            raise CatalogueError(f"{key}: invalid surface or context")
        if record["risk"] not in {"ordinary", "setup", "consequential"}:
            raise CatalogueError(f"{key}: invalid risk")
        siblings = string_list(record["siblings"], f"{key}.siblings")
        if any(sibling not in strings for sibling in siblings):
            raise CatalogueError(f"{key}: unknown sibling")
        placeholders = string_list(record["placeholders"], f"{key}.placeholders", distinct=False)
        if placeholders != PLACEHOLDER_RE.findall(text):
            raise CatalogueError(f"{key}: placeholder metadata mismatch")
        frozen = string_list(record["frozen"], f"{key}.frozen")
        if any(token not in text for token in frozen):
            raise CatalogueError(f"{key}: frozen literal missing from English text")
        missing_required = [
            literal for literal in REQUIRED_FROZEN_LITERALS
            if contains_literal(text, literal) and literal not in frozen
        ]
        if missing_required:
            raise CatalogueError(
                f"{key}: required frozen literal missing: {', '.join(missing_required)}"
            )
        soft, hard = record["softMaxChars"], record["hardMaxChars"]
        if not isinstance(soft, int) or not isinstance(hard, int) or soft <= 0 or hard < soft or hard < len(text):
            raise CatalogueError(f"{key}: invalid layout budget")
    return root


def validate_target(
    path: Path,
    source: dict[str, Any],
    *,
    expected_locale: str | None = None,
) -> dict[str, Any]:
    validate_target_script_policies()
    root = read_json(path)
    exact_keys(root, TARGET_ROOT_KEYS, "target root")
    if root["schema"] != SCHEMA or root["locale"] not in LOCALES:
        raise CatalogueError("target root: unsupported schema or locale")
    if expected_locale is not None and root["locale"] != expected_locale:
        raise CatalogueError(f"target root: locale {root['locale']} does not match {expected_locale}")
    if not isinstance(root["sourceRevision"], str) or not REV_RE.fullmatch(root["sourceRevision"]):
        raise CatalogueError("target root: sourceRevision must be a 40-character lowercase Git SHA")
    records = root["strings"]
    if not isinstance(records, dict):
        raise CatalogueError("target root: strings must be an object")
    for key, record in records.items():
        if not KEY_RE.fullmatch(key) or not isinstance(record, dict):
            raise CatalogueError(f"target contains invalid record: {key}")
        exact_keys(record, TARGET_RECORD_KEYS, key)
        text = record["text"]
        if not isinstance(text, str) or not text:
            raise CatalogueError(f"{key}: target text must be non-empty")
        validate_target_text_hygiene(key, text)
        if len(text) > MAX_TARGET_TEXT_CHARS:
            raise CatalogueError(f"{key}: target text is unreasonably large")
        if not isinstance(record["sourceHash"], str) or not SHA_RE.fullmatch(record["sourceHash"]):
            raise CatalogueError(f"{key}: invalid source hash")
        if record["state"] not in STATES:
            raise CatalogueError(f"{key}: invalid state")
        source_record = source["strings"].get(key)
        if source_record is None or record["sourceHash"] != source_record["sourceHash"]:
            continue
        if record["state"] == "english-fallback" and text != source_record["text"]:
            raise CatalogueError(f"{key}: English fallback does not equal its source")
        if Counter(PLACEHOLDER_RE.findall(text)) != Counter(source_record["placeholders"]):
            raise CatalogueError(f"{key}: changed placeholders")
        if any(text.count(token) != source_record["text"].count(token) for token in source_record["frozen"]):
            raise CatalogueError(f"{key}: changed frozen literal")
        if len(text) > source_record["hardMaxChars"]:
            raise CatalogueError(f"{key}: hard length budget exceeded")
        if record["state"] != "english-fallback":
            validate_target_language(key, text, root["locale"], source_record)
    return root


def git_head(worktree: Path) -> str:
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=worktree, text=True, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout.strip()
    if not REV_RE.fullmatch(head):
        raise CatalogueError("could not resolve an exact Git HEAD")
    return head


def require_clean(worktree: Path) -> None:
    status = subprocess.run(
        ["git", "status", "--porcelain"], cwd=worktree, text=True, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout
    if status:
        raise CatalogueError("translation input must be exported from a clean committed worktree")


def require_committed_catalogue(worktree: Path, path: Path, relative_path: Path, owner: str) -> None:
    canonical = worktree.resolve() / relative_path
    if path.resolve() != canonical:
        raise CatalogueError(f"{owner} must be the canonical catalogue inside the selected worktree")
    committed = subprocess.run(
        ["git", "show", f"HEAD:{relative_path.as_posix()}"],
        cwd=worktree,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout
    try:
        current = path.read_bytes()
    except OSError as error:
        raise CatalogueError(f"{owner}: {error}") from error
    if current != committed:
        raise CatalogueError(f"{owner} bytes do not match the selected Git HEAD")


def write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(rendered)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def export_batch(source_path: Path, locale: str, output: Path, worktree: Path) -> None:
    if locale not in LOCALES:
        raise CatalogueError(f"unsupported target locale: {locale}")
    require_clean(worktree)
    canonical_source = worktree.resolve() / "app/src/main/assets/i18n/en.json"
    if source_path.resolve() != canonical_source:
        raise CatalogueError("source must be the canonical catalogue inside the selected worktree")
    source = validate_source(source_path)
    source_bytes = source_path.read_bytes()
    committed_source = subprocess.run(
        ["git", "show", "HEAD:app/src/main/assets/i18n/en.json"], cwd=worktree, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout
    if source_bytes != committed_source:
        raise CatalogueError("source catalogue bytes do not match the selected Git HEAD")
    messages = source["strings"]
    records: list[dict[str, Any]] = []
    for key in sorted(messages):
        record = messages[key]
        siblings = [
            {"key": sibling, "english": messages[sibling]["text"]}
            for sibling in record["siblings"]
        ]
        records.append({
            "key": key,
            "english": record["text"],
            "sourceHash": record["sourceHash"],
            "surface": record["surface"],
            "context": record["context"],
            "risk": record["risk"],
            "siblings": siblings,
            "placeholders": record["placeholders"],
            "frozen": record["frozen"],
            "softMaxChars": record["softMaxChars"],
            "hardMaxChars": record["hardMaxChars"],
        })
    batch = {
        "schema": SCHEMA,
        "publicRevision": git_head(worktree),
        "sourceRevision": source["sourceRevision"],
        "sourceCatalogueHash": hashlib.sha256(source_bytes).hexdigest(),
        "sourceLocale": "en",
        "targetLocale": locale,
        "records": records,
    }
    write_json_atomic(output, batch)


def candidate_to_target(source_path: Path, candidate_path: Path, output: Path) -> None:
    source = validate_source(source_path)
    candidate = read_json(candidate_path)
    exact_keys(candidate, CANDIDATE_ROOT_KEYS, "candidate root")
    locale = candidate["targetLocale"]
    if candidate["schema"] != SCHEMA or locale not in LOCALES:
        raise CatalogueError("candidate root: unsupported schema or target locale")
    if candidate["sourceRevision"] != source["sourceRevision"]:
        raise CatalogueError("candidate root: stale sourceRevision")
    catalogue_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
    if candidate["sourceCatalogueHash"] != catalogue_hash:
        raise CatalogueError("candidate root: stale sourceCatalogueHash")
    translations = candidate["translations"]
    if not isinstance(translations, list):
        raise CatalogueError("candidate translations must be an array")
    wanted = sorted(source["strings"])
    if len(translations) != len(wanted):
        raise CatalogueError("candidate does not have exact source coverage")
    target_strings: dict[str, Any] = {}
    for index, (expected_key, item) in enumerate(zip(wanted, translations)):
        if not isinstance(item, dict):
            raise CatalogueError(f"candidate record {index} must be an object")
        exact_keys(item, CANDIDATE_RECORD_KEYS, f"candidate record {index}")
        if item["key"] != expected_key:
            raise CatalogueError(f"candidate record {index}: expected {expected_key}, got {item['key']}")
        translation = item["translation"]
        if not isinstance(translation, str) or not translation:
            raise CatalogueError(f"{expected_key}: empty translation")
        source_record = source["strings"][expected_key]
        if Counter(PLACEHOLDER_RE.findall(translation)) != Counter(source_record["placeholders"]):
            raise CatalogueError(f"{expected_key}: changed placeholders")
        if any(translation.count(token) != source_record["text"].count(token) for token in source_record["frozen"]):
            raise CatalogueError(f"{expected_key}: changed frozen literal")
        if len(translation) > source_record["hardMaxChars"]:
            raise CatalogueError(f"{expected_key}: hard length budget exceeded")
        validate_target_language(expected_key, translation, locale, source_record)
        target_strings[expected_key] = {
            "text": translation,
            "sourceHash": source_record["sourceHash"],
            "state": "machine-draft",
        }
    target = {
        "schema": SCHEMA,
        "locale": locale,
        "sourceRevision": source["sourceRevision"],
        "strings": target_strings,
    }
    validate_target_value = output.parent / f".{output.name}.validation"
    write_json_atomic(validate_target_value, target)
    try:
        validate_target(validate_target_value, source)
        os.replace(validate_target_value, output)
    finally:
        validate_target_value.unlink(missing_ok=True)


def merge_candidate(
    source_path: Path,
    base_target_path: Path,
    candidate_path: Path,
    output: Path,
) -> None:
    source = validate_source(source_path)
    base = validate_target(base_target_path, source, expected_locale=base_target_path.stem)
    candidate = read_json(candidate_path)
    exact_keys(candidate, CANDIDATE_ROOT_KEYS, "candidate root")
    locale = candidate["targetLocale"]
    if candidate["schema"] != SCHEMA or locale not in LOCALES or locale != base["locale"]:
        raise CatalogueError("candidate root: unsupported or mismatched target locale")
    if candidate["sourceRevision"] != source["sourceRevision"]:
        raise CatalogueError("candidate root: stale sourceRevision")
    catalogue_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
    if candidate["sourceCatalogueHash"] != catalogue_hash:
        raise CatalogueError("candidate root: stale sourceCatalogueHash")
    translations = candidate["translations"]
    if not isinstance(translations, list) or not translations:
        raise CatalogueError("partial candidate translations must be a non-empty array")
    selected: dict[str, str] = {}
    previous_key: str | None = None
    for index, item in enumerate(translations):
        if not isinstance(item, dict):
            raise CatalogueError(f"candidate record {index} must be an object")
        exact_keys(item, CANDIDATE_RECORD_KEYS, f"candidate record {index}")
        key, translation = item["key"], item["translation"]
        if key not in source["strings"]:
            raise CatalogueError(f"candidate record {index}: unknown key {key}")
        if previous_key is not None and key <= previous_key:
            raise CatalogueError("partial candidate keys must be unique and in canonical order")
        previous_key = key
        if not isinstance(translation, str) or not translation:
            raise CatalogueError(f"{key}: empty translation")
        old = base["strings"].get(key)
        source_record = source["strings"][key]
        if old and old["state"] == "community-corrected" and old["sourceHash"] == source_record["sourceHash"]:
            raise CatalogueError(f"{key}: current community correction is protected")
        if Counter(PLACEHOLDER_RE.findall(translation)) != Counter(source_record["placeholders"]):
            raise CatalogueError(f"{key}: changed placeholders")
        if any(translation.count(token) != source_record["text"].count(token) for token in source_record["frozen"]):
            raise CatalogueError(f"{key}: changed frozen literal")
        if len(translation) > source_record["hardMaxChars"]:
            raise CatalogueError(f"{key}: hard length budget exceeded")
        validate_target_language(key, translation, locale, source_record)
        selected[key] = translation

    # Removed source keys are never renderable. Drop them from the public merged artifact; any
    # review value worth retaining belongs in the candidate plan/evidence, not an accumulating
    # runtime catalogue orphan.
    merged_strings = {
        key: value for key, value in base["strings"].items()
        if key in source["strings"]
    }
    for key, translation in selected.items():
        source_record = source["strings"][key]
        merged_strings[key] = {
            "text": translation,
            "sourceHash": source_record["sourceHash"],
            "state": "machine-draft",
        }
    merged = {
        "schema": SCHEMA,
        "locale": locale,
        "sourceRevision": source["sourceRevision"],
        "strings": merged_strings,
    }
    validation_path = output.parent / f".{output.name}.validation"
    write_json_atomic(validation_path, merged)
    try:
        validate_target(validation_path, source, expected_locale=locale)
        os.replace(validation_path, output)
    finally:
        validation_path.unlink(missing_ok=True)


def paths_alias(first: Path, second: Path) -> bool:
    if first.resolve() == second.resolve() or entry_identity(first) == entry_identity(second):
        return True
    try:
        return first.exists() and second.exists() and os.path.samefile(first, second)
    except OSError:
        return False


def read_community_replacement(path: Path, key: str) -> str:
    try:
        with path.open("rb") as handle:
            data = handle.read(MAX_REPLACEMENT_FILE_BYTES + 1)
    except OSError as error:
        raise CatalogueError(f"{path}: {error}") from error
    if len(data) > MAX_REPLACEMENT_FILE_BYTES:
        raise CatalogueError(f"{key}: community correction is unreasonably large")
    try:
        replacement = data.decode("utf-8")
    except UnicodeError as error:
        raise CatalogueError(f"{path}: {error}") from error
    if replacement.endswith("\r\n"):
        replacement = replacement[:-2]
    elif replacement.endswith("\n"):
        replacement = replacement[:-1]
    if not replacement:
        raise CatalogueError(f"{key}: empty community correction")
    if len(replacement) > MAX_TARGET_TEXT_CHARS:
        raise CatalogueError(f"{key}: community correction is unreasonably large")
    return replacement


def apply_community_correction(
    worktree: Path,
    source_path: Path,
    base_target_path: Path,
    locale: str,
    key: str,
    expected_source_hash: str,
    expected_target_hash: str,
    replacement_path: Path,
    output: Path,
) -> None:
    if locale not in LOCALES:
        raise CatalogueError(f"unsupported target locale: {locale}")
    if not SHA_RE.fullmatch(expected_source_hash):
        raise CatalogueError("expected source hash must be a lowercase SHA-256")
    if not SHA_RE.fullmatch(expected_target_hash):
        raise CatalogueError("expected target hash must be a lowercase SHA-256")
    require_clean(worktree)
    worktree_root = worktree.resolve()
    resolved_output = output.resolve()
    if resolved_output == worktree_root or worktree_root in resolved_output.parents:
        raise CatalogueError("correction output must be outside the selected worktree")
    catalogue_dir = Path("app/src/main/assets/i18n")
    require_committed_catalogue(worktree, source_path, catalogue_dir / "en.json", "source")
    require_committed_catalogue(
        worktree,
        base_target_path,
        catalogue_dir / f"{locale}.json",
        "target",
    )
    for input_path, owner in (
        (source_path, "source catalogue"),
        (base_target_path, "target catalogue"),
        (replacement_path, "replacement file"),
    ):
        if paths_alias(output, input_path):
            raise CatalogueError(f"correction output must not overwrite the {owner}")
    if output.exists() or output.is_symlink():
        raise CatalogueError("correction output already exists")

    source = validate_source(source_path)
    base = validate_target(base_target_path, source, expected_locale=locale)
    source_record = source["strings"].get(key)
    if source_record is None:
        raise CatalogueError(f"unknown source key: {key}")
    current = base["strings"].get(key)
    if current is None:
        raise CatalogueError(f"target has no current record for key: {key}")
    if source_record["sourceHash"] != expected_source_hash:
        raise CatalogueError(f"{key}: expected English source hash is stale")
    if current["sourceHash"] != source_record["sourceHash"]:
        raise CatalogueError(f"{key}: target record is stale against the English source")
    if source_hash(current["text"]) != expected_target_hash:
        raise CatalogueError(f"{key}: expected current target hash is stale")
    if current["state"] == "community-corrected":
        raise CatalogueError(f"{key}: current community correction is protected")
    replacement = read_community_replacement(replacement_path, key)

    corrected = {
        "schema": SCHEMA,
        "locale": locale,
        "sourceRevision": source["sourceRevision"],
        "strings": dict(base["strings"]),
    }
    corrected["strings"][key] = {
        "text": replacement,
        "sourceHash": source_record["sourceHash"],
        "state": "community-corrected",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        dir=output.parent,
        prefix=f".{output.name}.validation.",
        delete=False,
    ) as handle:
        validation_path = Path(handle.name)
        handle.write(json.dumps(corrected, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    try:
        validate_target(validation_path, source, expected_locale=locale)
        try:
            os.link(validation_path, output)
        except FileExistsError as error:
            raise CatalogueError("correction output already exists") from error
        except OSError as error:
            raise CatalogueError(f"could not create correction output: {error}") from error
    finally:
        validation_path.unlink(missing_ok=True)


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def coverage(
    count: int,
    total: int,
    *,
    keys: set[str] | None = None,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "count": count,
        "percent": round(count * 100 / total, 2),
    }
    if keys is not None:
        result["keys"] = sorted(keys)
    return result


def grouped_counts(
    source_strings: dict[str, Any],
    records: dict[str, Any],
    stale_keys: set[str],
    translated_keys: set[str],
    dimension: str,
) -> dict[str, Any]:
    groups: dict[str, set[str]] = {}
    for key, record in source_strings.items():
        groups.setdefault(record[dimension], set()).add(key)

    result: dict[str, Any] = {}
    record_keys = set(records)
    for name in sorted(groups):
        keys = groups[name]
        present_keys = keys & record_keys
        group_stale = keys & stale_keys
        group_current = present_keys - group_stale
        group_translated = keys & translated_keys
        states = Counter(records[key]["state"] for key in present_keys)
        result[name] = {
            "source": len(keys),
            "stateCounts": {state: states[state] for state in sorted(STATES)},
            "missing": len(keys - record_keys),
            "stale": len(group_stale),
            "current": len(group_current),
            "translated": len(group_translated),
            "fallback": len(keys - group_translated),
        }
    return result


def report_context_path(source_path: Path, context_path: Path | None) -> Path | None:
    if context_path is not None:
        return context_path
    candidate = source_path.parent / "context" / "home-assistant-terminology.json"
    return candidate if candidate.is_file() else None


def context_report(path: Path) -> dict[str, Any]:
    root = read_json(path)
    exact_keys(root, CONTEXT_ROOT_KEYS, "terminology context root")
    if (
        root["schema"] != SCHEMA
        or not isinstance(root["id"], str)
        or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", root["id"])
        or not isinstance(root["productContext"], str)
        or not root["productContext"].strip()
        or not isinstance(root["instruction"], str)
        or not root["instruction"].strip()
        or root["license"] != "Apache-2.0"
        or not isinstance(root["notice"], str)
        or not root["notice"].strip()
        or not isinstance(root["sources"], list)
        or not root["sources"]
        or not isinstance(root["terms"], list)
        or not root["terms"]
    ):
        raise CatalogueError("malformed terminology context")

    pins: list[dict[str, str]] = []
    source_ids: list[str] = []
    for source in root["sources"]:
        if not isinstance(source, dict):
            raise CatalogueError("malformed terminology context source")
        exact_keys(source, CONTEXT_SOURCE_KEYS, "terminology context source")
        if (
            not isinstance(source["id"], str)
            or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", source["id"])
            or not isinstance(source["repository"], str)
            or not source["repository"].startswith("https://github.com/home-assistant/")
            or not isinstance(source["revision"], str)
            or not REV_RE.fullmatch(source["revision"])
            or not isinstance(source["artifact"], str)
            or not source["artifact"].strip()
            or not isinstance(source["artifactSha256"], str)
            or not SHA_RE.fullmatch(source["artifactSha256"])
            or source["license"] != "Apache-2.0"
        ):
            raise CatalogueError("malformed terminology context source pin")
        source_ids.append(source["id"])
        pins.append({
            "id": source["id"],
            "revision": source["revision"],
            "artifactSha256": source["artifactSha256"],
        })
    if source_ids != sorted(source_ids) or len(source_ids) != len(set(source_ids)):
        raise CatalogueError("terminology context sources are not unique canonical ids")

    term_ids: list[str] = []
    known_sources = set(source_ids)
    for term in root["terms"]:
        if not isinstance(term, dict):
            raise CatalogueError("malformed terminology context term")
        exact_keys(term, CONTEXT_TERM_KEYS, "terminology context term")
        translations = term["translations"]
        if (
            not isinstance(term["id"], str)
            or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", term["id"])
            or not isinstance(term["meaning"], str)
            or not term["meaning"].strip()
            or not isinstance(term["english"], str)
            or not term["english"].strip()
            or not isinstance(term["source"], str)
            or term["source"] not in known_sources
            or not isinstance(term["sourceKey"], str)
            or not term["sourceKey"].strip()
            or not isinstance(translations, dict)
            or set(translations) != LOCALES
            or any(not isinstance(text, str) or not text.strip() for text in translations.values())
        ):
            raise CatalogueError("malformed terminology context term value")
        if list(translations) != sorted(translations):
            raise CatalogueError("terminology context translations are not in canonical locale order")
        term_ids.append(term["id"])
    if term_ids != sorted(term_ids) or len(term_ids) != len(set(term_ids)):
        raise CatalogueError("terminology context terms are not unique canonical ids")

    return {
        "id": root["id"],
        "fileSha256": file_hash(path),
        "terms": len(root["terms"]),
        "sourcePins": pins,
    }


def catalogue_report(
    source_path: Path,
    target_paths: list[Path],
    context_path: Path | None = None,
) -> dict[str, Any]:
    """Return deterministic per-locale catalogue and runtime-fallback counts."""
    if not target_paths:
        raise CatalogueError("report requires at least one target catalogue")
    source = validate_source(source_path)
    source_strings = source["strings"]
    source_keys = set(source_strings)
    total = len(source_keys)
    locales: dict[str, Any] = {}

    for path in target_paths:
        target = validate_target(path, source, expected_locale=path.stem)
        locale = target["locale"]
        if locale in locales:
            raise CatalogueError(f"duplicate target locale: {locale}")

        records = target["strings"]
        record_keys = set(records)
        present_keys = source_keys & record_keys
        missing_keys = source_keys - record_keys
        stale_keys = {
            key for key in present_keys
            if records[key]["sourceHash"] != source_strings[key]["sourceHash"]
        }
        current_keys = present_keys - stale_keys
        renderable_states = (
            RENDERABLE_STATES | {"machine-draft"} if locale in EARLY_ACCESS_LOCALES else RENDERABLE_STATES
        )
        translated_keys = {
            key for key in current_keys
            if records[key]["state"] in renderable_states
        }
        fallback_keys = source_keys - translated_keys
        state_counts = Counter(record["state"] for record in records.values())

        locales[locale] = {
            "catalogueRecords": len(records),
            "fileSha256": file_hash(path),
            "sourceRevision": target["sourceRevision"],
            "sourceRevisionMatches": target["sourceRevision"] == source["sourceRevision"],
            "stateCounts": {state: state_counts[state] for state in sorted(STATES)},
            "surfaces": grouped_counts(
                source_strings, records, stale_keys, translated_keys, "surface",
            ),
            "risks": grouped_counts(
                source_strings, records, stale_keys, translated_keys, "risk",
            ),
            "missing": coverage(len(missing_keys), total, keys=missing_keys),
            "stale": coverage(len(stale_keys), total, keys=stale_keys),
            "current": coverage(len(current_keys), total),
            "translated": coverage(len(translated_keys), total),
            "fallback": coverage(len(fallback_keys), total),
            "extra": len(record_keys - source_keys),
        }

    resolved_context = report_context_path(source_path, context_path)
    result = {
        "schema": SCHEMA,
        "source": {
            "locale": source["locale"],
            "revision": source["sourceRevision"],
            "fileSha256": file_hash(source_path),
            "strings": total,
            "surfaceCounts": dict(sorted(Counter(
                record["surface"] for record in source_strings.values()
            ).items())),
            "riskCounts": dict(sorted(Counter(
                record["risk"] for record in source_strings.values()
            ).items())),
        },
        "locales": {locale: locales[locale] for locale in sorted(locales)},
    }
    if resolved_context is not None:
        result["context"] = context_report(resolved_context)
    return result


def selected_targets(
    source_path: Path,
    target_paths: list[Path],
    target_dir: Path | None,
) -> list[Path]:
    selected = list(target_paths)
    if target_dir:
        selected.extend(
            path for path in sorted(target_dir.glob("*.json"))
            if path.resolve() != source_path.resolve()
        )
    return selected


def entry_identity(path: Path) -> tuple[Path, str]:
    absolute = Path(os.path.abspath(path))
    return absolute.parent.resolve(), absolute.name


def report_targets(
    source_path: Path,
    target_paths: list[Path],
    target_dir: Path | None,
    output: Path | None,
    context_path: Path | None = None,
) -> list[Path]:
    selected = selected_targets(source_path, target_paths, target_dir)
    if context_path is not None:
        context_entry = entry_identity(context_path)
        selected = [
            path for path in selected
            if entry_identity(path) != context_entry
        ]
    if output is None:
        return selected

    output_path = output.resolve()
    if output_path == source_path.resolve():
        raise CatalogueError("report output must not overwrite the source catalogue")
    if context_path is not None and output_path == context_path.resolve():
        raise CatalogueError("report output must not overwrite the context artifact")
    if any(output_path == path.resolve() for path in target_paths):
        raise CatalogueError("report output must not overwrite a target catalogue")
    if (
        target_dir is not None
        and output_path.parent == target_dir.resolve()
        and output_path.stem in LOCALES
    ):
        raise CatalogueError("report output must not overwrite a target catalogue")

    output_entry = entry_identity(output)
    selected = [
        path for path in selected
        if entry_identity(path) != output_entry
    ]
    if any(output_path == path.resolve() for path in selected):
        raise CatalogueError("report output must not overwrite a target catalogue")
    return selected


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    sub = result.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("--source", type=Path, required=True)
    validate.add_argument("--target", type=Path, action="append", default=[])
    validate.add_argument("--target-dir", type=Path)
    report = sub.add_parser(
        "report",
        help="report per-locale catalogue state, currency, and runtime fallback coverage as JSON",
    )
    report.add_argument("--source", type=Path, required=True)
    report.add_argument("--target", type=Path, action="append", default=[])
    report.add_argument("--target-dir", type=Path)
    report.add_argument("--context", type=Path)
    report.add_argument("--output", type=Path)
    export = sub.add_parser("export")
    export.add_argument("--source", type=Path, required=True)
    export.add_argument("--locale", required=True)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--worktree", type=Path, default=Path.cwd())
    candidate = sub.add_parser("validate-candidate")
    candidate.add_argument("--source", type=Path, required=True)
    candidate.add_argument("--candidate", type=Path, required=True)
    candidate.add_argument("--output", type=Path, required=True)
    merge = sub.add_parser("merge-candidate")
    merge.add_argument("--source", type=Path, required=True)
    merge.add_argument("--base-target", type=Path, required=True)
    merge.add_argument("--candidate", type=Path, required=True)
    merge.add_argument("--output", type=Path, required=True)
    correction = sub.add_parser(
        "apply-community-correction",
        help="apply one reviewed contributor correction to a separate target catalogue output",
    )
    correction.add_argument("--source", type=Path, required=True)
    correction.add_argument("--worktree", type=Path, default=Path.cwd())
    correction.add_argument("--base-target", type=Path, required=True)
    correction.add_argument("--locale", required=True)
    correction.add_argument("--key", required=True)
    correction.add_argument("--expected-source-hash", required=True)
    correction.add_argument("--expected-target-hash", required=True)
    correction.add_argument("--replacement-file", type=Path, required=True)
    correction.add_argument("--output", type=Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "validate":
            source = validate_source(args.source)
            for target in selected_targets(args.source, args.target, args.target_dir):
                validate_target(target, source, expected_locale=target.stem)
        elif args.command == "report":
            context_path = report_context_path(args.source, args.context)
            report = catalogue_report(
                args.source,
                report_targets(
                    args.source,
                    args.target,
                    args.target_dir,
                    args.output,
                    context_path,
                ),
                context_path,
            )
            if args.output:
                write_json_atomic(args.output, report)
            else:
                print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
        elif args.command == "export":
            export_batch(args.source, args.locale, args.output, args.worktree)
        elif args.command == "validate-candidate":
            candidate_to_target(args.source, args.candidate, args.output)
        elif args.command == "merge-candidate":
            merge_candidate(args.source, args.base_target, args.candidate, args.output)
        elif args.command == "apply-community-correction":
            apply_community_correction(
                args.worktree,
                args.source,
                args.base_target,
                args.locale,
                args.key,
                args.expected_source_hash,
                args.expected_target_hash,
                args.replacement_file,
                args.output,
            )
        else:
            raise CatalogueError(f"unsupported command: {args.command}")
    except (CatalogueError, subprocess.CalledProcessError) as error:
        print(f"i18n catalogue error: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
