"""Fail when a workflow's recent median run time exceeds its budget.

CI slowdown is gradual, so nobody notices it in the run they are looking at.
This turns it into something that fails on a schedule instead: the median of
recent successful push runs on main, per workflow, against a recorded ceiling.

With --this-run it instead warns, never fails, when the current run on main has
already taken longer than its workflow's budget, so a slow push is seen the day
it lands rather than at the next weekly check.

Both modes also hold each set-up step (checkout, toolchain, image pull, cache
restore) to a per-step budget, "setup_step" in the budget file. A whole
workflow can stay inside its budget while one download grows from 2 s to 30 s,
and that is the slowdown nobody looks for. The weekly check fails on a step
whose median over the sampled runs is over; --this-run warns on this run's.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import urllib.parse
import urllib.request
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

_API = "https://api.github.com"
# Bound API work even when a workflow has only malformed history.
_MAX_PAGES = 10
_TIMESTAMP = "%Y-%m-%dT%H:%M:%SZ"


def run_seconds(run: dict[str, Any]) -> float | None:
    """Return one run's wall-clock seconds, or None when it cannot be told."""
    started = run.get("run_started_at")
    finished = run.get("updated_at")
    if not started or not finished:
        return None
    try:
        elapsed = datetime.strptime(finished, _TIMESTAMP) - datetime.strptime(
            started, _TIMESTAMP
        )
    except ValueError:
        return None
    seconds = elapsed.total_seconds()
    return seconds if seconds >= 0 else None


def median_seconds(runs: list[dict[str, Any]], sample_size: int) -> float | None:
    """Return the median duration of the most recent usable runs."""
    if sample_size < 1:
        raise ValueError("sample_size must be at least 1")
    durations = [seconds for run in runs if (seconds := run_seconds(run)) is not None][
        :sample_size
    ]
    if not durations:
        return None
    return statistics.median(durations)


def verdicts(
    measured: dict[str, float | None], budgets: dict[str, int]
) -> list[tuple[str, str, float | None, int]]:
    """Return one (state, workflow, median, budget) row per budgeted workflow.

    A workflow with no usable runs is reported as "no-data" rather than passed:
    an absent measurement is not evidence that the budget is met.
    """
    rows = []
    for workflow in sorted(budgets):
        budget = budgets[workflow]
        seconds = measured.get(workflow)
        if seconds is None:
            rows.append(("no-data", workflow, None, budget))
        elif seconds > budget:
            rows.append(("over", workflow, seconds, budget))
        else:
            rows.append(("ok", workflow, seconds, budget))
    return rows


def this_run_warning(
    workflow: str, created_at: str, now: datetime, budgets: dict[str, int]
) -> str | None:
    """Return the warning for a run that has outlasted its budget, or None.

    The run is timed from its creation to now by the same wall-clock rule the
    weekly median uses. A workflow without a budget, or a creation time that
    cannot be read, gives no warning: this check advises and never blocks.
    """
    budget = budgets.get(workflow)
    if budget is None:
        return None
    seconds = run_seconds(
        {"run_started_at": created_at, "updated_at": now.strftime(_TIMESTAMP)}
    )
    if seconds is None or seconds <= budget:
        return None
    return (
        f"{workflow} took {seconds:.0f}s on this push, over its budget of "
        f"{budget}s in .github/ci-budget.json. Find what got slower before "
        "raising the number."
    )


def step_seconds(step: dict[str, Any]) -> float | None:
    """Return one job step's seconds, or None when it did not run to completion."""
    return run_seconds(
        {
            "run_started_at": step.get("started_at"),
            "updated_at": step.get("completed_at"),
        }
    )


def is_setup_step(job: str, name: str, rule: dict[str, Any]) -> bool:
    """Tell whether a step only prepares the job (budgeted) or does its work.

    An exemption names the step in every job, or "<job> / <step>" in one job,
    since the same step can be cut on one runner and not on another.
    """
    exempt = rule.get("exempt", {})
    return any(name.startswith(prefix) for prefix in rule["prefixes"]) and not (
        name in exempt or f"{job} / {name}" in exempt
    )


def setup_step_times(
    jobs: list[dict[str, Any]], rule: dict[str, Any]
) -> dict[tuple[str, str], float]:
    """Return the seconds of every completed set-up step, keyed by (job, step).

    A job still running is left out: in --this-run that is the budget job
    itself, and an unfinished step has no duration to judge.
    """
    times = {}
    for job in jobs:
        if job.get("status") != "completed":
            continue
        for step in job.get("steps") or []:
            name = step.get("name") or ""
            # A skipped step (a cache hit skips its install) cost nothing.
            skipped = step.get("conclusion") == "skipped"
            seconds = 0.0 if skipped else step_seconds(step)
            job_name = job.get("name") or ""
            if seconds is not None and is_setup_step(job_name, name, rule):
                times[(job_name, name)] = seconds
    return times


def slow_setup_steps(
    samples: list[dict[tuple[str, str], float]], budget: int
) -> list[tuple[str, str, float]]:
    """Return (job, step, median seconds) for each set-up step over budget.

    The median across the sampled runs, so one cold-cache run cannot fail the
    weekly check on its own; a single sample is judged as it stands.
    """
    by_step: dict[tuple[str, str], list[float]] = {}
    for sample in samples:
        for key, seconds in sample.items():
            by_step.setdefault(key, []).append(seconds)
    rows = []
    for (job, step), values in sorted(by_step.items()):
        seconds = statistics.median(values)
        if seconds > budget:
            rows.append((job, step, seconds))
    return rows


def setup_step_warning(job: str, step: str, seconds: float, budget: int) -> str:
    """Name the step and the three ways to cut it before any exemption."""
    return (
        f"Set-up step '{step}' in job '{job}' took {seconds:.0f}s, over the "
        f"per-step budget of {budget}s in .github/ci-budget.json. Eliminate it, "
        "prebuild it or cache it; only a step none of those can cut goes under "
        "setup_step.exempt with the reason."
    )


def _jobs(api: str, run_id: object, token: str) -> list[dict[str, Any]]:
    return _fetch(f"{api}/runs/{run_id}/jobs?filter=latest&per_page=100", token).get(
        "jobs", []
    )


def summary_lines(*, over: bool, no_data: bool) -> list[str]:
    """Return the closing advice for each failure that actually occurred.

    The two failures need different repairs, so one shared sentence sends half
    the readers to the wrong file. A cold cache is named because it is a
    recurring cause rather than a corner case: GitHub drops a cache nothing has
    read for a week, so a quiet week makes the next run slow for no other reason.
    """
    lines = []
    if over:
        lines.append(
            "A workflow is slower than its budget. Either it got slower, or the "
            "sample includes cold-cache runs; find out which before raising a "
            "number in .github/ci-budget.json."
        )
    if no_data:
        lines.append(
            "A budgeted workflow has no successful runs to measure. It was most "
            "likely renamed, so its key in .github/ci-budget.json no longer matches."
        )
    return lines


def _fetch(url: str, token: str) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "panel-assistant-ci-budget",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def check_this_run(args: argparse.Namespace) -> int:
    """Warn when this run is over its workflow's budget; always return 0."""
    now = datetime.now(UTC).replace(tzinfo=None)
    try:
        token = os.environ.get("GITHUB_TOKEN")
        run_id = os.environ.get("GITHUB_RUN_ID")
        workflow = os.environ.get("GITHUB_WORKFLOW")
        if not token or not args.repository or not run_id or not workflow:
            print("CI budget: no token, repository, run or workflow; not checked")
            return 0
        config = json.loads(args.budget_file.read_text(encoding="utf-8"))
        run = _fetch(f"{_API}/repos/{args.repository}/actions/runs/{run_id}", token)
        warnings = []
        warning = this_run_warning(
            workflow, run.get("created_at") or "", now, config["budgets"]
        )
        if warning is not None:
            warnings.append(warning)
        rule = config.get("setup_step")
        if rule:
            api = f"{_API}/repos/{args.repository}/actions"
            times = setup_step_times(_jobs(api, run_id, token), rule)
            warnings += [
                setup_step_warning(job, step, seconds, rule["budget_seconds"])
                for job, step, seconds in slow_setup_steps(
                    [times], rule["budget_seconds"]
                )
            ]
        if not warnings:
            print(f"CI budget: {workflow} is within its budget")
            return 0
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        for warning in warnings:
            print(f"::warning title=CI budget::{warning}")
            if summary:
                with open(summary, "a", encoding="utf-8") as handle:
                    handle.write(f"{warning}\n")
    except Exception as error:  # this check must never fail a run
        print(f"CI budget: not checked ({type(error).__name__}: {error})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--branch", default="main")
    parser.add_argument(
        "--event",
        default="push",
        help="count only runs started by this event, so trials never skew the median",
    )
    parser.add_argument(
        "--budget-file", type=Path, default=Path(".github/ci-budget.json")
    )
    parser.add_argument(
        "--this-run",
        action="store_true",
        help="warn, never fail, when the current run is over its workflow's budget",
    )
    args = parser.parse_args(argv)
    if args.this_run:
        return check_this_run(args)

    token = os.environ.get("GITHUB_TOKEN")
    if not token or not args.repository:
        print("GITHUB_TOKEN and a repository are required", file=sys.stderr)
        return 2

    config = json.loads(args.budget_file.read_text(encoding="utf-8"))
    budgets: dict[str, int] = config["budgets"]
    sample_size: int = config["sample_size"]

    if sample_size < 1:
        raise ValueError("sample_size must be at least 1")
    api = f"{_API}/repos/{args.repository}/actions"
    workflow_ids: dict[str, list[int]] = {}
    for page in range(1, _MAX_PAGES + 1):
        workflows = _fetch(f"{api}/workflows?per_page=100&page={page}", token).get(
            "workflows", []
        )
        for workflow in workflows:
            if workflow.get("name") in budgets:
                workflow_ids.setdefault(workflow["name"], []).append(workflow["id"])
        if len(workflows) < 100:
            break

    by_workflow: dict[str, list[dict[str, Any]]] = {}
    for workflow in budgets:
        ids = workflow_ids.get(workflow, [])
        runs: list[dict[str, Any]] = []
        by_workflow[workflow] = runs
        # A missing or ambiguous name cannot identify the budgeted history.
        if len(ids) != 1:
            continue
        for page in range(1, _MAX_PAGES + 1):
            query = urllib.parse.urlencode(
                {
                    "branch": args.branch,
                    "event": args.event,
                    "status": "success",
                    "per_page": 100,
                    "page": page,
                }
            )
            history = _fetch(f"{api}/workflows/{ids[0]}/runs?{query}", token).get(
                "workflow_runs", []
            )
            for run in history:
                if run_seconds(run) is not None:
                    runs.append(run)
                    if len(runs) == sample_size:
                        break
            if len(runs) == sample_size or len(history) < 100:
                break
    measured = {
        workflow: median_seconds(runs, sample_size)
        for workflow, runs in by_workflow.items()
    }

    over = False
    no_data = False
    rule = config.get("setup_step")
    for workflow, runs in by_workflow.items() if rule else ():
        samples = [setup_step_times(_jobs(api, run["id"], token), rule) for run in runs]
        for job, step, seconds in slow_setup_steps(samples, rule["budget_seconds"]):
            print(
                f"SETUP    {workflow} / {job} / {step}: median {seconds:.0f}s "
                f"> {rule['budget_seconds']}s per step ({len(samples)} runs)"
            )
            over = True
    for state, workflow, seconds, budget in verdicts(measured, budgets):
        sample = f" ({len(by_workflow[workflow])}/{sample_size} usable runs)"
        if state == "no-data":
            print(f"NO DATA  {workflow}: no successful runs on {args.branch}")
            no_data = True
        elif state == "over":
            print(
                f"OVER     {workflow}: median {seconds:.0f}s > budget {budget}s{sample}"
            )
            over = True
        else:
            print(
                f"ok       {workflow}: median {seconds:.0f}s "
                f"<= budget {budget}s{sample}"
            )
    for line in summary_lines(over=over, no_data=no_data):
        print(f"\n{line}", file=sys.stderr)
    return 1 if over or no_data else 0


if __name__ == "__main__":
    raise SystemExit(main())
