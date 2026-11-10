"""The CI budget check (.github/scripts/check_ci_budget.py) must be able to go red.

The script is the same one ha-integration runs; these tests pin the behaviour this
repository relies on: a workflow's wall-clock median and each set-up step's median
against .github/ci-budget.json, warnings on a push, never a failure from --this-run.

Source-text reason for the workflow tests at the end: the YAML files and the budget
JSON are the artifacts GitHub Actions consumes; their triggers and jobs are the contract.
"""

import contextlib
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest import mock
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location(
    "check_ci_budget", ROOT / ".github/scripts/check_ci_budget.py"
)
check_ci_budget = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(check_ci_budget)

RULE = {
    "budget_seconds": 10,
    "prefixes": ["Set up ", "Install ", "Restore "],
    "exempt": {
        "Initialize unavoidable": "reason",
        "hosted / Install Android build toolchain": "no cache on this runner",
    },
}


def _load_yaml(path):
    """PyYAML where installed; otherwise Ruby's, which every GitHub-hosted image carries."""
    try:
        import yaml
    except ImportError:
        script = "puts JSON.dump(YAML.safe_load(File.read(ARGV[0]), aliases: true))"
        loaded = json.loads(subprocess.check_output(["ruby", "-ryaml", "-rjson", "-e", script, str(path)]))
        if "true" in loaded:
            loaded[True] = loaded.pop("true")
        return loaded
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _run(started, finished, **extra):
    return {"run_started_at": started, "updated_at": finished, **extra}


def _step(name, seconds, conclusion="success"):
    return {
        "name": name,
        "conclusion": conclusion,
        "started_at": "2026-11-10T00:00:00Z",
        "completed_at": f"2026-11-10T00:00:{seconds:02d}Z",
    }


def _job(name, *steps, status="completed"):
    return {"name": name, "status": status, "steps": list(steps)}


def _main(argv, fetch, env):
    out = io.StringIO()
    with mock.patch.object(check_ci_budget, "_fetch", fetch), mock.patch.dict(
        os.environ, env, clear=False
    ), contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
        result = check_ci_budget.main(argv)
    return result, out.getvalue()


class WallClockTest(unittest.TestCase):
    def test_median_over_budget_is_over_and_at_budget_passes(self):
        self.assertEqual(
            check_ci_budget.verdicts({"CI": 401.0}, {"CI": 400}),
            [("over", "CI", 401.0, 400)],
        )
        self.assertEqual(
            check_ci_budget.verdicts({"CI": 400.0}, {"CI": 400}),
            [("ok", "CI", 400.0, 400)],
        )

    def test_absence_of_data_is_not_a_pass(self):
        self.assertEqual(
            check_ci_budget.verdicts({"CI": None}, {"CI": 400}),
            [("no-data", "CI", None, 400)],
        )

    def test_unusable_runs_are_not_measured(self):
        for run in (
            {"updated_at": "2026-09-12T16:13:06Z"},
            _run("not a timestamp", "2026-09-12T16:13:06Z"),
            _run("2026-09-12T16:13:06Z", "2026-09-12T16:10:02Z"),
        ):
            self.assertIsNone(check_ci_budget.run_seconds(run))


class SetupStepTest(unittest.TestCase):
    def test_a_slow_setup_step_is_reported_and_work_is_not(self):
        times = check_ci_budget.setup_step_times(
            [_job("unit", _step("Install Android build toolchain", 28), _step("Run debug unit tests", 59))],
            RULE,
        )
        self.assertEqual(
            check_ci_budget.slow_setup_steps([times], 10),
            [("unit", "Install Android build toolchain", 28.0)],
        )

    def test_a_step_at_its_budget_passes(self):
        times = check_ci_budget.setup_step_times([_job("unit", _step("Set up JDK 17", 10))], RULE)
        self.assertEqual(check_ci_budget.slow_setup_steps([times], 10), [])

    def test_a_job_scoped_exemption_holds_only_in_that_job(self):
        """The toolchain install is cached on Blacksmith, not on the hosted runner."""
        times = check_ci_budget.setup_step_times(
            [
                _job("hosted", _step("Install Android build toolchain", 30)),
                _job("blacksmith", _step("Install Android build toolchain", 30)),
            ],
            RULE,
        )
        self.assertEqual(times, {("blacksmith", "Install Android build toolchain"): 30.0})

    def test_a_skipped_install_cost_nothing_and_one_cold_run_does_not_fail(self):
        samples = [
            check_ci_budget.setup_step_times([_job("unit", _step("Restore SDK", s, c))], RULE)
            for s, c in [(40, "success"), (40, "skipped"), (3, "success")]
        ]
        self.assertEqual(check_ci_budget.slow_setup_steps(samples, 10), [])

    def test_an_unfinished_job_is_left_out(self):
        times = check_ci_budget.setup_step_times(
            [_job("budget", _step("Set up job", 30), status="in_progress")], RULE
        )
        self.assertEqual(times, {})


class ModesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.budget = Path(self.dir.name) / "budget.json"
        self.summary = Path(self.dir.name) / "summary.md"

    def test_this_run_warns_on_a_slow_setup_step_and_never_fails(self):
        created = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.budget.write_text(json.dumps({"sample_size": 10, "budgets": {"CI": 400}, "setup_step": RULE}))

        def fetch(url, _token):
            if "/jobs" in url:
                return {"jobs": [_job("unit", _step("Install Android build toolchain", 28))]}
            return {"created_at": created}

        env = {
            "GITHUB_TOKEN": "t",
            "GITHUB_RUN_ID": "99",
            "GITHUB_WORKFLOW": "CI",
            "GITHUB_STEP_SUMMARY": str(self.summary),
        }
        args = ["--this-run", "--repository", "example/repo", "--budget-file", str(self.budget)]
        result, out = _main(args, fetch, env)
        self.assertEqual(result, 0)
        self.assertIn("::warning title=CI budget::Set-up step 'Install Android build toolchain' in job 'unit' took 28s", out)
        self.assertNotIn("CI took", out)
        self.assertIn("Install Android build toolchain", self.summary.read_text())

        def broken(_url, _token):
            raise OSError("HTTP Error 502")

        result, out = _main(args, broken, env)
        self.assertEqual(result, 0)
        self.assertIn("not checked", out)

    def test_this_run_over_its_wall_budget_is_warned(self):
        created = (datetime.now(UTC) - timedelta(seconds=3600)).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.budget.write_text(json.dumps({"sample_size": 10, "budgets": {"CI": 400}}))
        env = {"GITHUB_TOKEN": "t", "GITHUB_RUN_ID": "99", "GITHUB_WORKFLOW": "CI"}
        result, out = _main(
            ["--this-run", "--repository", "example/repo", "--budget-file", str(self.budget)],
            lambda _u, _t: {"created_at": created},
            env,
        )
        self.assertEqual(result, 0)
        self.assertIn("::warning title=CI budget::CI took", out)

    def test_the_weekly_check_counts_push_runs_and_fails_on_a_slow_setup_step(self):
        self.budget.write_text(json.dumps({"sample_size": 3, "budgets": {"CI": 400}, "setup_step": RULE}))
        for seconds, failed in ((25, True), (4, False)):
            queries = []

            def fetch(url, _token, seconds=seconds):
                parsed = urlparse(url)
                if parsed.path.endswith("/actions/workflows"):
                    return {"workflows": [{"name": "CI", "id": 7}]}
                if parsed.path.endswith("/jobs"):
                    return {"jobs": [_job("unit", _step("Set up JDK 17", seconds))]}
                queries.append(parse_qs(parsed.query))
                return {"workflow_runs": [_run("2026-11-10T00:00:00Z", "2026-11-10T00:05:00Z", id=n) for n in range(3)]}

            result, out = _main(
                ["--repository", "example/repo", "--budget-file", str(self.budget)],
                fetch,
                {"GITHUB_TOKEN": "t"},
            )
            self.assertEqual(queries[0]["event"], ["push"])
            self.assertEqual(queries[0]["branch"], ["main"])
            self.assertEqual(result, 1 if failed else 0, out)
            self.assertEqual("SETUP    CI / unit / Set up JDK 17: median 25s" in out, failed)


class WorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((ROOT / ".github/ci-budget.json").read_text(encoding="utf-8"))
        self.workflows = {
            path.name: _load_yaml(path)
            for path in sorted((ROOT / ".github/workflows").glob("*.yml"))
        }

    def test_every_branch_push_workflow_has_a_budget(self):
        """A new workflow that runs on pushes to main must arrive with a budget."""
        on_push = set()
        for workflow in self.workflows.values():
            triggers = workflow.get(True, workflow.get("on", {}))
            push = triggers.get("push") if isinstance(triggers, dict) else None
            if isinstance(triggers, dict) and "push" in triggers and not (push or {}).get("tags"):
                on_push.add(workflow["name"])
        self.assertEqual(on_push, set(self.config["budgets"]))

    def test_every_budgeted_workflow_warns_on_its_own_slow_push(self):
        checked = set()
        for path, workflow in self.workflows.items():
            jobs = workflow["jobs"]
            for name, job in jobs.items():
                steps = " ".join(step.get("run", "") for step in job.get("steps", []))
                if "check_ci_budget.py --this-run" not in steps:
                    continue
                checked.add(workflow["name"])
                self.assertEqual(job["runs-on"], "ubuntu-24.04", path)
                self.assertTrue(job["if"].startswith("${{ always() && "), path)
                self.assertIn("github.event_name == 'push'", job["if"], path)
                self.assertIn("github.ref == 'refs/heads/main'", job["if"], path)
                self.assertEqual(set(job["needs"]), set(jobs) - {name}, path)
                self.assertEqual(job["permissions"]["actions"], "read", path)
                self.assertTrue(all(step.get("continue-on-error") for step in job["steps"]), path)
        self.assertEqual(checked, set(self.config["budgets"]))

    def test_the_per_step_budget_is_ten_seconds_and_every_exemption_has_a_reason(self):
        rule = self.config["setup_step"]
        self.assertEqual(rule["budget_seconds"], 10)
        for step, reason in rule["exempt"].items():
            self.assertGreaterEqual(len(reason.split()), 8, step)


if __name__ == "__main__":
    unittest.main()
