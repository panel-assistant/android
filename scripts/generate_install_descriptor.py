#!/usr/bin/env python3
"""Generate V1 install and protocol companion records from exact signed APKs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import NoReturn

# The descriptor names the successor package, the identity the integration installs. Three strings
# below used to be derived from it and must not be: the Gradle namespace does not move with the
# applicationId, and the schema and database-compatibility contracts are compared byte for byte by
# shipped verifiers that predate the move.
SCHEMA = "io.github.maxlyth.hapaneld.install.v1"
PACKAGE_ID = "io.panelassistant.android"
# The Kotlin package and Gradle namespace. Every manifest class stays here whichever applicationId the
# build carries, so a component is always `<applicationId>/<fully.qualified.Class>`.
CODE_PACKAGE = "io.github.maxlyth.hapaneld"
MAX_APK_SIZE_BYTES = 64 * 1024 * 1024
MAX_ANDROID_SDK = 100
MAX_ANDROID_VERSION_CODE = 2**31 - 1
SIGNER_CERTIFICATE_SHA256 = (
    "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
)
DATABASE_METADATA_KEY = f"{CODE_PACKAGE}.DATABASE_COMPATIBILITY"
PROTOCOL_METADATA_KEY = f"{CODE_PACKAGE}.PANEL_ASSISTANT_PROTOCOL"
PROTOCOL_SCHEMA = "io.github.maxlyth.hapaneld.protocol.v1"
SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a")
LAUNCH_ACTIVITY = f"{CODE_PACKAGE}.MainActivity"
# Fully qualified on purpose. The `/.MainActivity` shorthand resolves against the applicationId, so
# under the successor id it would name a class that does not exist.
LAUNCH_COMPONENT = f"{PACKAGE_ID}/{LAUNCH_ACTIVITY}"
RELEASE_TAG_PATTERN = re.compile(
    r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-rc[1-9][0-9]*)?$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
ATTRIBUTE_PATTERN = re.compile(
    r"(?:android|http://schemas\.android\.com/apk/res/android):"
    r"(?P<name>name|value)(?:\([^)]*\))?=\"(?P<value>[^\"]*)\""
)


class DescriptorError(ValueError):
    """The APK cannot be represented by the closed install descriptor contract."""


def _fail(message: str) -> NoReturn:
    raise DescriptorError(message)


def _run(tool: Path, apk_fd: int, *args: str) -> str:
    result = subprocess.run(
        [str(tool), *args],
        check=False,
        pass_fds=(apk_fd,),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        _fail(f"{tool.name} failed while inspecting the release APK")
    return result.stdout


def _quoted_fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)='([^']*)'", line))


def _one_line(prefix: str, text: str) -> str:
    matches = [line for line in text.splitlines() if line.startswith(prefix)]
    if len(matches) != 1:
        _fail(f"expected exactly one {prefix.rstrip(':')} record")
    return matches[0]


def _require_path_identity(apk: Path, opened: os.stat_result) -> None:
    try:
        current = apk.lstat()
    except OSError:
        _fail("release APK pathname is no longer available")
    if not stat.S_ISREG(current.st_mode) or (current.st_dev, current.st_ino) != (
        opened.st_dev,
        opened.st_ino,
    ):
        _fail("release APK pathname no longer identifies the opened file")


def _apk_identity(apk_fd: int) -> tuple[int, str]:
    size = 0
    digest = hashlib.sha256()
    try:
        before = os.fstat(apk_fd)
        if not stat.S_ISREG(before.st_mode):
            _fail("release APK is not a regular file")
        if before.st_size <= 0:
            _fail("release APK is missing or empty")
        if before.st_size > MAX_APK_SIZE_BYTES:
            _fail("release APK exceeds the 64 MiB installer limit")
        os.lseek(apk_fd, 0, os.SEEK_SET)
        while chunk := os.read(apk_fd, 1024 * 1024):
            size += len(chunk)
            digest.update(chunk)
        after = os.fstat(apk_fd)
    except OSError:
        _fail("release APK cannot be read")
    if (before.st_dev, before.st_ino, before.st_size) != (
        after.st_dev,
        after.st_ino,
        after.st_size,
    ) or size != before.st_size:
        _fail("release APK changed while it was read")
    return size, digest.hexdigest()


def parse_badging(
    badging: str, allowed_packages: tuple[str, ...] = (PACKAGE_ID,)
) -> dict[str, object]:
    """Read the closed package/version/platform/launch contract from aapt output."""
    package_fields = _quoted_fields(_one_line("package:", badging))
    package_id = package_fields.get("name", "")
    version_name = package_fields.get("versionName", "")
    version_code_text = package_fields.get("versionCode", "")
    if package_id not in allowed_packages:
        _fail(f"unexpected package ID: {package_id or '<missing>'}")
    if not version_name:
        _fail("APK versionName is missing")
    if (
        not re.fullmatch(r"[1-9][0-9]*", version_code_text)
        or len(version_code_text) > 10
        or int(version_code_text) > MAX_ANDROID_VERSION_CODE
    ):
        _fail("APK versionCode is not a positive decimal integer")

    sdk_line = _one_line("sdkVersion:", badging)
    sdk_match = re.fullmatch(r"sdkVersion:'([^']*)'", sdk_line)
    min_sdk_text = sdk_match.group(1) if sdk_match else ""
    if (
        not re.fullmatch(r"[1-9][0-9]*", min_sdk_text)
        or len(min_sdk_text) > 3
        or int(min_sdk_text) > MAX_ANDROID_SDK
    ):
        _fail("APK minSdk is not a positive decimal integer")

    launch_fields = _quoted_fields(_one_line("launchable-activity:", badging))
    if launch_fields.get("name") != LAUNCH_ACTIVITY:
        _fail("APK launcher is not the canonical panel MainActivity")

    native_line = _one_line("native-code:", badging)
    abis = tuple(sorted(re.findall(r"'([^']*)'", native_line)))
    if len(abis) != len(set(abis)) or abis != SUPPORTED_ABIS:
        _fail("APK native-code ABI set does not match the supported panel contract")

    return {
        "minSdk": int(min_sdk_text),
        "packageId": package_id,
        "supportedAbis": list(abis),
        "versionCode": int(version_code_text),
        "versionName": version_name,
    }


def parse_application_metadata(xmltree: str, key: str, label: str) -> str:
    """Read one unique direct application metadata value from aapt xmltree output."""
    stack: dict[int, tuple[str, int]] = {}
    next_node_id = 0
    manifest_count = 0
    manifest_node_id: int | None = None
    application_count = 0
    application_node_id: int | None = None
    contracts: list[str] = []
    wrong_scope = False
    current: tuple[bool, dict[str, list[str]]] | None = None

    def flush() -> None:
        nonlocal current, wrong_scope
        if current is None:
            return
        direct_application_child, attributes = current
        if key in attributes["name"]:
            if (
                not direct_application_child
                or attributes["name"] != [key]
                or len(attributes["value"]) != 1
            ):
                wrong_scope = True
            else:
                contracts.append(attributes["value"][0])
        current = None

    for line in xmltree.splitlines():
        entity_match = re.match(r"^( *)(?:E:)\s+([^\s(]+)", line)
        if entity_match:
            flush()
            indent = len(entity_match.group(1))
            entity = entity_match.group(2)
            parent_indent = max((level for level in stack if level < indent), default=None)
            parent, parent_node_id = stack[parent_indent] if parent_indent is not None else ("", None)
            stack = {level: value for level, value in stack.items() if level < indent}
            next_node_id += 1
            node_id = next_node_id
            stack[indent] = (entity, node_id)
            if entity == "manifest" and parent_indent is None:
                manifest_count += 1
                manifest_node_id = node_id
            if (
                entity == "application"
                and parent == "manifest"
                and parent_node_id == manifest_node_id
            ):
                application_count += 1
                application_node_id = node_id
            if entity == "meta-data":
                current = (
                    parent == "application" and parent_node_id == application_node_id,
                    {"name": [], "value": []},
                )
            continue
        if current is not None:
            attribute_match = ATTRIBUTE_PATTERN.search(line)
            if attribute_match:
                current[1][attribute_match.group("name")].append(attribute_match.group("value"))
    flush()

    if manifest_count != 1 or application_count != 1 or wrong_scope or len(contracts) != 1:
        _fail(f"APK must contain one application-scoped {label} record")
    return contracts[0]


def parse_database_compatibility(xmltree: str) -> str:
    contract = parse_application_metadata(xmltree, DATABASE_METADATA_KEY, "database compatibility")
    match = re.fullmatch(r"hapaneld-db:v1:ha-paneld\.db:([1-9][0-9]*):([1-9][0-9]*)", contract)
    if (
        not match
        or any(len(value) > 10 for value in match.groups())
        or not 1
        <= int(match.group(1))
        <= int(match.group(2))
        <= MAX_ANDROID_VERSION_CODE
    ):
        _fail("APK database compatibility record is malformed")
    return contract


def parse_protocol_range(xmltree: str) -> tuple[int, int]:
    contract = parse_application_metadata(xmltree, PROTOCOL_METADATA_KEY, "Panel Assistant protocol")
    match = re.fullmatch(r"hapaneld-native:v1:([1-9][0-9]*):([1-9][0-9]*)", contract)
    if (
        not match
        or any(len(value) > 10 for value in match.groups())
        or not 1 <= int(match.group(1)) <= int(match.group(2)) <= MAX_ANDROID_VERSION_CODE
    ):
        _fail("APK Panel Assistant protocol record is malformed")
    return int(match.group(1)), int(match.group(2))


def parse_signer(apksigner_output: str) -> str:
    digests = [
        digest.lower()
        for digest in re.findall(
            r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9A-Fa-f]+)$",
            apksigner_output,
            flags=re.MULTILINE,
        )
    ]
    if len(digests) != 1 or not SHA256_PATTERN.fullmatch(digests[0]):
        _fail("APK must have exactly one valid signer certificate digest")
    if digests[0] != SIGNER_CERTIFICATE_SHA256:
        _fail("APK signer certificate does not match the release authority")
    return digests[0]


def inspect_apk(
    apk: Path,
    release_tag: str,
    aapt: Path,
    apksigner: Path,
    allowed_packages: tuple[str, ...] = (PACKAGE_ID,),
) -> tuple[dict[str, object], str, str, tuple[int, str]]:
    if len(release_tag) > 64 or not RELEASE_TAG_PATTERN.fullmatch(release_tag):
        _fail("release tag is not an accepted vX.Y.Z or vX.Y.Z-rcN value")
    try:
        apk_fd = os.open(apk, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    except OSError:
        _fail("release APK cannot be opened as a regular nofollow file")
    try:
        opened = os.fstat(apk_fd)
        if not stat.S_ISREG(opened.st_mode):
            _fail("release APK is not a regular file")
        _require_path_identity(apk, opened)
        initial_identity = _apk_identity(apk_fd)
        fd_path = f"/proc/self/fd/{apk_fd}"

        badging = _run(aapt, apk_fd, "dump", "badging", fd_path)
        _require_path_identity(apk, opened)
        fields = parse_badging(badging, allowed_packages)
        expected_version_name = release_tag.removeprefix("v")
        if fields["versionName"] != expected_version_name:
            _fail("APK versionName does not match the release tag")
        xmltree = _run(aapt, apk_fd, "dump", "xmltree", fd_path, "AndroidManifest.xml")
        _require_path_identity(apk, opened)
        signer = parse_signer(_run(apksigner, apk_fd, "verify", "--print-certs", fd_path))
        _require_path_identity(apk, opened)
        final_identity = _apk_identity(apk_fd)
        if final_identity != initial_identity:
            _fail("release APK changed while its descriptor was generated")
    finally:
        os.close(apk_fd)
    return fields, xmltree, signer, final_identity


def build_descriptor(apk: Path, release_tag: str, aapt: Path, apksigner: Path) -> dict[str, object]:
    canonical_apk_name = f"panel-assistant-{release_tag}-manual-setup-required.apk"
    if apk.name != canonical_apk_name:
        _fail("APK filename is not canonical for the release tag")
    fields, xmltree, signer, final_identity = inspect_apk(apk, release_tag, aapt, apksigner)

    return {
        "apkName": canonical_apk_name,
        "apkSha256": final_identity[1],
        "apkSize": final_identity[0],
        "databaseCompatibility": parse_database_compatibility(xmltree),
        "launchComponent": LAUNCH_COMPONENT,
        "minSdk": fields["minSdk"],
        "packageId": fields["packageId"],
        "releaseTag": release_tag,
        "schema": SCHEMA,
        "signerCertificateSha256": signer,
        "supportedAbis": fields["supportedAbis"],
        "versionCode": fields["versionCode"],
        "versionName": fields["versionName"],
    }


def build_protocol_manifest(
    apks: list[Path], release_tag: str, aapt: Path, apksigner: Path
) -> dict[str, object]:
    if not apks or len(apks) > 500:
        _fail("protocol companion requires 1 to 500 exact APKs")
    artifacts = []
    for apk in apks:
        _, xmltree, _, identity = inspect_apk(
            apk, release_tag, aapt, apksigner, (CODE_PACKAGE, PACKAGE_ID)
        )
        low, high = parse_protocol_range(xmltree)
        artifacts.append({"apkSha256": identity[1], "protocolMin": low, "protocolMax": high})
    if len({record["apkSha256"] for record in artifacts}) != len(artifacts):
        _fail("protocol companion repeats an APK hash")
    return {"schema": PROTOCOL_SCHEMA, "artifacts": sorted(artifacts, key=lambda record: record["apkSha256"])}


def canonical_json(descriptor: dict[str, object]) -> bytes:
    """Return the only accepted byte representation for schema v1."""
    return (json.dumps(descriptor, ensure_ascii=True, separators=(",", ":"), sort_keys=True) + "\n").encode()


def write_atomic(output: Path, payload: bytes) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor_fd, descriptor_name = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
    try:
        with os.fdopen(descriptor_fd, "wb") as descriptor_file:
            descriptor_file.write(payload)
            descriptor_file.flush()
            os.fsync(descriptor_file.fileno())
        os.replace(descriptor_name, output)
    except BaseException:
        Path(descriptor_name).unlink(missing_ok=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--protocol-apk", action="append", default=[], type=Path)
    parser.add_argument("--protocol-output", type=Path)
    args = parser.parse_args()
    if bool(args.apk) != bool(args.output) or bool(args.protocol_apk) != bool(args.protocol_output):
        parser.error("each output requires its corresponding APK input")
    if not args.output and not args.protocol_output:
        parser.error("an install descriptor or protocol companion output is required")
    try:
        # Validate both before publishing either, so a failed companion cannot leave a descriptor
        # appearing to describe a completed generation run.
        descriptor = build_descriptor(args.apk, args.release_tag, args.aapt, args.apksigner) if args.output else None
        protocol = build_protocol_manifest(args.protocol_apk, args.release_tag, args.aapt, args.apksigner) if args.protocol_output else None
        if args.output:
            write_atomic(args.output, canonical_json(descriptor))
        if args.protocol_output:
            write_atomic(args.protocol_output, canonical_json(protocol))
    except DescriptorError as error:
        print(f"install descriptor: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
