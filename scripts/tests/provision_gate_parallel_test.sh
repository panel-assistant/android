#!/usr/bin/env bash
# Focused contract tests for provision_gate_parallel.sh; no real provisioning cases run here.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="$SCRIPT_DIR/provision_gate_parallel.sh"
CI_WORKFLOW="${PROVISION_CI_WORKFLOW_UNDER_TEST:-$SCRIPT_DIR/../../.github/workflows/ci.yml}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passes=0
failures=0
pass() { passes=$((passes + 1)); printf 'ok %d - %s\n' "$passes" "$1"; }
fail() { failures=$((failures + 1)); printf 'not ok - %s\n' "$1" >&2; }
assert_true() { if "$@"; then pass "$description"; else fail "$description"; fi; }

FAKE_RUNNER="$TMP/fake-provision-test.sh"
cat > "$FAKE_RUNNER" <<'FAKE'
#!/usr/bin/env bash
set -u
name="${PROVISION_TEST_SCOPE#shard-}"
case "$name" in
  database-host) cases=1 ;; database-runtime) cases=1 ;; install-export) cases=1 ;;
  install-runtime) cases=1 ;; helper-transaction) cases=1 ;; release-integrity) cases=1 ;;
  renderer-seeding) cases=1 ;; install-finish) cases=1 ;;
  backup) cases=1 ;; publication) cases=1 ;; database-authority) cases=1 ;;
  fleet-installer) cases=1 ;; host-reclamation) cases=1 ;; git-bash) cases=1 ;;
  helper-release-install) cases=1 ;; database-capture) cases=1 ;;
  *) exit 2 ;;
esac
printf 'tmpdir=%s\n' "$TMPDIR"
if [ -n "${FAKE_STATE_DIR:-}" ]; then
  while ! mkdir "$FAKE_STATE_DIR/lock" 2>/dev/null; do /bin/sleep 0.01; done
  active="$(cat "$FAKE_STATE_DIR/active" 2>/dev/null || printf 0)"
  active=$((active + 1)); printf '%s\n' "$active" > "$FAKE_STATE_DIR/active"
  maximum="$(cat "$FAKE_STATE_DIR/maximum" 2>/dev/null || printf 0)"
  [ "$active" -le "$maximum" ] || printf '%s\n' "$active" > "$FAKE_STATE_DIR/maximum"
  rmdir "$FAKE_STATE_DIR/lock"
  /bin/sleep "${FAKE_SLEEP_SECONDS:-0}"
  while ! mkdir "$FAKE_STATE_DIR/lock" 2>/dev/null; do /bin/sleep 0.01; done
  active="$(cat "$FAKE_STATE_DIR/active")"; printf '%s\n' "$((active - 1))" > "$FAKE_STATE_DIR/active"
  rmdir "$FAKE_STATE_DIR/lock"
fi
if [ "${FAKE_BLOCK_SCOPE:-}" = "$name" ]; then
  /bin/sleep 300 &
  blocked_child=$!
  printf '%s\n' "$blocked_child" > "${FAKE_BLOCK_PID_FILE:?}"
  wait "$blocked_child"
  exit $?
fi
if [ "${FAKE_MALFORMED_SCOPE:-}" = "$name" ]; then printf 'ok 1 - missing plan\n'; exit 0; fi
if [ "${FAKE_ZERO_SCOPE:-}" = "$name" ]; then printf '1..0\n'; exit 0; fi
if [ "${FAKE_DUPLICATE_NUMBER_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - first\nok 1 - second\n1..2\n'; exit 0
fi
if [ "${FAKE_DUPLICATE_IDENTITY_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - repeated\nok 2 - repeated\n1..2\n'; exit 0
fi
if [ "${FAKE_MULTIPLE_PLAN_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - duplicate plan\n1..1\n1..1\n'; exit 0
fi
if [ "${FAKE_MALFORMED_RESULT_SCOPE:-}" = "$name" ]; then
  printf 'not two numeric fields\n' > "$TMPDIR/worker-result-override"
fi
if [ "${FAKE_MISSING_RESULT_SCOPE:-}" = "$name" ]; then
  : > "$TMPDIR/skip-worker-result"
fi
if [ "${FAKE_NONZERO_SCOPE:-}" = "$name" ]; then
  printf 'ok 1 - interrupted worker\n1..1\n'; exit 143
fi
if [ "${FAKE_FAIL_SCOPE:-}" = "$name" ]; then
  printf 'not ok - injected failure\n1..1\n'; exit 1
fi
printf 'ok 1 - %s\n1..1\n' "$name"
FAKE
chmod 755 "$FAKE_RUNNER"

STATE="$TMP/state"; mkdir "$STATE"
OUT="$TMP/pass-results"
PASS_LOG="$TMP/pass.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" PROVISION_GATE_EXPECTED_TOTAL=16 \
FAKE_STATE_DIR="$STATE" FAKE_SLEEP_SECONDS=0.05 \
  bash "$WRAPPER" --jobs 2 --output "$OUT" > "$PASS_LOG" 2>&1
status=$?
description="the complete fake gate passes"; assert_true test "$status" -eq 0
description="the aggregate pins all 16 shard cases"; assert_true grep -q '^AGGREGATE PASS shards=16 cases=16 failures=0 ' "$PASS_LOG"
description="a successful full gate emits exactly one compatible totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$PASS_LOG")" -eq 1
description="the full-gate totals marker is coherent"; assert_true grep -qx 'PROVISION_GATE_TOTALS=shards=16/16;tests=16/16;failures=0' "$PASS_LOG"
order="$(awk '/^SHARD / {printf "%s ", $2}' "$PASS_LOG")"
description="per-shard reports retain deterministic manifest order"; assert_true test "$order" = "database-host database-runtime install-export install-runtime helper-release-install helper-transaction release-integrity renderer-seeding install-finish backup publication database-authority database-capture fleet-installer host-reclamation git-bash "
unique_tmp="$(grep -h '^tmpdir=' "$OUT"/*/tap.log | sort -u | wc -l | tr -d ' ')"
description="every shard receives isolated temporary state"; assert_true test "$unique_tmp" -eq 16
description="the jobs limit permits the requested concurrency"; assert_true test "$(cat "$STATE/maximum")" -eq 2

AGGREGATE_ONLY_LOG="$TMP/aggregate-only.log"
PROVISION_GATE_SHARD_RUNNER="$TMP/not-a-runner" PROVISION_GATE_EXPECTED_TOTAL=16 \
  bash "$WRAPPER" --aggregate "$OUT" > "$AGGREGATE_ONLY_LOG" 2>&1
status=$?
description="retained results can be aggregated without a shard runner"; assert_true test "$status" -eq 0
description="retained full-gate results preserve the canonical totals marker"; assert_true grep -qx 'PROVISION_GATE_TOTALS=shards=16/16;tests=16/16;failures=0' "$AGGREGATE_ONLY_LOG"

# The shard time budget only warns: it names a shard that has grown well past the median, as a GitHub
# annotation and a step-summary table under Actions, and never changes the verdict or exit status.
BUDGET="$TMP/budget-results"
cp -a "$OUT" "$BUDGET"
for result in "$BUDGET"/*/result; do printf '0 20\n' > "$result"; done
printf '0 130\n' > "$BUDGET/install-runtime/result"
BUDGET_LOG="$TMP/budget.log"
BUDGET_SUMMARY="$TMP/budget-summary.md"
GITHUB_ACTIONS=true GITHUB_STEP_SUMMARY="$BUDGET_SUMMARY" \
PROVISION_GATE_SHARD_RUNNER="$TMP/not-a-runner" PROVISION_GATE_EXPECTED_TOTAL=16 \
  bash "$WRAPPER" --aggregate "$BUDGET" > "$BUDGET_LOG" 2>&1
status=$?
description="an over-budget shard does not fail the gate"; assert_true test "$status" -eq 0
description="the over-budget shard is named against the median"; assert_true grep -qx 'BUDGET WARN shard=install-runtime wall=130s median=20s ratio=2' "$BUDGET_LOG"
description="only the over-budget shard is warned"; assert_true test "$(grep -c '^BUDGET WARN ' "$BUDGET_LOG")" -eq 1
description="the budget warning is a GitHub annotation under Actions"; assert_true grep -q '^::warning title=Provisioning shard over budget::install-runtime took 130s' "$BUDGET_LOG"
description="the step summary lists shards slowest first"; assert_true test "$(grep -m1 '^| [a-z]' "$BUDGET_SUMMARY" | cut -d'|' -f2 | tr -d ' ')" = install-runtime
printf '0 55\n' > "$BUDGET/install-runtime/result"
PROVISION_GATE_SHARD_RUNNER="$TMP/not-a-runner" PROVISION_GATE_EXPECTED_TOTAL=16 \
  bash "$WRAPPER" --aggregate "$BUDGET" > "$BUDGET_LOG" 2>&1
description="a short shard stays under the budget floor"; assert_true grep -qx 'BUDGET OK median=20s ratio=2' "$BUDGET_LOG"
description="no annotation is written outside Actions"; assert_true test "$(grep -c '^::warning' "$BUDGET_LOG" || true)" -eq 0

MISSING_AGGREGATE="$TMP/missing-aggregate"
cp -a "$OUT" "$MISSING_AGGREGATE"
rm -rf "$MISSING_AGGREGATE/database-host"
MISSING_AGGREGATE_LOG="$TMP/missing-aggregate.log"
PROVISION_GATE_SHARD_RUNNER="$TMP/not-a-runner" PROVISION_GATE_EXPECTED_TOTAL=16 \
  bash "$WRAPPER" --aggregate "$MISSING_AGGREGATE" > "$MISSING_AGGREGATE_LOG" 2>&1
status=$?
description="a retained full gate fails when one shard artifact is missing"; assert_true test "$status" -ne 0
description="the missing retained shard is reported explicitly"; assert_true grep -q '^SHARD database-host FAIL cases=0 ' "$MISSING_AGGREGATE_LOG"

DEFAULT_STATE="$TMP/default-state"; mkdir "$DEFAULT_STATE"
DEFAULT_LOG="$TMP/default.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_STATE_DIR="$DEFAULT_STATE" FAKE_SLEEP_SECONDS=0.5 \
  bash "$WRAPPER" --output "$TMP/default-results" \
    database-host database-runtime install-export install-runtime helper-transaction \
    release-integrity renderer-seeding install-finish database-authority fleet-installer git-bash \
    > "$DEFAULT_LOG" 2>&1
status=$?
description="the default-concurrency fake gate passes"; assert_true test "$status" -eq 0
description="the default launches all eleven independent shards"; assert_true test "$(cat "$DEFAULT_STATE/maximum")" -eq 11

FAIL_LOG="$TMP/fail.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_FAIL_SCOPE=backup \
  bash "$WRAPPER" -j 2 --output "$TMP/fail-results" database-host backup > "$FAIL_LOG" 2>&1
status=$?
description="one red shard fails the aggregate"; assert_true test "$status" -ne 0
description="the red shard reports its TAP failure and status"; assert_true grep -q '^SHARD backup FAIL cases=1 failures=1 status=1 ' "$FAIL_LOG"
description="a red shard produces a fail-closed aggregate"; assert_true grep -q '^AGGREGATE FAIL shards=2 cases=2 failures=1 ' "$FAIL_LOG"
description="a failed gate emits no passing totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$FAIL_LOG" || true)" -eq 0

FOCUSED_LOG="$TMP/focused.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" \
  bash "$WRAPPER" -j 2 --output "$TMP/focused-results" database-host backup > "$FOCUSED_LOG" 2>&1
status=$?
description="a focused fake gate passes"; assert_true test "$status" -eq 0
description="a focused gate emits coherent positive selected totals"; assert_true grep -qx 'PROVISION_GATE_TOTALS=shards=2/2;tests=2/2;failures=0' "$FOCUSED_LOG"

MALFORMED_LOG="$TMP/malformed.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MALFORMED_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/malformed-results" publication > "$MALFORMED_LOG" 2>&1
status=$?
description="missing TAP plan fails closed"; assert_true test "$status" -ne 0
description="malformed TAP is identified as a failed shard"; assert_true grep -q '^SHARD publication FAIL cases=0 ' "$MALFORMED_LOG"

ZERO_LOG="$TMP/zero.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_ZERO_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/zero-results" publication > "$ZERO_LOG" 2>&1
status=$?
description="a focused shard with zero tests fails closed"; assert_true test "$status" -ne 0
description="the zero-test shard is reported as failed"; assert_true grep -q '^SHARD publication FAIL cases=0 ' "$ZERO_LOG"

DUPLICATE_NUMBER_LOG="$TMP/duplicate-number.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_DUPLICATE_NUMBER_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/duplicate-number-results" publication > "$DUPLICATE_NUMBER_LOG" 2>&1
status=$?
description="duplicate TAP numbering fails closed"; assert_true test "$status" -ne 0

DUPLICATE_IDENTITY_LOG="$TMP/duplicate-identity.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_DUPLICATE_IDENTITY_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/duplicate-identity-results" publication > "$DUPLICATE_IDENTITY_LOG" 2>&1
status=$?
description="repeated TAP descriptions remain valid when test numbers are unique"; assert_true test "$status" -eq 0

MULTIPLE_PLAN_LOG="$TMP/multiple-plan.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MULTIPLE_PLAN_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/multiple-plan-results" publication > "$MULTIPLE_PLAN_LOG" 2>&1
status=$?
description="multiple TAP plans fail closed"; assert_true test "$status" -ne 0

MALFORMED_RESULT_LOG="$TMP/malformed-result.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MALFORMED_RESULT_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/malformed-result-results" publication > "$MALFORMED_RESULT_LOG" 2>&1
status=$?
description="malformed worker metadata fails closed"; assert_true test "$status" -ne 0

MISSING_RESULT_LOG="$TMP/missing-result.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_MISSING_RESULT_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/missing-result-results" publication > "$MISSING_RESULT_LOG" 2>&1
status=$?
description="missing worker metadata fails closed"; assert_true test "$status" -ne 0

NONZERO_LOG="$TMP/nonzero.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_NONZERO_SCOPE=publication \
  bash "$WRAPPER" --output "$TMP/nonzero-results" publication > "$NONZERO_LOG" 2>&1
status=$?
description="an interrupted nonzero worker fails closed"; assert_true test "$status" -ne 0
description="the interrupted status is retained in the shard report"; assert_true grep -q '^SHARD publication FAIL cases=1 failures=0 status=143 ' "$NONZERO_LOG"

AGGREGATE_MISMATCH_LOG="$TMP/aggregate-mismatch.log"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" PROVISION_GATE_EXPECTED_TOTAL=17 \
  bash "$WRAPPER" --output "$TMP/aggregate-mismatch-results" > "$AGGREGATE_MISMATCH_LOG" 2>&1
status=$?
description="a complete-set aggregate count mismatch fails closed"; assert_true test "$status" -ne 0
description="the exact expected and actual aggregate are reported"; assert_true grep -q '^CONTRACT FAIL expected_cases=17 actual_cases=16$' "$AGGREGATE_MISMATCH_LOG"
description="an aggregate mismatch emits no passing totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$AGGREGATE_MISMATCH_LOG" || true)" -eq 0

TERM_LOG="$TMP/term.log"
TERM_CHILD_PID_FILE="$TMP/term-child.pid"
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" FAKE_BLOCK_SCOPE=database-host \
FAKE_BLOCK_PID_FILE="$TERM_CHILD_PID_FILE" \
  bash "$WRAPPER" --output "$TMP/term-results" database-host > "$TERM_LOG" 2>&1 &
term_wrapper_pid=$!
term_ready=0
term_attempt=0
while [ "$term_attempt" -lt 200 ]; do
  if [ -s "$TERM_CHILD_PID_FILE" ]; then term_ready=1; break; fi
  /bin/sleep 0.01
  term_attempt=$((term_attempt + 1))
done
description="the blocked fixture exposes a live descendant before TERM"; assert_true test "$term_ready" -eq 1
kill -TERM "$term_wrapper_pid"
if wait "$term_wrapper_pid"; then term_status=0; else term_status=$?; fi
description="TERM exits with conventional status 143"; assert_true test "$term_status" -eq 143
term_child_pid="$(cat "$TERM_CHILD_PID_FILE")"
term_child_gone=0
term_attempt=0
while [ "$term_attempt" -lt 100 ]; do
  if ! kill -0 "$term_child_pid" 2>/dev/null; then term_child_gone=1; break; fi
  /bin/sleep 0.02
  term_attempt=$((term_attempt + 1))
done
description="TERM reaps the blocked shard descendant process group"; assert_true test "$term_child_gone" -eq 1
description="an interrupted gate emits no passing totals marker"; assert_true test "$(grep -c '^PROVISION_GATE_TOTALS=' "$TERM_LOG" || true)" -eq 0

PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" -j 0 database-host > "$TMP/jobs.log" 2>&1
status=$?
description="a zero jobs limit is rejected"; assert_true test "$status" -eq 2
PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" unknown > "$TMP/unknown.log" 2>&1
status=$?
description="an unknown shard is rejected before execution"; assert_true test "$status" -eq 2
for retired_shard in shizuku helper-install device-sweep; do
  PROVISION_GATE_SHARD_RUNNER="$FAKE_RUNNER" bash "$WRAPPER" "$retired_shard" \
    > "$TMP/retired-$retired_shard.log" 2>&1
  status=$?
  description="unsafe dependent shard $retired_shard is no longer selectable"; assert_true test "$status" -eq 2
done

provisioning_job="$(awk '/^  provisioning:$/ { in_job=1 } /^  provisioning-aggregate:$/ { exit } in_job' "$CI_WORKFLOW")"
aggregate_job="$(awk '/^  provisioning-aggregate:$/ { in_job=1 } /^  dependency-integrity:$/ { exit } in_job' "$CI_WORKFLOW")"
# The self-hosted Android job holds the step list; the hosted job reuses it through a YAML anchor.
build_job="$(awk '/^  build-self-hosted:$/ { in_job=1 } /^  build:$/ { exit } in_job' "$CI_WORKFLOW")"
hosted_build_job="$(awk '/^  build:$/ { in_job=1 } /^  android-build:$/ { exit } in_job' "$CI_WORKFLOW")"
android_build_job="$(awk '/^  android-build:$/ { in_job=1 } /^  host-contracts:$/ { exit } in_job' "$CI_WORKFLOW")"
host_job="$(awk '/^  host-contracts:$/ { in_job=1 } /^  provisioning:$/ { exit } in_job' "$CI_WORKFLOW")"
shard_list="$(awk '/^            shards: / { sub(/^            shards: /, ""); print }' <<<"$provisioning_job" | tr ' ' '\n' | sort)"
expected_shards="$(printf '%s\n' database-host database-runtime install-export install-runtime helper-release-install helper-transaction release-integrity renderer-seeding install-finish backup publication database-authority database-capture fleet-installer host-reclamation git-bash | sort)"
if grep -Fq 'bash scripts/tests/provision_gate_parallel.sh --jobs 3 --output "$results" ${{ matrix.shards }}' <<<"$provisioning_job" &&
   grep -Fqx "    runs-on: \${{ github.event_name != 'pull_request' && vars.CI_PROVISIONING_RUNNER != 'hosted' && 'blacksmith-4vcpu-ubuntu-2404' || 'ubuntu-24.04' }}" <<<"$provisioning_job" &&
   [ "$shard_list" = "$expected_shards" ] &&
   grep -Fq 'uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a' <<<"$provisioning_job" &&
   grep -Fq 'name: provisioning-${{ matrix.group }}' <<<"$provisioning_job" &&
   grep -Fqx '    needs: [provisioning, host-contracts]' <<<"$aggregate_job" &&
   ! grep -Fq 'docs-localization' <<<"$aggregate_job" &&
   grep -Fqx '    name: Host contracts' <<<"$aggregate_job" &&
   grep -Fq 'uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c' <<<"$aggregate_job" &&
   grep -Fq 'pattern: provisioning-*' <<<"$aggregate_job" &&
   grep -Fq 'merge-multiple: true' <<<"$aggregate_job" &&
   grep -Fq 'bash scripts/tests/provision_gate_parallel.sh --aggregate "$RUNNER_TEMP/provisioning-results"' <<<"$aggregate_job" &&
   grep -Fq 'HOST_RESULT: ${{ needs.host-contracts.result }}' <<<"$aggregate_job" &&
   grep -Fq 'PROVISIONING_RESULT: ${{ needs.provisioning.result }}' <<<"$aggregate_job" &&
   grep -Fq 'test "$HOST_RESULT" = success' <<<"$aggregate_job" &&
   grep -Fq 'test "$PROVISIONING_RESULT" = success' <<<"$aggregate_job" &&
   grep -Fqx '    name: Host shell contracts ${{ matrix.group }}' <<<"$host_job" &&
   grep -Fqx '        group: [authority, other]' <<<"$host_job" &&
   grep -Fq 'scripts/tests/root_helper_authority_test.sh' <<<"$host_job"; then
  pass "CI retains every runner-level shard and preserves the Host contracts release gate"
else
  fail "CI retains every runner-level shard and preserves the Host contracts release gate"
fi
if awk '
     /- name: Upload debug APK/ { in_step=1; next }
     in_step && /if: matrix\.apks == '\''debug'\''/ { guarded=1 }
     in_step && /uses: actions\/upload-artifact@/ { uploaded=1; exit }
     END { exit !(guarded && uploaded) }
   ' <<<"$build_job" &&
   grep -Fqx '    steps: &android-steps' <<<"$build_job" &&
   grep -Fqx '    steps: *android-steps' <<<"$hosted_build_job"; then
  pass "CI uploads the debug APK only from the assemble split"
else
  fail "CI uploads the debug APK only from the assemble split"
fi
if grep -Fqx '    name: Android build' <<<"$android_build_job" &&
   grep -Fqx '    needs: [android-runner, build-self-hosted, build]' <<<"$android_build_job" &&
   grep -Fq 'SELF_HOSTED_RESULT: ${{ needs.build-self-hosted.result }}' <<<"$android_build_job" &&
   grep -Fq 'HOSTED_RESULT: ${{ needs.build.result }}' <<<"$android_build_job" &&
   grep -Fq 'self-hosted) test "$SELF_HOSTED_RESULT" = success ;;' <<<"$android_build_job" &&
   grep -Fq 'hosted) test "$HOSTED_RESULT" = success ;;' <<<"$android_build_job" &&
   grep -Fq "if: needs.android-runner.outputs.choice == 'hosted'" <<<"$hosted_build_job" &&
   grep -Fq "if: needs.android-runner.outputs.choice == 'self-hosted'" <<<"$build_job"; then
  pass "CI preserves the Android build release gate across all matrix splits"
else
  fail "CI preserves the Android build release gate across all matrix splits"
fi

printf '1..%d\n' "$((passes + failures))"
[ "$failures" -eq 0 ]
