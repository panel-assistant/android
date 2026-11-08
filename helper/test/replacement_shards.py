#!/usr/bin/env python3
"""Prove the Makefile's replacement driver shards run every (leg, case, variant) exactly once.

Usage: replacement_shards.py <driver> <leg>:<case>+<case>...:<variant>+<variant>... ...

Asks the driver itself (`--plan`) what each shard's environment selects, so the proof uses the
same selection code the shard runs, and compares the union with the unsharded plan.
"""
import collections
import os
import subprocess
import sys


def plan(driver, environment):
    output = subprocess.run([sys.executable, driver, '--plan'], env={**os.environ, **environment},
                            check=True, capture_output=True, text=True).stdout
    return [tuple(line.split()) for line in output.splitlines()]


def main(driver, shards):
    unselected = {key: value for key, value in os.environ.items() if not key.startswith('RECOVERY_REPLACEMENT_')}
    os.environ.clear(); os.environ.update(unselected)
    expected = plan(driver, {})
    seen = collections.Counter()
    for shard in shards:
        fields = shard.split(':')
        if len(fields) != 3 or not fields[0] or not fields[1]:
            sys.exit(f'FAIL: malformed shard {shard!r}')
        environment = {'RECOVERY_REPLACEMENT_LEGS': fields[0],
                       'RECOVERY_REPLACEMENT_CASES': fields[1].replace('+', ',')}
        if fields[2]: environment['RECOVERY_REPLACEMENT_VARIANTS'] = fields[2].replace('+', ',')
        selected = plan(driver, environment)
        if not selected: sys.exit(f'FAIL: shard {shard!r} selects no run')
        seen.update(selected)
    missing = [run for run in expected if run not in seen]
    repeated = [run for run, count in seen.items() if count > 1]
    extra = [run for run in seen if run not in expected]
    for label, runs in (('missing from every shard', missing), ('run by more than one shard', repeated), ('not in the driver plan', extra)):
        for run in runs: print(f'FAIL: {" ".join(run)} {label}')
    if missing or repeated or extra: sys.exit(1)
    print(f'replacement shards: {len(shards)} shards cover all {len(expected)} driver runs once')


if __name__ == '__main__':
    if len(sys.argv) < 3: sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
