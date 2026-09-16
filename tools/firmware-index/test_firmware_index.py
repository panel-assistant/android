#!/usr/bin/env python3

import io
import pathlib
import re
import unittest
import urllib.error
from types import SimpleNamespace
from unittest import mock

import firmware_index


class ResponseTotalSizeTest(unittest.TestCase):
    def test_reads_total_from_partial_response(self):
        response = SimpleNamespace(
            status=206,
            headers={"Content-Range": "bytes 0-0/309567578", "Content-Length": "1"},
        )

        self.assertEqual(firmware_index.response_total_size(response), 309567578)

    def test_rejects_partial_response_without_total(self):
        response = SimpleNamespace(status=206, headers={"Content-Range": "bytes 0-0/*"})

        self.assertIsNone(firmware_index.response_total_size(response))

    def test_reads_full_response_content_length(self):
        response = SimpleNamespace(status=200, headers={"Content-Length": "136037762"})

        self.assertEqual(firmware_index.response_total_size(response), 136037762)


class HistoryTrimTest(unittest.TestCase):
    def test_retry_replaces_the_same_utc_days_initial_sample(self):
        day = 20000 * 86400
        first = {"t": day + 8 * 3600, "r": {"firmware": 0}}
        retry = {"t": day + 9 * 3600, "r": {"firmware": 1}}
        history = {"samples": [first, retry]}

        firmware_index.trim(history, day + 10 * 3600)

        self.assertEqual(history["samples"], [retry])

    def test_retains_the_latest_sample_for_each_of_seven_days(self):
        base_day = 20000
        samples = []
        for offset in range(8):
            day = (base_day + offset) * 86400
            samples.append({"t": day + 8 * 3600, "r": {"day": offset, "attempt": 1}})
            samples.append({"t": day + 9 * 3600, "r": {"day": offset, "attempt": 2}})
        history = {"samples": list(reversed(samples))}
        now = (base_day + 7) * 86400 + 10 * 3600

        firmware_index.trim(history, now)

        self.assertEqual(len(history["samples"]), 7)
        self.assertEqual(
            [(sample["r"]["day"], sample["r"]["attempt"]) for sample in history["samples"]],
            [(offset, 2) for offset in range(1, 8)],
        )


DOCS = pathlib.Path(__file__).resolve().parents[2] / "docs" / "hardware"

# Nothing past this has been flashed on real hardware by this project.
FLASH_VERIFIED_CEILING = (4, 4, 0)


class PublishedDataContractTest(unittest.TestCase):
    """Contracts the published surfaces must keep with the indexed data.

    Only claims checkable against the data or a recorded hardware fact live here.
    Editorial wording is not tested; a wording regression is fixed by editing the
    sentence, the same as in any docs repo.
    """

    def test_every_document_states_the_flash_verification_boundary(self):
        """4.4.0 is the hardware-verified boundary; no surface may widen or drop it.

        Stating the boundary is necessary but not sufficient — a document could say it and
        then claim verification of something later in the next sentence, so every
        verification claim is checked against the ceiling too, in both directions and
        without pinning the connecting verb ("4.7.0 has since been flash-verified" is as
        wrong as "flash-verified through 4.7.0").
        """
        claim = re.compile(
            r"(?:hardware|flash|live-flash)[- ]verified[^.\n]{0,80}?(\d+\.\d+\.\d+)"
            r"|(\d+\.\d+\.\d+)[^.\n]{0,60}?(?:hardware|flash|live-flash)[- ]verified",
            flags=re.I,
        )
        for name, text in self._documents().items():
            with self.subTest(document=name):
                self.assertRegex(text, r"(?:hardware-verified|flash-verified)[^.\n]{0,60}4\.4\.0")
                for match in claim.finditer(text):
                    raw = match.group(1) or match.group(2)
                    version = tuple(int(part) for part in raw.split("."))
                    self.assertLessEqual(
                        version,
                        FLASH_VERIFIED_CEILING,
                        f"{name} claims verification of {raw}, past the "
                        f"{'.'.join(map(str, FLASH_VERIFIED_CEILING))} ceiling: "
                        f"{match.group(0)!r}",
                    )

    def test_upgrade_guidance_matches_the_indexed_data(self):
        """The index records releases with several inbound diffs, and APK-only releases
        with no ROM at all, so guidance must not describe a single-diff-only model."""
        widest = self._widest_inbound_diff_count()
        self.assertGreater(widest, 1, "index has no multi-source release to guard against")
        for name, text in self._documents().items():
            with self.subTest(document=name):
                self.assertNotIn("a diff against one specific earlier version", text)
                self.assertIn("APK-only", text)

    @staticmethod
    def _widest_inbound_diff_count():
        widest = 0
        for name in ("fw-120p.dat", "fw-86p.dat"):
            for line in (pathlib.Path(__file__).parent / name).read_text().splitlines():
                if line.startswith("diff|"):
                    widest = max(widest, len(line.split("|")) - 3)
        return widest

    def test_curated_release_rows_are_in_ascending_version_order(self):
        """A release appended to the curated table must not land out of sequence."""
        versions = self._curated_versions()
        self.assertGreater(len(versions), 5, "curated table not found")
        self.assertEqual(
            versions,
            sorted(versions),
            f"curated release rows out of order: {['.'.join(map(str, v)) for v in versions]}",
        )

    @classmethod
    def _curated_versions(cls):
        body = cls._body()
        section = body.split("## Which version should an HA panel run?")[1].split("\n## ")[0]
        found = []
        for line in section.split("\n"):
            match = re.match(r"\|\s*\[?\*\*(\d+)\.(\d+)\.(\d+)\*\*", line)
            if match:
                found.append(tuple(int(g) for g in match.groups()))
        return found

    @classmethod
    def _documents(cls):
        # The NSPanel Pro guides moved to panel-assistant.io and the pages under docs/hardware are
        # now redirect stubs, so the generated discussion body is the one surface published here.
        return {"generated discussion body": cls._body()}

    @staticmethod
    def _body():
        output = io.StringIO()
        with mock.patch("sys.stdout", output):
            firmware_index.cmd_render(SimpleNamespace(history=None, out=None))
        return output.getvalue()


class DiscoveryTest(unittest.TestCase):
    """Contracts for `discover`, checked against the real index data.

    Discovery guesses forward into a bucket that cannot be listed, so its
    failure mode is a silent false negative. Each contract below pins a way
    that has already been observed to happen or would hide a real release.
    """

    def setUp(self):
        self.devices = firmware_index.load_devices()

    def test_every_rom_target_can_be_an_upgrade_source(self):
        # Regression: the from-set was built only from versions already used
        # as a diff SOURCE, so the newest release — which has only ever been
        # a TARGET — was excluded, and the likeliest upgrade path of all
        # (latest → next) could never be discovered.
        for d in self.devices:
            targets = {to for to, _f, _i, _s in d["diffs"]}
            states = firmware_index.rom_states(d)
            missing = sorted(targets - states, key=firmware_index.vkey)
            self.assertEqual(
                missing, [], f"{d['channel']}: ROM targets absent from the upgrade-source set"
            )

    def test_candidate_versions_span_the_largest_historical_minor_jump(self):
        # Sonoff shipped 4.0.12 and then 4.4.0 with nothing between, so the
        # candidate window must reach at least that far or a real release
        # would sit undiscovered forever.
        jumps = []
        for d in self.devices:
            versions = sorted({v for v, _i, _s in d["apks"]}, key=firmware_index.vkey)
            for earlier, later in zip(versions, versions[1:]):
                a, b = firmware_index.vkey(earlier), firmware_index.vkey(later)
                if b[0] == a[0]:
                    jumps.append(b[1] - a[1])
        widest = max(jumps)
        self.assertGreater(widest, 0, "index data has no minor-version jump to calibrate against")

        newest = firmware_index.newest_version(self.devices[0]["apks"])
        major, minor, _patch = firmware_index.vkey(newest)
        candidates = firmware_index.candidate_versions(newest)
        self.assertIn(
            f"{major}.{minor + widest}.0",
            candidates,
            f"candidate versions do not reach a {widest}-minor jump, which has happened before",
        )

    def test_candidate_versions_exclude_the_known_release(self):
        newest = firmware_index.newest_version(self.devices[0]["apks"])
        self.assertNotIn(newest, firmware_index.candidate_versions(newest))

    def test_default_windows_cover_observed_index_and_patch_gaps(self):
        index_gaps = []
        for d in self.devices:
            for entries, position in ((d["apks"], 1), (d["diffs"], 2)):
                indices = sorted({int(entry[position]) for entry in entries})
                index_gaps.extend(later - earlier for earlier, later in zip(indices, indices[1:]))
        self.assertGreaterEqual(firmware_index.DISCOVER_INDEX_WINDOW, max(index_gaps))
        self.assertIn("3.8.7", firmware_index.candidate_versions("3.8.0"))
        self.assertIn("3.9.3", firmware_index.candidate_versions("3.8.0"))

    def test_discover_one_requires_zip_magic(self):
        # A 206 alone is not proof: the CDN could serve an error body that
        # still satisfies a range request.
        response = mock.MagicMock()
        response.status = 206
        response.headers = {"Content-Range": "bytes 0-3/137890388"}
        response.read.return_value = b"<htm"
        response.__enter__.return_value = response

        with mock.patch("urllib.request.urlopen", return_value=response):
            self.assertIsNone(firmware_index.discover_one("https://example.invalid/x.apk"))

    def test_discover_one_accepts_a_real_zip(self):
        response = mock.MagicMock()
        response.status = 206
        response.headers = {"Content-Range": "bytes 0-3/137890388"}
        response.read.return_value = b"PK\x03\x04"
        response.__enter__.return_value = response

        with mock.patch("urllib.request.urlopen", return_value=response):
            self.assertEqual(
                firmware_index.discover_one("https://example.invalid/x.apk"), 137890388
            )

    def test_only_the_cdns_explicit_missing_response_means_absent(self):
        missing = urllib.error.HTTPError("https://example.invalid/x", 403, "missing", {}, None)
        throttled = urllib.error.HTTPError("https://example.invalid/x", 429, "slow down", {}, None)
        with mock.patch("urllib.request.urlopen", side_effect=missing):
            self.assertIsNone(firmware_index.discover_one("https://example.invalid/x"))
        with mock.patch("urllib.request.urlopen", side_effect=throttled):
            with self.assertRaises(urllib.error.HTTPError):
                firmware_index.discover_one("https://example.invalid/x")
        with mock.patch("urllib.request.urlopen", side_effect=TimeoutError("timed out")):
            with self.assertRaises(TimeoutError):
                firmware_index.discover_one("https://example.invalid/x")

    def test_rom_diffs_are_probed_without_an_apk_hit(self):
        d = self.devices[0]
        with mock.patch.object(firmware_index, "discover_one", return_value=None):
            findings, searched = firmware_index.discover_device(d, index_window=1, minor_window=1)
        self.assertEqual(findings, [])
        next_diff = max(int(index) for _to, _frm, index, _size in d["diffs"]) + 1
        self.assertEqual(searched["diff_indices"], [next_diff, next_diff])

    def test_a_blind_prober_aborts_instead_of_reporting_nothing_new(self):
        # The whole point of the daily job is that "nothing found" is
        # trustworthy. If the prober cannot see objects that are known to
        # exist, it must fail loudly rather than emit a clean negative.
        output = io.StringIO()
        with mock.patch.object(firmware_index, "discover_one", return_value=None), \
                mock.patch("sys.stdout", output):
            code = firmware_index.cmd_discover(
                SimpleNamespace(index_window=2, minor_window=1, json=None, apply=False)
            )

        self.assertEqual(code, 2)
        self.assertIn("harness FAILED", output.getvalue())
        self.assertNotIn("no unindexed firmware found", output.getvalue())

    def test_searched_window_is_reported_even_when_nothing_is_found(self):
        # A fixed window can miss a release when the CDN skips indices, so a
        # negative result must say what it actually covered.
        real = firmware_index.discover_one
        devices = self.devices

        def only_known_objects(url):
            known = firmware_index.all_url_sizes(devices)
            return known.get(url)

        output = io.StringIO()
        with mock.patch.object(firmware_index, "discover_one", side_effect=only_known_objects), \
                mock.patch("sys.stdout", output):
            code = firmware_index.cmd_discover(
                SimpleNamespace(index_window=2, minor_window=1, json=None, apply=False)
            )

        self.assertIsNot(real, None)
        self.assertEqual(code, 0)
        text = output.getvalue()
        self.assertIn("no unindexed firmware found", text)
        for d in devices:
            self.assertIn(f"searched {d['channel']}: apk indices", text)

    def test_workflow_preserves_main_data_and_does_not_suppress_validation(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/sonoff-firmware-discovery.yml").read_text()
        self.assertNotIn("git show FETCH_HEAD:tools/firmware-index", workflow)
        self.assertNotIn("[skip ci]", workflow)
        # The guarded property is that the validation step still renders, not the
        # exact argv — pinning the flag order made adding --wayback look like a
        # regression when it was the fix for one.
        self.assertRegex(workflow, r"firmware_index\.py render\b[^\n]*--out")
        self.assertIn("git diff --cached --quiet", workflow)
        self.assertIn("--force-with-lease", workflow)


class RenderedIndexTest(unittest.TestCase):
    """Contracts for the two generated surfaces, checked against the index data.

    The Discussion body is capped by GitHub and the archive page is not, so the
    split between them is a correctness property: the body may drop rows, the
    archive page may not.
    """

    def setUp(self):
        self.devices = firmware_index.load_devices()

    def _body(self, wb=None, window=firmware_index.RENDER_TARGET_WINDOW):
        return firmware_index.render_body(self.devices, wb or {}, window)

    def test_body_fits_inside_the_github_limit(self):
        size = len(self._body().encode())
        self.assertLess(
            size,
            firmware_index.GITHUB_BODY_LIMIT,
            f"rendered body is {size} bytes, at or over GitHub's limit",
        )

    def test_body_shows_only_the_windowed_targets(self):
        window = firmware_index.RENDER_TARGET_WINDOW
        targets = sorted({t for d in self.devices for t, _f, _i, _s in d["diffs"]},
                         key=firmware_index.vkey)
        self.assertGreater(len(targets), window, "index has too few targets to exercise the window")
        body = self._body()
        for dropped in targets[:-window]:
            for d in self.devices:
                for to, frm, idx, _sz in d["diffs"]:
                    if to == dropped:
                        self.assertNotIn(firmware_index.diff_url(d, idx, frm, to), body)
        for kept in targets[-window:]:
            self.assertIn(f"| **{kept}** |", body)

    def test_archive_page_lists_every_indexed_object(self):
        # The body is allowed to omit rows; this page is the reason that is safe.
        page = (DOCS / "nspanel-pro-firmware-archive.md").read_text(encoding="utf-8")
        for url in firmware_index.all_url_sizes(self.devices):
            self.assertIn(url, page, f"archive page is missing {url}")

    def test_committed_archive_page_matches_the_index(self):
        """The generated page must not drift from the data it claims to render.

        Compared by the set of download URLs rather than byte-for-byte, because
        the committed page carries Wayback dates that a regeneration without the
        archival state cannot reproduce. URLs are the part that must not drift;
        an added, removed or renamed object fails this.
        """
        import tempfile

        with tempfile.NamedTemporaryFile("r+", suffix=".md") as tmp:
            with mock.patch("sys.stderr", io.StringIO()):
                firmware_index.cmd_archive(SimpleNamespace(wayback=None, out=tmp.name))
            tmp.seek(0)
            regenerated = tmp.read()
        committed = (DOCS / "nspanel-pro-firmware-archive.md").read_text(encoding="utf-8")

        cdn = re.compile(r"\((https://global-otadl2bsy\.coolkit\.cc/[^)]+)\)")
        self.assertEqual(
            set(cdn.findall(regenerated)),
            set(cdn.findall(committed)),
            "committed archive page has drifted from the index — regenerate it with "
            "`firmware_index.py archive`",
        )

    def test_a_missing_capture_renders_as_an_explicit_gap(self):
        # A blank cell would read as "no archive needed"; the marker makes an
        # unarchived file visible so it can be fixed.
        self.assertEqual(firmware_index.archived_cell("https://example.invalid/x", {}), "—")
        self.assertEqual(
            firmware_index.archived_cell("u", {"u": "20260803124531"}), "2026-08-03"
        )

    def test_archive_refuses_to_erase_existing_capture_dates(self):
        """A caller that forgets the archival state must fail, not silently regress.

        This is the defect the URL-only drift test cannot see: the regenerated
        page keeps every URL and loses every date.
        """
        import tempfile

        urls = list(firmware_index.all_url_sizes(self.devices))
        fixture = {u: "20260803124531" for u in urls}

        with tempfile.TemporaryDirectory() as tmp:
            page = pathlib.Path(tmp) / "archive.md"
            with mock.patch.object(firmware_index, "load_wayback", return_value=fixture), \
                    mock.patch("sys.stderr", io.StringIO()):
                covered_rc = firmware_index.cmd_archive(
                    SimpleNamespace(wayback="fixture", out=str(page), allow_coverage_loss=False)
                )
            self.assertEqual(covered_rc, 0)
            before = firmware_index.archived_rows(page.read_text(encoding="utf-8"))
            self.assertGreater(before, 0, "fixture run produced no archived rows")

            err = io.StringIO()
            with mock.patch.object(firmware_index, "load_wayback", return_value={}), \
                    mock.patch("sys.stderr", err):
                rc = firmware_index.cmd_archive(
                    SimpleNamespace(wayback=None, out=str(page), allow_coverage_loss=False)
                )

            self.assertEqual(rc, 1, "regenerating without archival state must fail")
            self.assertIn("refusing to write", err.getvalue())
            self.assertEqual(
                firmware_index.archived_rows(page.read_text(encoding="utf-8")),
                before,
                "the existing page must be left untouched when the guard fires",
            )

    def test_every_render_path_receives_the_archival_state(self):
        """Each workflow step that renders or archives must pass --wayback.

        One such call site was missing it, so the
        contract is checked across all of them rather than fixed in one place.
        """
        workflows = pathlib.Path(__file__).resolve().parents[2] / ".github" / "workflows"
        pattern = re.compile(r"firmware_index\.py (render|archive)([^\n]*)")
        found = 0
        for wf in sorted(workflows.glob("*.yml")):
            for verb, rest in pattern.findall(wf.read_text(encoding="utf-8")):
                found += 1
                self.assertIn(
                    "--wayback", rest,
                    f"{wf.name}: `{verb}` is invoked without --wayback, which would "
                    f"publish empty archival coverage",
                )
        self.assertGreaterEqual(found, 3, "expected render/archive call sites were not found")

    def test_banner_reports_coverage_over_everything_indexed(self):
        # Coverage must be measured against the full index, not the subset the
        # body happens to show, or the number would flatter itself.
        urls = list(firmware_index.all_url_sizes(self.devices))
        wb = {urls[0]: "20260803124531"}
        banner = firmware_index.archive_banner(wb, self.devices)
        self.assertIn(f"**1/{len(urls)}**", banner)


if __name__ == "__main__":
    unittest.main()
