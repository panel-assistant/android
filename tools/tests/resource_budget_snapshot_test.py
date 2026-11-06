"""Unit tests for the pure parsers and comparison of resource-budget-snapshot.py.

These lock the parsing and regression-verdict logic — the part that can be
proven without a panel. The thin adb layer is exercised here with an injected
runner over realistic captured-format fixtures; on-panel validation of the exact
device command output is a separate hardware step.
"""

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "resource-budget-snapshot.py"
SPEC = importlib.util.spec_from_file_location("resource_budget_snapshot", SCRIPT)
assert SPEC and SPEC.loader
rb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rb)


MEMINFO_MODERN = """\
Applications Memory Usage (in Kilobytes):
Uptime: 1 Realtime: 1

** MEMINFO in pid 4242 [io.panelassistant.android] **
                   Pss  Private  Private  ...
                 Total    Dirty    Clean  ...
   Native Heap    20480    20000        0
   Dalvik Heap     8192     8000        0
   TOTAL PSS:    152340      TOTAL RSS:   210880     TOTAL SWAP (KB):        0
"""

MEMINFO_LEGACY = """\
** MEMINFO in pid 4242 [io.panelassistant.android] **
   Native Heap    20480 ...
   App Summary
                       Pss(KB)
   TOTAL              149004        TOTAL SWAP PSS:   0
"""

STATUS = "Name:\thapaneld\nState:\tS (sleeping)\nTgid:\t4242\nThreads:\t97\nSigQ:\t0/12000\n"


class ParserTests(unittest.TestCase):
    def test_pid_first_numeric(self):
        self.assertEqual(rb.parse_pid("4242 5555\n"), 4242)
        self.assertIsNone(rb.parse_pid("\n"))

    def test_pss_modern_footer(self):
        self.assertEqual(rb.parse_total_pss_kb(MEMINFO_MODERN), 152340)

    def test_pss_legacy_summary_row(self):
        self.assertEqual(rb.parse_total_pss_kb(MEMINFO_LEGACY), 149004)

    def test_pss_absent_is_null(self):
        self.assertIsNone(rb.parse_total_pss_kb("no total here"))

    def test_threads(self):
        self.assertEqual(rb.parse_threads(STATUS), 97)
        self.assertIsNone(rb.parse_threads("Name:\tx\n"))

    def test_fd_count_and_degradation(self):
        self.assertEqual(rb.parse_fd_count("0\n1\n2\n3\n"), 4)
        self.assertIsNone(rb.parse_fd_count(""))
        self.assertIsNone(rb.parse_fd_count("ls: /proc/4242/fd: Permission denied"))

    def test_crash_markers_count_only_our_package(self):
        log = (
            "--------- beginning of crash\n"
            "F DEBUG : pid: 4242 >>> io.panelassistant.android <<<\n"
            "F DEBUG : some.other.app crashed\n"
        )
        self.assertEqual(rb.parse_crash_markers(log), 1)
        self.assertEqual(rb.parse_crash_markers(""), 0)

    def test_version_code(self):
        self.assertEqual(rb.parse_version_code("  versionCode=341 minSdk=26 targetSdk=34"), "341")


class CompareTests(unittest.TestCase):
    def snap(self, vc, pss, threads, fds, crashes=0):
        return {"version_code": vc, "metrics": {"pss_kb": pss, "threads": threads, "fds": fds, "crash_markers": crashes}}

    def test_stable_build_passes(self):
        v = rb.compare_snapshots(self.snap("340", 150000, 97, 220), self.snap("341", 151000, 98, 224))
        self.assertTrue(v["ok"], v["findings"])

    def test_thread_leak_fails(self):
        v = rb.compare_snapshots(self.snap("340", 150000, 97, 220), self.snap("341", 150000, 105, 220))
        self.assertFalse(v["ok"])
        self.assertTrue(any("live threads" in f for f in v["findings"]))

    def test_pss_regression_needs_both_abs_and_pct(self):
        # +6 MiB is over the 8 MiB abs floor? No (8 MiB=8192 KiB); 6144 < 8192 -> pass even at high pct
        ok = rb.compare_snapshots(self.snap("340", 40000, 97, 220), self.snap("341", 46144, 97, 220))
        self.assertTrue(ok["ok"], ok["findings"])
        # +40 MiB and +100% -> fail
        bad = rb.compare_snapshots(self.snap("340", 40000, 97, 220), self.snap("341", 80960, 97, 220))
        self.assertFalse(bad["ok"])

    def test_new_crash_is_hard_fail(self):
        v = rb.compare_snapshots(self.snap("340", 150000, 97, 220, 0), self.snap("341", 150000, 97, 220, 1))
        self.assertFalse(v["ok"])
        self.assertTrue(any("retained recent crash/ANR marker lines" in f for f in v["findings"]))

    def test_same_build_is_flagged(self):
        v = rb.compare_snapshots(self.snap("341", 150000, 97, 220), self.snap("341", 150000, 97, 220))
        self.assertFalse(v["ok"])
        self.assertTrue(any("same build" in f for f in v["findings"]))

    def test_null_metric_is_skipped_not_passed(self):
        v = rb.compare_snapshots(self.snap("340", None, 97, 220), self.snap("341", None, 97, 220))
        self.assertIn("pss_kb", v["skipped"])


class CaptureLayerTests(unittest.TestCase):
    def test_capture_wires_commands_to_parsers(self):
        replies = {
            "pidof": "4242\n",
            "meminfo": MEMINFO_MODERN,
            "status": STATUS,
            "fd": "0\n1\n2\n",
            "logcat": "",
            "package": "versionCode=341 minSdk=26",
        }

        def fake(target, args):
            joined = " ".join(args)
            if args[0] == "pidof":
                return replies["pidof"]
            if "meminfo" in joined:
                return replies["meminfo"]
            if "status" in joined:
                return replies["status"]
            if "/fd" in joined:
                return replies["fd"]
            if args[0] == "logcat":
                return replies["logcat"]
            if "package" in joined:
                return replies["package"]
            return ""

        snap = rb.capture("panel:5555", runner=fake)
        self.assertEqual(snap["version_code"], "341")
        self.assertEqual(snap["metrics"], {"pss_kb": 152340, "threads": 97, "fds": 3, "crash_markers": 0})

    def test_capture_raises_when_not_running(self):
        with self.assertRaises(rb.SnapshotError):
            rb.capture("panel:5555", runner=lambda t, a: "")

    def test_meminfo_queried_by_pid_and_crash_log_bounded(self):
        calls = []

        def fake(target, args):
            calls.append(args)
            j = " ".join(args)
            if args[0] == "pidof":
                return "4242\n"
            if "meminfo" in j:
                return MEMINFO_MODERN
            if "status" in j:
                return STATUS
            if "/fd" in j:
                return "0\n1\n2\n"
            if args[0] == "logcat":
                return ""
            if "package" in j:
                return "versionCode=350"
            return ""

        rb.capture("panel:5555", runner=fake)
        meminfo = next(a for a in calls if "meminfo" in " ".join(a))
        self.assertEqual(meminfo, ["dumpsys", "meminfo", "4242"])
        self.assertIn(["cat", "/proc/4242/status"], calls)
        self.assertIn(["ls", "-1", "/proc/4242/fd"], calls)
        logcat = next(a for a in calls if a[0] == "logcat")
        self.assertEqual(logcat, ["logcat", "-b", "crash", "-d", "-t", "500"])

    def test_capture_rejects_process_restart(self):
        for final_pid, expected in (("4343\n", "4343"), ("", "not running")):
            with self.subTest(final_pid=final_pid):
                pid_reads = 0

                def fake(target, args):
                    nonlocal pid_reads
                    joined = " ".join(args)
                    if args[0] == "pidof":
                        pid_reads += 1
                        return "4242\n" if pid_reads == 1 else final_pid
                    if "meminfo" in joined:
                        return MEMINFO_MODERN
                    if "status" in joined:
                        return STATUS
                    if "/fd" in joined:
                        return "0\n1\n2\n"
                    if args[0] == "logcat":
                        return ""
                    if "package" in joined:
                        return "versionCode=350"
                    return ""

                with self.assertRaisesRegex(
                    rb.SnapshotError,
                    rf"restarted during snapshot.*4242 -> {expected}",
                ):
                    rb.capture("panel:5555", runner=fake)
                self.assertEqual(pid_reads, 2)

    def test_su_prefix_retries_only_denied_proc_reads(self):
        calls: list[list[str]] = []

        def fake(target, args):
            calls.append(args)
            if args[0] == "pidof":
                return "4242\n"
            if "meminfo" in " ".join(args):
                return MEMINFO_MODERN
            if "package" in " ".join(args):
                return "versionCode=341"
            if args[0] == "logcat":
                return ""
            # direct /proc reads are denied; only the su-wrapped retry returns data
            joined = " ".join(args)
            if args[0] == "su":
                return STATUS if "status" in joined else "0\n1\n2\n3\n4\n"
            return "Permission denied"

        snap = rb.capture("panel:5555", runner=fake, su_prefix="su 0")
        self.assertEqual(snap["metrics"]["threads"], 97)
        self.assertEqual(snap["metrics"]["fds"], 5)
        # the su retry used the operator's prefix verbatim, no dialect guessing
        self.assertTrue(any(a[:2] == ["su", "0"] and "/fd" in " ".join(a) for a in calls))

    def test_no_su_prefix_leaves_denied_reads_null(self):
        def fake(target, args):
            if args[0] == "pidof":
                return "4242\n"
            if "meminfo" in " ".join(args):
                return MEMINFO_MODERN
            if "package" in " ".join(args):
                return "versionCode=341"
            if args[0] == "logcat":
                return ""
            return "Permission denied"

        snap = rb.capture("panel:5555", runner=fake)
        self.assertIsNone(snap["metrics"]["threads"])
        self.assertIsNone(snap["metrics"]["fds"])
        self.assertEqual(snap["metrics"]["pss_kb"], 152340)


if __name__ == "__main__":
    unittest.main()
