import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "generate_install_descriptor.py"
SPEC = importlib.util.spec_from_file_location("generate_install_descriptor", SCRIPT)
assert SPEC and SPEC.loader
descriptor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(descriptor)

TAG = "v1.2.3-rc1"
APK_NAME = f"panel-assistant-{TAG}-manual-setup-required.apk"
RELEASE_IDENTITY_CORPUS = json.loads(
    (Path(__file__).parent / "fixtures" / "release-identity-corpus.json").read_text(
        encoding="utf-8"
    )
)
ANDROID_PRODUCER_FIXTURE = json.loads(
    (
        Path(__file__).resolve().parents[2]
        / "app/src/test/resources/panel-assistant-contract/android_producer_v1.json"
    ).read_text(encoding="utf-8")
)
ANDROID_PRODUCER_SOURCE_REVISION = "a2951a1a3db623d02d75484363143d3d29b1563c"
BADGING = """\
package: name='io.panelassistant.android' versionCode='701' versionName='1.2.3-rc1' \
platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' \
compileSdkVersionCodename='17'
sdkVersion:'26'
launchable-activity: name='io.github.maxlyth.hapaneld.MainActivity'  label='' icon=''
native-code: 'arm64-v8a' 'armeabi-v7a'
""".replace(" \\\n", " ")
XMLTREE = """\
E: manifest (line=2)
  E: application (line=8)
    E: meta-data (line=10)
      A: android:name(0x01010003)="io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY" (Raw: "io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY")
      A: android:value(0x01010024)="hapaneld-db:v1:ha-paneld.db:11:14" (Raw: "hapaneld-db:v1:ha-paneld.db:11:14")
    E: activity (line=20)
"""
SIGNER = (
    "Signer #1 certificate SHA-256 digest: "
    "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339\n"
)


class InstallDescriptorTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.directory = Path(self.temporary_directory.name)
        self.apk = self.directory / APK_NAME
        self.apk.write_bytes(b"signed release apk\n")

    def completed(self, stdout):
        return subprocess.CompletedProcess([], 0, stdout, "")

    def build(self, badging=BADGING, xmltree=XMLTREE, signer=SIGNER):
        replies = [self.completed(badging), self.completed(xmltree), self.completed(signer)]
        with patch.object(descriptor.subprocess, "run", side_effect=replies) as run:
            result = descriptor.build_descriptor(
                self.apk,
                TAG,
                Path("/tools/aapt"),
                Path("/tools/apksigner"),
            )
        commands = [call.args[0] for call in run.call_args_list]
        fd_path = commands[0][-1]
        self.assertRegex(fd_path, r"^/proc/self/fd/[0-9]+$")
        self.assertEqual(
            [
                ["/tools/aapt", "dump", "badging", fd_path],
                ["/tools/aapt", "dump", "xmltree", fd_path, "AndroidManifest.xml"],
                ["/tools/apksigner", "verify", "--print-certs", fd_path],
            ],
            commands,
        )
        for call in run.call_args_list:
            self.assertEqual((int(fd_path.rsplit("/", 1)[1]),), call.kwargs["pass_fds"])
        return result

    def test_descriptor_binds_the_exact_release_and_install_contract(self):
        actual = self.build()
        expected = {
            "apkName": APK_NAME,
            "apkSha256": hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            "apkSize": self.apk.stat().st_size,
            "databaseCompatibility": "hapaneld-db:v1:ha-paneld.db:11:14",
            "launchComponent": "io.panelassistant.android/io.github.maxlyth.hapaneld.MainActivity",
            "minSdk": 26,
            "packageId": "io.panelassistant.android",
            "releaseTag": TAG,
            "schema": "io.github.maxlyth.hapaneld.install.v1",
            "signerCertificateSha256": (
                "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
            ),
            "supportedAbis": ["arm64-v8a", "armeabi-v7a"],
            "versionCode": 701,
            "versionName": "1.2.3-rc1",
        }
        self.assertEqual(expected, actual)

    def test_canonical_json_is_sorted_compact_ascii_with_one_newline(self):
        payload = descriptor.canonical_json({"z": 1, "a": "caf\N{LATIN SMALL LETTER E WITH ACUTE}"})
        self.assertEqual(b'{"a":"caf\\u00e9","z":1}\n', payload)
        self.assertEqual(payload, descriptor.canonical_json(json.loads(payload)))

    def test_release_tag_must_match_version_name_and_canonical_apk_name(self):
        with self.assertRaisesRegex(descriptor.DescriptorError, "versionName does not match"):
            self.build(badging=BADGING.replace("1.2.3-rc1", "1.2.3"))
        renamed = self.apk.with_name("arbitrary.apk")
        self.apk.rename(renamed)
        with self.assertRaisesRegex(descriptor.DescriptorError, "filename is not canonical"):
            descriptor.build_descriptor(
                renamed,
                TAG,
                Path("/tools/aapt"),
                Path("/tools/apksigner"),
            )

    def test_release_tag_rejects_leading_zeroes_and_excessive_length(self):
        for release_tag in ("v01.2.3", "v1.02.3", "v1.2.03", "v1.2.3-" + "x" * 58):
            invalid_apk = self.directory / f"panel-assistant-{release_tag}-manual-setup-required.apk"
            invalid_apk.write_bytes(self.apk.read_bytes())
            with self.subTest(release_tag=release_tag), self.assertRaisesRegex(
                descriptor.DescriptorError,
                "release tag",
            ):
                descriptor.build_descriptor(
                    invalid_apk,
                    release_tag,
                    Path("/tools/aapt"),
                    Path("/tools/apksigner"),
                )

    def test_shared_release_identity_corpus_matches_the_descriptor_producer(self):
        for case in RELEASE_IDENTITY_CORPUS["tags"]:
            expected = case["kind"] in {"stable", "rc"}
            with self.subTest(release_tag=case["tag"]):
                self.assertEqual(
                    expected,
                    len(case["tag"]) <= 64
                    and descriptor.RELEASE_TAG_PATTERN.fullmatch(case["tag"]) is not None,
                )

    def test_source_stamped_producer_fixture_is_reproducible(self):
        self.assertEqual(
            ANDROID_PRODUCER_SOURCE_REVISION,
            ANDROID_PRODUCER_FIXTURE["sourceRevision"],
        )
        expected_verdicts = [
            {
                "tag": case["tag"],
                "accepted": case["kind"] in {"stable", "rc"},
            }
            for case in RELEASE_IDENTITY_CORPUS["tags"]
        ]
        actual_verdicts = [
            {
                "tag": case["tag"],
                "accepted": len(case["tag"]) <= 64
                and descriptor.RELEASE_TAG_PATTERN.fullmatch(case["tag"]) is not None,
            }
            for case in RELEASE_IDENTITY_CORPUS["tags"]
        ]
        self.assertEqual(expected_verdicts, ANDROID_PRODUCER_FIXTURE["releaseTagVerdicts"])
        self.assertEqual(actual_verdicts, ANDROID_PRODUCER_FIXTURE["releaseTagVerdicts"])
        self.assertEqual(self.build(), ANDROID_PRODUCER_FIXTURE["installDescriptors"][0])

    def test_package_platform_abis_and_launcher_are_closed(self):
        mutations = (
            (BADGING.replace("io.panelassistant.android' versionCode", "example.foreign' versionCode"), "package ID"),
            # The bridge id is a foreign id for this descriptor: a release that generated the
            # descriptor from the bridge APK would hand the integration the package it is migrating
            # away from, under the successor's asset name.
            (BADGING.replace("io.panelassistant.android' versionCode", "io.github.maxlyth.hapaneld' versionCode"), "package ID"),
            (BADGING.replace("sdkVersion:'26'", "sdkVersion:'0'"), "minSdk"),
            (BADGING.replace(" 'arm64-v8a'", " 'x86_64'"), "ABI set"),
            (BADGING.replace(".MainActivity'  label", ".DashboardActivity'  label"), "launcher"),
        )
        for badging, message in mutations:
            with self.subTest(message=message), self.assertRaisesRegex(descriptor.DescriptorError, message):
                descriptor.parse_badging(badging)

    def test_database_contract_must_be_unique_application_metadata(self):
        invalid = (
            XMLTREE.replace(":11:14", ":15:14"),
            XMLTREE + XMLTREE.split("    E: activity", 1)[0].split("  E: application", 1)[1],
            XMLTREE.replace("    E: meta-data", "    E: activity\n      E: meta-data"),
        )
        for xmltree in invalid:
            with self.subTest(xmltree=xmltree), self.assertRaises(descriptor.DescriptorError):
                descriptor.parse_database_compatibility(xmltree)

    def test_database_metadata_rejects_duplicate_attributes_and_nested_application(self):
        duplicate_name = XMLTREE.replace(
            "      A: android:value",
            "      A: android:name(0x01010003)=\"io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY\"\n"
            "      A: android:value",
        )
        duplicate_value = XMLTREE.replace(
            "    E: activity",
            "      A: android:value(0x01010024)=\"hapaneld-db:v1:ha-paneld.db:11:14\"\n"
            "    E: activity",
        )
        nested_application = XMLTREE.replace(
            "    E: meta-data",
            "    E: activity\n      E: application\n        E: meta-data",
        ).replace("      A: android:", "          A: android:")
        nested_manifest = "E: outer\n" + "\n".join(f"  {line}" for line in XMLTREE.splitlines())
        duplicate_manifest = XMLTREE + XMLTREE
        same_indent_foreign_application = """\
E: manifest (line=2)
  E: application (line=8)
E: foreign-root (line=20)
  E: application (line=21)
    E: meta-data (line=22)
      A: android:name(0x01010003)="io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY"
      A: android:value(0x01010024)="hapaneld-db:v1:ha-paneld.db:11:14"
"""
        for xmltree in (
            duplicate_name,
            duplicate_value,
            nested_application,
            nested_manifest,
            duplicate_manifest,
            same_indent_foreign_application,
        ):
            with self.subTest(xmltree=xmltree), self.assertRaises(descriptor.DescriptorError):
                descriptor.parse_database_compatibility(xmltree)

    def test_numeric_fields_enforce_consumer_upper_bounds(self):
        upper_badging = BADGING.replace("versionCode='701'", "versionCode='2147483647'").replace(
            "sdkVersion:'26'",
            "sdkVersion:'100'",
        )
        parsed = descriptor.parse_badging(upper_badging)
        self.assertEqual(2147483647, parsed["versionCode"])
        self.assertEqual(100, parsed["minSdk"])
        for badging in (
            BADGING.replace("versionCode='701'", "versionCode='2147483648'"),
            BADGING.replace("sdkVersion:'26'", "sdkVersion:'101'"),
        ):
            with self.subTest(badging=badging), self.assertRaises(descriptor.DescriptorError):
                descriptor.parse_badging(badging)
        self.assertEqual(
            "hapaneld-db:v1:ha-paneld.db:1:2147483647",
            descriptor.parse_database_compatibility(
                XMLTREE.replace("hapaneld-db:v1:ha-paneld.db:11:14", "hapaneld-db:v1:ha-paneld.db:1:2147483647")
            ),
        )
        with self.assertRaises(descriptor.DescriptorError):
            descriptor.parse_database_compatibility(
                XMLTREE.replace("hapaneld-db:v1:ha-paneld.db:11:14", "hapaneld-db:v1:ha-paneld.db:1:2147483648")
            )

    def test_signer_must_be_one_exact_release_certificate(self):
        with self.assertRaisesRegex(descriptor.DescriptorError, "exactly one"):
            descriptor.parse_signer(SIGNER + SIGNER.replace("#1", "#2"))
        foreign = SIGNER.replace("ac619330", "bc619330")
        with self.assertRaisesRegex(descriptor.DescriptorError, "release authority"):
            descriptor.parse_signer(foreign)

    def test_apk_cannot_change_while_the_tools_inspect_it(self):
        replies = iter((self.completed(BADGING), self.completed(XMLTREE), self.completed(SIGNER)))

        def inspect(*args, **kwargs):
            result = next(replies)
            if args[0][1:3] == ["dump", "badging"]:
                self.apk.write_bytes(b"replaced apk bytes\n")
            return result

        with patch.object(descriptor.subprocess, "run", side_effect=inspect), self.assertRaisesRegex(
            descriptor.DescriptorError,
            "changed while",
        ):
            descriptor.build_descriptor(
                self.apk,
                TAG,
                Path("/tools/aapt"),
                Path("/tools/apksigner"),
            )

    def test_apk_larger_than_the_installer_limit_is_refused_before_inspection(self):
        self.assertEqual(64 * 1024 * 1024, descriptor.MAX_APK_SIZE_BYTES)
        self.apk.write_bytes(b"")
        with self.apk.open("ab") as apk_file:
            apk_file.truncate(64 * 1024 * 1024 + 1)
        with patch.object(descriptor.subprocess, "run") as run, self.assertRaisesRegex(
            descriptor.DescriptorError,
            "exceeds the 64 MiB",
        ):
            descriptor.build_descriptor(
                self.apk,
                TAG,
                Path("/tools/aapt"),
                Path("/tools/apksigner"),
            )
        run.assert_not_called()

    def test_apk_path_must_be_a_regular_nofollow_file(self):
        target = self.directory / "target.apk"
        self.apk.rename(target)
        self.apk.symlink_to(target)
        with self.assertRaisesRegex(descriptor.DescriptorError, "nofollow"):
            descriptor.build_descriptor(
                self.apk,
                TAG,
                Path("/tools/aapt"),
                Path("/tools/apksigner"),
            )

    def test_aba_path_swap_cannot_change_the_opened_apk_seen_by_tools(self):
        original = self.apk.read_bytes()
        backup = self.directory / "original.apk"
        replacement = self.directory / "replacement.apk"
        observed = []
        replies = iter((self.completed(BADGING), self.completed(XMLTREE), self.completed(SIGNER)))

        def inspect(*args, **kwargs):
            fd_path = next(value for value in args[0] if value.startswith("/proc/self/fd/"))
            if not observed:
                self.apk.rename(backup)
                replacement.write_bytes(b"foreign replacement apk\n")
                replacement.rename(self.apk)
                observed.append(Path(fd_path).read_bytes())
                self.apk.rename(replacement)
                backup.rename(self.apk)
            else:
                observed.append(Path(fd_path).read_bytes())
            return next(replies)

        with patch.object(descriptor.subprocess, "run", side_effect=inspect):
            result = descriptor.build_descriptor(
                self.apk,
                TAG,
                Path("/tools/aapt"),
                Path("/tools/apksigner"),
            )
        self.assertEqual([original, original, original], observed)
        self.assertEqual(hashlib.sha256(original).hexdigest(), result["apkSha256"])

    def test_pathname_replacement_during_inspection_is_rejected(self):
        backup = self.directory / "opened.apk"
        replacement = self.directory / "replacement.apk"
        replies = iter((self.completed(BADGING), self.completed(XMLTREE), self.completed(SIGNER)))

        def inspect(*args, **kwargs):
            if args[0][1:3] == ["dump", "badging"]:
                self.apk.rename(backup)
                replacement.write_bytes(b"foreign replacement apk\n")
                replacement.rename(self.apk)
            return next(replies)

        with patch.object(descriptor.subprocess, "run", side_effect=inspect), self.assertRaisesRegex(
            descriptor.DescriptorError,
            "pathname no longer identifies",
        ):
            descriptor.build_descriptor(
                self.apk,
                TAG,
                Path("/tools/aapt"),
                Path("/tools/apksigner"),
            )


if __name__ == "__main__":
    unittest.main()
