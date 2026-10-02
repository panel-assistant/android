#!/usr/bin/env bash
# Focused behavioral checks for release-workflow shell contracts.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="${RELEASE_WORKFLOW_UNDER_TEST:-$ROOT/.github/workflows/release.yml}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

passes=0
failures=0

pass() {
  passes=$((passes + 1))
  printf 'ok %d - %s\n' "$passes" "$1"
}

fail_test() {
  failures=$((failures + 1))
  printf 'not ok - %s\n' "$1" >&2
}

# The shared release corpus drives the tag-gate checks below. A missing, empty or misshapen corpus
# ends the suite here, before any check, so the gates can never pass over zero vectors.
if ! release_tag_vectors="$(python3 -c 'import json, sys
tags = json.load(open(sys.argv[1]))["tags"]
assert {t["kind"] for t in tags} == {"stable", "rc", "build", "refused"}, "every tag kind, and no other"
assert all(isinstance(t["tag"], str) and t["tag"] and not set(t["tag"]) & set("\t\n") for t in tags)
for t in tags: print(t["kind"] + "\t" + t["tag"])' "$ROOT/scripts/tests/fixtures/release-identity-corpus.json")"; then
  printf 'Bail out! the shared release corpus could not be loaded\n' >&2
  exit 1
fi

extract_named_step() {
  step_name="$1"
  awk -v wanted="$step_name" '
    $0 == "      - name: " wanted { in_step=1; next }
    in_step && $0 == "        run: |" { in_script=1; next }
    in_script && (/^      - name: / || /^  [A-Za-z0-9_-]+:$/) { exit }
    in_script { print substr($0, 11) }
  ' "$WORKFLOW"
}

extract_named_step_yaml() {
  step_name="$1"
  awk -v wanted="$step_name" '
    $0 == "      - name: " wanted { in_step=1 }
    in_step && /^      - name: / && $0 != "      - name: " wanted { exit }
    in_step { print }
  ' "$WORKFLOW"
}

extract_release_notes_step() {
  extract_named_step "Extract release notes from CHANGELOG"
}

run_release_notes_step() {
  event_name="$1"
  release_tag="$2"
  case_dir="$3"
  (
    cd "$case_dir" || exit 1
    GITHUB_EVENT_NAME="$event_name" \
      RELEASE_TAG="$release_tag" \
      APK_NAME=test.apk \
      bash <(extract_release_notes_step)
  ) > "$case_dir/output.log" 2>&1
}

extract_integrated_checks_step() {
  extract_named_step "Require clean integrated checks for the source commit"
}

run_integrated_checks_step() {
  checks_file="$1"
  output_file="$2"
  mock_bin="$TMP/mock-bin"
  mkdir -p "$mock_bin"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'case "$*" in' \
    '  *check-runs*) sed -n "p" "$MOCK_CHECKS_FILE" ;;' \
    '  *code-scanning/alerts*) printf "%s\n" 0 ;;' \
    '  *) printf "unexpected gh invocation: %s\n" "$*" >&2; exit 2 ;;' \
    'esac' > "$mock_bin/gh"
  chmod +x "$mock_bin/gh"
  PATH="$mock_bin:$PATH" \
    MOCK_CHECKS_FILE="$checks_file" \
    SOURCE_COMMIT=1111111111111111111111111111111111111111 \
    GITHUB_REPOSITORY=maxlyth/ha-paneld \
    GITHUB_ENV="$TMP/github-env" \
    bash <(extract_integrated_checks_step | sed -e 's/max_attempts=60/max_attempts=1/' -e 's/sleep_seconds=10/sleep_seconds=0/') > "$output_file" 2>&1
}

if extract_release_notes_step | bash -n; then
  pass "release-notes workflow shell is syntactically valid"
else
  fail_test "release-notes workflow shell is syntactically valid"
fi

stable_missing="$TMP/stable-missing"
mkdir -p "$stable_missing/release-input" "$stable_missing/docs"
printf '## v1.0.0\n\nExisting notes.\n' > "$stable_missing/docs/CHANGELOG.md"
run_release_notes_step push v9.9.9 "$stable_missing"
stable_missing_status=$?
if [ "$stable_missing_status" -ne 0 ] && \
   grep -Fq "::error::No docs/CHANGELOG.md section for stable release v9.9.9." "$stable_missing/output.log" && \
   ! grep -Fq "_No changelog entry for v9.9.9._" "$stable_missing/release-input/release-body.md"; then
  pass "stable tag publication fails closed without an exact changelog entry"
else
  fail_test "stable tag publication fails closed without an exact changelog entry"
fi

dry_run_missing="$TMP/dry-run-missing"
mkdir -p "$dry_run_missing/release-input" "$dry_run_missing/docs"
printf '## v1.0.0\n\nExisting notes.\n' > "$dry_run_missing/docs/CHANGELOG.md"
run_release_notes_step workflow_dispatch v9.9.9 "$dry_run_missing"
dry_run_status=$?
if [ "$dry_run_status" -eq 0 ] && \
   grep -Fq "::warning::Dry run has no docs/CHANGELOG.md section for stable candidate v9.9.9." "$dry_run_missing/output.log" && \
   grep -Fq "_No changelog entry for v9.9.9._" "$dry_run_missing/release-input/release-body.md"; then
  pass "manual stable dry run preserves diagnostic missing-changelog behavior"
else
  fail_test "manual stable dry run preserves diagnostic missing-changelog behavior"
fi

prerelease_missing="$TMP/prerelease-missing"
mkdir -p "$prerelease_missing/release-input" "$prerelease_missing/docs"
printf '## v9.9.9\n\nStable notes.\n' > "$prerelease_missing/docs/CHANGELOG.md"
run_release_notes_step push v9.9.9-rc1 "$prerelease_missing"
prerelease_missing_status=$?
if [ "$prerelease_missing_status" -ne 0 ] && \
   grep -Fq "each RC needs its own ## v9.9.9-rc1 entry" "$prerelease_missing/output.log"; then
  pass "prerelease tag still requires its own exact changelog entry"
else
  fail_test "prerelease tag still requires its own exact changelog entry"
fi

stable_present="$TMP/stable-present"
mkdir -p "$stable_present/release-input" "$stable_present/docs"
printf '## v9.9.9\n\nStable release notes.\n' > "$stable_present/docs/CHANGELOG.md"
run_release_notes_step push v9.9.9 "$stable_present"
stable_present_status=$?
if [ "$stable_present_status" -eq 0 ] && \
   grep -Fq "Stable release notes." "$stable_present/release-input/release-body.md"; then
  pass "stable tag with an exact changelog entry remains publishable"
else
  fail_test "stable tag with an exact changelog entry remains publishable"
fi

if grep -Fq "body_path: release-input/release-body.md" "$WORKFLOW" && \
   ! grep -Eq '^[[:space:]]*generate_release_notes:' "$WORKFLOW"; then
  pass "curated changelog remains the sole release prose source"
else
  fail_test "curated changelog remains the sole release prose source"
fi

required_check_names='["Android build","Android lint","Host contracts","Dependency integrity","Privileged helper","CodeQL · actions","CodeQL · c-cpp","CodeQL · java-kotlin","CodeQL · javascript-typescript","CodeQL · python"]'
latest_success_checks="$TMP/latest-success-checks.json"
jq -cn --argjson names "$required_check_names" '
  {check_runs: [$names[] as $name |
    {id: 100, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:00:00Z", status: "completed", conclusion: "failure"},
    {id: 200, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:01:00Z", status: "completed", conclusion: "success"},
    {id: 300, name: $name, head_sha: "2222222222222222222222222222222222222222", app: {slug: "github-actions"}, started_at: "2026-07-27T00:02:00Z", status: "completed", conclusion: "failure"},
    {id: 400, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "untrusted-check-writer"}, started_at: "2026-07-27T00:03:00Z", status: "completed", conclusion: "failure"}
  ]}' > "$latest_success_checks"
if run_integrated_checks_step "$latest_success_checks" "$TMP/latest-success-output.log"; then
  pass "integrated gate selects the latest exact-source check instead of an older or foreign result"
else
  sed -n '1,20p' "$TMP/latest-success-output.log" >&2
  fail_test "integrated gate selects the latest exact-source check instead of an older or foreign result"
fi

latest_failure_checks="$TMP/latest-failure-checks.json"
jq -cn --argjson names "$required_check_names" '
  {check_runs: [$names[] as $name |
    {id: 100, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:00:00Z", status: "completed", conclusion: "success"},
    {id: 200, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:01:00Z", status: "completed", conclusion: "failure"}
  ]}' > "$latest_failure_checks"
if ! run_integrated_checks_step "$latest_failure_checks" "$TMP/latest-failure-output.log" && \
   grep -Fq 'Latest required Android build check' "$TMP/latest-failure-output.log"; then
  pass "integrated gate rejects a latest exact-source failure even when an older run passed"
else
  fail_test "integrated gate rejects a latest exact-source failure even when an older run passed"
fi

latest_queued_checks="$TMP/latest-queued-checks.json"
jq -cn --argjson names "$required_check_names" '
  {check_runs: [$names[] as $name |
    {id: 100, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: "2026-07-27T00:00:00Z", status: "completed", conclusion: "success"},
    {id: 200, name: $name, head_sha: "1111111111111111111111111111111111111111", app: {slug: "github-actions"}, started_at: null, status: "queued", conclusion: null}
  ]}' > "$latest_queued_checks"
if ! run_integrated_checks_step "$latest_queued_checks" "$TMP/latest-queued-output.log" && \
   grep -Fq 'Timed out waiting for required checks' "$TMP/latest-queued-output.log"; then
  pass "integrated gate waits for a newer queued exact-source check instead of accepting an older success"
else
  fail_test "integrated gate waits for a newer queued exact-source check instead of accepting an older success"
fi

verify_job="$(awk '/^  verify:$/ { in_job=1 } /^  package:$/ { exit } in_job' "$WORKFLOW")"
package_job="$(awk '/^  package:$/ { in_job=1 } /^  sign-and-publish:$/ { exit } in_job' "$WORKFLOW")"
publish_job="$(awk '/^  sign-and-publish:$/ { in_job=1 } in_job' "$WORKFLOW")"
if grep -Fq 'Require clean integrated checks for the source commit' "$WORKFLOW" && \
   grep -Fq 'commits/$SOURCE_COMMIT/check-runs' "$WORKFLOW" && \
   grep -Fq 'code-scanning/alerts?state=open' "$WORKFLOW" && \
   grep -Fq '"Android build"' "$WORKFLOW" && \
   grep -Fq '"Android lint"' "$WORKFLOW" && \
   grep -Fq '"Host contracts"' "$WORKFLOW" && \
   grep -Fq '"Dependency integrity"' "$WORKFLOW" && \
   grep -Fq '"Privileged helper"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · actions"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · c-cpp"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · java-kotlin"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · javascript-typescript"' "$WORKFLOW" && \
   grep -Fq '"CodeQL · python"' "$WORKFLOW" && \
   grep -Fq 'select(.name == $name and .head_sha == $source and .app.slug == "github-actions")' "$WORKFLOW" && \
   grep -Fq '| max_by(.id) // {}' "$WORKFLOW" && \
   grep -Fq 'max_attempts=60' "$WORKFLOW" && \
   grep -Fq 'sleep_seconds=10' "$WORKFLOW" && \
   grep -Fq 'if [ "$conclusion" != success ]; then' "$WORKFLOW" && \
   grep -Fq 'Timed out waiting for required checks' "$WORKFLOW" && \
   grep -Fqx '      checks: read' <<<"$verify_job" && \
   grep -Fqx '      security-events: read' <<<"$verify_job" && \
   ! grep -Fq 'Require clean integrated checks for the source commit' <<<"$package_job"; then
  pass "release consumes the latest successful exact-source CI and CodeQL checks and fails closed"
else
  fail_test "release consumes the latest successful exact-source CI and CodeQL checks and fails closed"
fi

if grep -Fq 'Test release workflow contracts' <<<"$verify_job" && \
   grep -Fq 'Require clean integrated checks for the source commit' <<<"$verify_job" && \
   ! grep -Fq 'Set up JDK 17' <<<"$verify_job" && \
   ! grep -Fq 'Set up Android SDK' <<<"$verify_job" && \
   ! grep -Fq 'Install Android build toolchain' <<<"$verify_job" && \
   ! grep -Fq 'Test privileged helper boundaries and app contract' <<<"$verify_job" && \
   ! grep -Fq 'Test installer and provisioning contracts' <<<"$verify_job" && \
   ! grep -Fq 'Run JVM tests and Android lint' <<<"$verify_job" && \
   grep -Fq 'Build release APK' <<<"$package_job" && \
   grep -Fq 'Upload sealed release inputs' <<<"$package_job" && \
   ! grep -Fq 'Require clean integrated checks for the source commit' <<<"$package_job"; then
  pass "release exact-source gate stays lightweight while packaging performs a clean exact-tag build"
else
  fail_test "release exact-source gate stays lightweight while packaging performs a clean exact-tag build"
fi

if grep -Fqx 'concurrency:' "$WORKFLOW" && \
   grep -Fq 'group: release-${{ inputs.release_tag || github.ref_name }}' "$WORKFLOW" && \
   grep -Fqx '  cancel-in-progress: false' "$WORKFLOW"; then
  pass "same-tag release runs serialize without cancelling publication"
else
  fail_test "same-tag release runs serialize without cancelling publication"
fi

if grep -Fqx '    needs: [verify, seal]' <<<"$publish_job" && \
   grep -Fqx '    needs: [package, package-apk]' <<<"$package_job" && \
   grep -Fqx '      input-manifest-sha256: ${{ steps.manifest.outputs.sha256 }}' <<<"$package_job" && \
   grep -Fq 'EXPECTED_MANIFEST_SHA256: ${{ needs.seal.outputs.input-manifest-sha256 }}' <<<"$publish_job" && \
   grep -Fqx "    if: github.event_name == 'push'" <<<"$publish_job"; then
  pass "publication requires the verify gate and the manifest sealed from both identity builds"
else
  fail_test "publication requires the verify gate and the manifest sealed from both identity builds"
fi

if grep -Fqx '      contents: read' <<<"$verify_job" && \
   grep -Fqx '      contents: read' <<<"$package_job" && \
   ! grep -Fq 'contents: write' <<<"$verify_job" && \
   ! grep -Fq 'contents: write' <<<"$package_job" && \
   ! grep -Fq 'environment: release' <<<"$verify_job" && \
   ! grep -Fq 'environment: release' <<<"$package_job" && \
   grep -Fqx '      contents: write' <<<"$publish_job" && \
   grep -Fqx '    environment: release' <<<"$publish_job"; then
  pass "parallel jobs stay read-only and release credentials remain publish-only"
else
  fail_test "parallel jobs stay read-only and release credentials remain publish-only"
fi

if grep -Fq 'git merge-base --is-ancestor "$source_commit" refs/remotes/origin/main' <<<"$verify_job" && \
   grep -Fq 'git merge-base --is-ancestor "$source_commit" refs/remotes/origin/main' <<<"$package_job" && \
   grep -Fq 'echo "SOURCE_COMMIT=$source_commit" >> "$GITHUB_ENV"' <<<"$verify_job" && \
   grep -Fq 'echo "SOURCE_COMMIT=$source_commit" >> "$GITHUB_ENV"' <<<"$package_job"; then
  pass "both parallel jobs validate and bind the same release source"
else
  fail_test "both parallel jobs validate and bind the same release source"
fi

if python3 - "$WORKFLOW" <<'PY'
from pathlib import Path
import re
import sys

lines = Path(sys.argv[1]).read_text().splitlines()
name = "      - name: Validate release catalogue provenance"
assert lines.count(name) == 1
start = lines.index("  verify:")
end = next(index for index in range(start + 1, len(lines)) if re.fullmatch(r"  [\w-]+:", lines[index]))
verify = lines[start:end]
assert not any(re.match(r"    (?:if|continue-on-error):", line) for line in verify)
starts = [index for index, line in enumerate(verify) if line.startswith("      - ")]
blocks = [verify[left:right] for left, right in zip(starts, [*starts[1:], len(verify)])]
gate_index = next(index for index, block in enumerate(blocks) if block[0] == name)
gate = blocks[gate_index]
assert "        run: |" in gate
assert not any(re.match(r"        (?:if|continue-on-error):", line) for line in gate)
checkout = [block for block in blocks[:gate_index] if "uses: actions/checkout@" in block[0]]
assert len(checkout) == 1 and "          fetch-depth: 0" in checkout[0]
assert not any(re.match(r"        (?:if|continue-on-error):", line) for line in checkout[0])
assert any(block[0] == "      - name: Validate release tag and source commit" for block in blocks[:gate_index])
poll_index = next(index for index, block in enumerate(blocks) if block[0] == "      - name: Require clean integrated checks for the source commit")
assert gate_index < poll_index
before_gate_end = "\n".join(verify[:starts[gate_index] + len(gate)])
assert not re.search(r"\$\{\{\s*(?:secrets\.|github\.token)|\bGH_TOKEN:", before_gate_end)
PY
then
  pass "release catalogue provenance is one unconditional full-history verify gate before credentials and polling"
else
  fail_test "release catalogue provenance is one unconditional full-history verify gate before credentials and polling"
fi

provenance_step="$TMP/provenance-step.sh"
extract_named_step 'Validate release catalogue provenance' > "$provenance_step"
provenance_seed="$TMP/provenance-seed"
git -c init.defaultBranch=main init --quiet --template= "$provenance_seed"
provenance_git() {
  git -C "$provenance_seed" -c user.name='Release contract test' \
    -c user.email=release-contract@example.invalid -c commit.gpgSign=false \
    -c tag.gpgSign=false -c core.hooksPath=/dev/null "$@"
}
provenance_blob=$(printf 'catalogue fixture\n' | provenance_git hash-object -w --stdin)
provenance_tree=$(printf '100644 blob %s\tfixture\n' "$provenance_blob" | provenance_git mktree)
provenance_ancestor=$(provenance_git commit-tree "$provenance_tree" -m 'Catalogue ancestor')
provenance_second=$(provenance_git commit-tree "$provenance_tree" -p "$provenance_ancestor" -m 'Second ancestor')
provenance_source=$(provenance_git commit-tree "$provenance_tree" -p "$provenance_second" -m 'Release source')
provenance_descendant=$(provenance_git commit-tree "$provenance_tree" -p "$provenance_source" -m 'Later commit')
provenance_unrelated=$(provenance_git commit-tree "$provenance_tree" -m 'Unrelated history')
provenance_git update-ref refs/heads/main "$provenance_source"
provenance_git tag -a catalogue-source "$provenance_ancestor" -m 'Annotated catalogue source'
provenance_tag=$(provenance_git rev-parse refs/tags/catalogue-source)
provenance_real_git=$(command -v git)
mapfile -t provenance_locales < <(
  PYTHONPATH="$ROOT" python3 -c \
    'from scripts.i18n_catalogue import LOCALES; print("en"); print(*sorted(LOCALES), sep="\n")'
)
provenance_target_locale=$(
  PYTHONPATH="$ROOT" python3 -c \
    'from scripts.i18n_catalogue import LOCALES; print(sorted(LOCALES)[0])'
)

make_provenance_case() {
  provenance_case="$TMP/provenance-$1"
  cp -a "$provenance_seed" "$provenance_case"
  provenance_catalogues="$provenance_case/app/src/main/assets/i18n"
  mkdir -p "$provenance_catalogues"
  mkdir -p "$provenance_case/scripts"
  cp "$ROOT/scripts/i18n_catalogue.py" "$provenance_case/scripts/i18n_catalogue.py"
  for locale in "${provenance_locales[@]}"; do
    printf '{"sourceRevision":"%s"}\n' "$2" > "$provenance_catalogues/$locale.json"
  done
}

check_provenance_case() {
  local label="$1" expected_error="$2" source_binding="${3-$provenance_source}" status=0
  (
    cd "$provenance_case" || exit 2
    SOURCE_COMMIT="$source_binding" PATH="$provenance_case/mock-bin:$PATH" \
      timeout 10s bash -e "$provenance_step"
  ) > "$provenance_case/output.log" 2>&1 || status=$?
  if { [ -z "$expected_error" ] && [ "$status" -eq 0 ] && \
       grep -Fxq 'Release catalogue provenance verified.' "$provenance_case/output.log" && \
       ! grep -Fq '::error::' "$provenance_case/output.log"; } || \
     { [ -n "$expected_error" ] && [ "$status" -eq 1 ] && \
       grep -Fxq "::error::$expected_error" "$provenance_case/output.log" && \
       ! grep -Fq 'Release catalogue provenance verified.' "$provenance_case/output.log"; }; then
    pass "release catalogue provenance $label"
  else
    sed -n '1,20p' "$provenance_case/output.log" >&2
    fail_test "release catalogue provenance $label"
  fi
}

make_provenance_case valid "$provenance_ancestor"
check_provenance_case 'accepts a valid ancestor' ''
make_provenance_case next-locale "$provenance_ancestor"
python3 - "$provenance_case/scripts/i18n_catalogue.py" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
source, replacements = re.subn(
    r'^LOCALES = \{(?P<values>.+)\}$',
    lambda match: f'LOCALES = {{{match.group("values")}, "nl"}}',
    source,
    count=1,
    flags=re.MULTILINE,
)
assert replacements == 1
path.write_text(source, encoding="utf-8")
PY
cp "$provenance_catalogues/$provenance_target_locale.json" "$provenance_catalogues/nl.json"
check_provenance_case 'derives a newly registered catalogue from locale policy' ''
rm "$provenance_catalogues/nl.json"
check_provenance_case 'rejects an omitted newly registered catalogue' 'Release catalogue files do not match the supported locales.'
make_provenance_case differing "$provenance_ancestor"
printf '{"sourceRevision":"%s"}\n' "$provenance_second" > "$provenance_catalogues/$provenance_target_locale.json"
check_provenance_case 'rejects differing valid ancestors' 'Release catalogues do not share one source revision.'

for revision_case in missing abbreviated symbolic nonstring invalid duplicate; do
  make_provenance_case "$revision_case" "$provenance_ancestor"
  expected_error='Release catalogue source revision is not one full commit SHA.'
  case "$revision_case" in
    missing) printf '{}\n' > "$provenance_catalogues/$provenance_target_locale.json"; label='rejects missing revision' ;;
    abbreviated) printf '{"sourceRevision":"%.12s"}\n' "$provenance_ancestor" > "$provenance_catalogues/$provenance_target_locale.json"; label='rejects abbreviated revision' ;;
    symbolic) printf '{"sourceRevision":"HEAD~1"}\n' > "$provenance_catalogues/$provenance_target_locale.json"; label='rejects symbolic revision' ;;
    nonstring) printf '{"sourceRevision":123}\n' > "$provenance_catalogues/$provenance_target_locale.json"; label='rejects nonstring revision' ;;
    invalid) printf '{invalid\n' > "$provenance_catalogues/$provenance_target_locale.json"; label='rejects invalid JSON'; expected_error='Unable to inspect release catalogue provenance.' ;;
    duplicate) printf '{"sourceRevision":"%s","sourceRevision":"%s"}\n' "$provenance_ancestor" "$provenance_ancestor" > "$provenance_catalogues/$provenance_target_locale.json"; label='rejects duplicate JSON key'; expected_error='Release catalogue contains a duplicate JSON key.' ;;
  esac
  if [ "$revision_case" = abbreviated ] || [ "$revision_case" = symbolic ]; then
    for locale in "${provenance_locales[@]}"; do
      [ "$locale" = "$provenance_target_locale" ] || \
        cp "$provenance_catalogues/$provenance_target_locale.json" "$provenance_catalogues/$locale.json"
    done
  fi
  check_provenance_case "$label" "$expected_error"
done

make_provenance_case missing-catalogue "$provenance_ancestor"
mv "$provenance_catalogues/$provenance_target_locale.json" "$provenance_case/$provenance_target_locale.json"
check_provenance_case 'rejects missing catalogue' 'Release catalogue files do not match the supported locales.'
make_provenance_case extra-catalogue "$provenance_ancestor"
cp "$provenance_catalogues/$provenance_target_locale.json" "$provenance_catalogues/extra-locale.json"
check_provenance_case 'rejects extra catalogue' 'Release catalogue files do not match the supported locales.'

for object_case in missing blob tree tag unrelated descendant; do
  expected_error='Release catalogue source revision does not name a commit.'
  case "$object_case" in
    missing) revision=0000000000000000000000000000000000000000; label='rejects missing object'; expected_error='Unable to inspect release catalogue provenance.' ;;
    blob) revision="$provenance_blob"; label='rejects blob object' ;;
    tree) revision="$provenance_tree"; label='rejects tree object' ;;
    tag) revision="$provenance_tag"; label='rejects annotated tag object' ;;
    unrelated) revision="$provenance_unrelated"; label='rejects unrelated commit'; expected_error='Release catalogue source revision is not an ancestor of the release source.' ;;
    descendant) revision="$provenance_descendant"; label='rejects descendant commit'; expected_error='Release catalogue source revision is not an ancestor of the release source.' ;;
  esac
  make_provenance_case "$object_case-object" "$revision"
  check_provenance_case "$label" "$expected_error"
done

# Keep the named ancestor object available so only the shallow-history guard rejects this case.
make_provenance_case shallow "$provenance_source"
printf '%s\n' "$provenance_source" > "$provenance_case/.git/shallow"
check_provenance_case 'rejects shallow history' 'Release catalogue provenance requires full Git history.'

for git_error in inspection ancestry; do
  make_provenance_case "git-$git_error" "$provenance_ancestor"
  mkdir -p "$provenance_case/mock-bin"
  if [ "$git_error" = inspection ]; then
    git_command=cat-file
    expected_error='Unable to inspect release catalogue provenance.'
  else
    git_command=merge-base
    expected_error='Release catalogue source revision is not an ancestor of the release source.'
  fi
  printf '#!/usr/bin/env bash\nif [ "$1" = "%s" ]; then exit 128; fi\nexec "%s" "$@"\n' \
    "$git_command" "$provenance_real_git" > "$provenance_case/mock-bin/git"
  chmod +x "$provenance_case/mock-bin/git"
  check_provenance_case "fails closed on Git $git_error error" "$expected_error"
done

for binding_case in missing malformed mismatched; do
  make_provenance_case "binding-$binding_case" "$provenance_ancestor"
  case "$binding_case" in
    missing) source_binding='' ;;
    malformed) source_binding=HEAD ;;
    mismatched) source_binding="$provenance_descendant" ;;
  esac
  check_provenance_case "rejects $binding_case source binding" 'Release catalogue source binding is invalid.' "$source_binding"
done

descriptor_step="$(extract_named_step 'Generate bounded install descriptor without release credentials')"
proof_step="$(extract_named_step 'Sign and authenticate release proofs')"
final_step="$(extract_named_step 'Final exact verification before publication')"
if grep -Fq 'cp scripts/generate_install_descriptor.py release-input/generate_install_descriptor.py' <<<"$package_job" && \
   [ "$(grep -Fc 'generate_install_descriptor.py \' <<<"$package_job")" -eq 1 ] && \
   grep -Fq 'sha256sum android-gradle-runtime.cdx.json generate_install_descriptor.py' <<<"$package_job" && \
   grep -Fq 'generate_install_descriptor.py \' <<<"$publish_job" && \
   grep -Fq '(cd release-input && sha256sum --check MANIFEST.sha256)' <<<"$publish_job" && \
   grep -Fq '/usr/bin/setpriv \' <<<"$descriptor_step" && \
   grep -Fq -- '--reuid=65534 \' <<<"$descriptor_step" && \
   grep -Fq 'SYS_landlock_restrict_self' <<<"$descriptor_step" && \
   grep -Fq '/usr/bin/env -i \' <<<"$descriptor_step" && \
   grep -Fq '/usr/bin/python3 "$workspace/release-input/generate_install_descriptor.py" "${generator_args[@]}"' <<<"$descriptor_step" && \
   ! grep -Fq 'KEYSTORE_B64: ${{' <<<"$(extract_named_step_yaml 'Generate bounded install descriptor without release credentials')" && \
   ! grep -Eq '(^|[[:space:]])(python3|release-input/[^[:space:]]+\.py)([[:space:]]|$)' <<<"$proof_step" && \
   ! grep -Fq -- '-srcstorepass "$KEYSTORE_PASSWORD"' <<<"$proof_step" && \
   ! grep -Fq -- '-srckeypass "$KEY_PASSWORD"' <<<"$proof_step" && \
   ! grep -Fq -- '-passin "pass:' <<<"$proof_step" && \
   grep -Fq 'Install descriptor is not the exact canonical 13-field APK contract.' <<<"$proof_step" && \
   grep -Fq 'for metadata_name in "$descriptor_name" "$bridge_descriptor_name" "$protocol_name"; do' <<<"$proof_step" && \
   grep -Fq '/usr/bin/openssl dgst -sha256 -verify "$public_key" -signature "dist/$metadata_name.sig"' <<<"$proof_step" && \
   grep -Fq 'Final exact verification before publication' <<<"$publish_job" && \
   grep -Fq '/usr/bin/openssl dgst -sha256 -verify "$public_key" -signature "dist/$descriptor_name.sig"' <<<"$final_step" && \
   grep -Fq 'files: dist/*' <<<"$publish_job"; then
  pass "release seals, isolates, revalidates, signs and publishes the exact APK install descriptor"
else
  fail_test "release seals, isolates, revalidates, signs and publishes the exact APK install descriptor"
fi

# Source-text reason: hosted tool installation and the credentialed tool paths cannot be replaced
# by the fixture executables below; require the same pinned SDK version across those boundaries.
if grep -Fq 'build-tools/36.0.0' <<<"$descriptor_step" && \
   grep -Fq 'build-tools/36.0.0' <<<"$proof_step" && \
   grep -Fq 'build-tools/36.0.0' <<<"$final_step" && \
   [ "$(grep -Fc 'build-tools;36.0.0' <<<"$publish_job")" -eq 1 ] && \
   ! grep -Eiq 'build-tools/(latest|[0-9]+\.[0-9]+\.[1-9][0-9]*)' <<<"$descriptor_step$proof_step$final_step"; then
  pass "descriptor generation and verification stay pinned to Android Build-Tools 36.0.0"
else
  fail_test "descriptor generation and verification stay pinned to Android Build-Tools 36.0.0"
fi

descriptor_step_yaml="$(extract_named_step_yaml 'Generate bounded install descriptor without release credentials')"
proof_step_yaml="$(extract_named_step_yaml 'Sign and authenticate release proofs')"
final_step_yaml="$(extract_named_step_yaml 'Final exact verification before publication')"
asset_step="$(extract_named_step 'Sign and validate release APK')"
if grep -Fq '/usr/bin/install -d -m 0755 dist' <<<"$asset_step" && \
   grep -Fq 'identity_idsig="$identity_signed_apk.idsig"' <<<"$asset_step" && \
   grep -Fq 'APK Signature Scheme v4 sidecar is not one regular nofollow file.' <<<"$asset_step" && \
   grep -Fq 'APK Signature Scheme v4 sidecar is empty or exceeds 1 MiB.' <<<"$asset_step" && \
   [ "$(grep -Fc -- '--v4-signature-file "$identity_idsig"' <<<"$asset_step")" -eq 2 ] && \
   grep -Fq '/usr/bin/chmod 0644 "$identity_signed_apk" "$identity_idsig"' <<<"$asset_step" && \
   grep -Fq 'subject_idsig="$subject_apk.idsig"' <<<"$proof_step" && \
   [ "$(grep -Fc -- '--v4-signature-file "$subject_idsig"' <<<"$proof_step")" -eq 2 ] && \
   grep -Fq '"$apk_name.idsig" \' <<<"$final_step" && \
   grep -Fq '"$successor_apk_name.idsig" \' <<<"$final_step" && \
   grep -Fq 'apk_idsig="dist/$apk_name.idsig"' <<<"$final_step" && \
   grep -Fq 'successor_apk_idsig="dist/$successor_apk_name.idsig"' <<<"$final_step" && \
   [ "$(grep -Fc -- '--v4-signature-file "dist/$subject.idsig"' <<<"$final_step")" -eq 2 ] && \
   grep -Fq 'Final APK Signature Scheme v4 sidecar is empty or exceeds 1 MiB: $sidecar' <<<"$final_step" && \
   grep -Fq 'for sidecar in "$apk_idsig" "$successor_apk_idsig"' <<<"$final_step"; then
  pass "both signed APKs and their bounded V4 sidecars remain in the exact readable release set"
else
  fail_test "both signed APKs and their bounded V4 sidecars remain in the exact readable release set"
fi
# Both tag gates in the workflow run, as written, over the shared release corpus: stable and rc
# tags pass, every other kind is refused.
tag_gates="$(grep -E '^[[:space:]]*if .*=~ .*RELEASE_TAG|^[[:space:]]*if .*RELEASE_TAG.*=~' "$WORKFLOW" | sed 's/^[[:space:]]*//')"
tag_gate_disagreements="$(
  while IFS= read -r gate; do
    while IFS=$'\t' read -r kind tag; do
      verdict="$(RELEASE_TAG="$tag" bash -c "$gate echo refused; else echo accepted; fi")"
      case "$kind:$verdict" in stable:accepted|rc:accepted|build:refused|refused:refused) ;; *) echo "$kind $tag -> $verdict" ;; esac
    done <<<"$release_tag_vectors"
  done <<<"$tag_gates"
)"
if [ "$(grep -c . <<<"$tag_gates")" -eq 2 ] && [ -z "$tag_gate_disagreements" ]; then
  pass "both release tag gates agree with the shared release corpus"
else
  printf '# %s\n' "$tag_gate_disagreements"
  fail_test "both release tag gates agree with the shared release corpus"
fi
# Without the shared corpus the suite must fail rather than pass its tag checks over nothing. The copy
# differs only by the missing corpus; the file test stops a copy whose load went silent from recursing.
mkdir -p "$TMP/no-corpus"
cp -R "$ROOT/scripts" "$ROOT/.github" "$TMP/no-corpus/"
rm -f "$TMP/no-corpus/scripts/tests/fixtures/release-identity-corpus.json"
if [ ! -f "$ROOT/scripts/tests/fixtures/release-identity-corpus.json" ] || \
   RELEASE_WORKFLOW_UNDER_TEST="$WORKFLOW" bash "$TMP/no-corpus/scripts/tests/release_workflow_test.sh" >/dev/null 2>&1; then
  fail_test "the suite fails when the shared release corpus is missing"
else
  pass "the suite fails when the shared release corpus is missing"
fi
if ! grep -Eq '^[[:space:]]*if:[[:space:]]*(\$\{\{[[:space:]]*)?false' <<<"$descriptor_step_yaml$proof_step_yaml$final_step_yaml" && \
   ! grep -Eq '^[[:space:]]*continue-on-error:[[:space:]]*true' <<<"$descriptor_step_yaml$proof_step_yaml$final_step_yaml" && \
   ! grep -Eq '^[[:space:]]*if[[:space:]]+false([[:space:];]|$)' <<<"$descriptor_step" && \
   grep -Fq 'scripts/tests/release_workflow_test.sh' "$ROOT/.github/workflows/ci.yml"; then
  pass "active descriptor step and regular host-CI coverage are explicit"
else
  fail_test "active descriptor step and regular host-CI coverage are explicit"
fi

for shell_step in "$descriptor_step" "$proof_step" "$final_step"; do
  if ! bash -n <<<"$shell_step"; then
    fail_test "descriptor release workflow shell is syntactically valid"
    shell_step_syntax_failed=1
    break
  fi
done
if [ "${shell_step_syntax_failed:-0}" -eq 0 ]; then
  pass "descriptor release workflow shell is syntactically valid"
fi

descriptor_case="$TMP/descriptor-contract"
mkdir -p "$descriptor_case/release-input" "$descriptor_case/dist" \
  "$descriptor_case/android/build-tools/36.0.0" "$descriptor_case/runner-temp" \
  "$descriptor_case/forbidden-write"
chmod 0755 "$TMP" "$descriptor_case" "$descriptor_case/release-input" "$descriptor_case/dist" \
  "$descriptor_case/android" "$descriptor_case/android/build-tools" \
  "$descriptor_case/android/build-tools/36.0.0" "$descriptor_case/runner-temp"
chmod 0777 "$descriptor_case/forbidden-write"
cp "$ROOT/scripts/generate_install_descriptor.py" "$descriptor_case/release-input/generate_install_descriptor.py"
# `apk_name` is the descriptor's subject, which is the successor. The bridge is published beside
# it under the name every earlier release used, because shipped updaters take the first `.apk`.
apk_name=panel-assistant-v1.2.3-rc1-manual-setup-required.apk
bridge_apk_name=ha-paneld-v1.2.3-rc1-manual-setup-required.apk
descriptor_name=ha-paneld-v1.2.3-rc1-install.json
bridge_descriptor_name=ha-paneld-v1.2.3-rc1-bridge-install.json
protocol_name=ha-paneld-v1.2.3-rc1-protocol.json
printf 'authenticated release APK fixture\n' > "$descriptor_case/dist/$apk_name"
printf 'authenticated bridge release APK fixture\n' > "$descriptor_case/dist/$bridge_apk_name"
cat > "$descriptor_case/android/build-tools/36.0.0/aapt" <<'EOF'
#!/usr/bin/env bash
set -eu
apk_path="$*"
for argument in "$@"; do
  case "$argument" in
    /proc/self/fd/*)
      apk_path="$(readlink "$argument")"
      [ -z "${POISON+x}" ] && [ -z "${KEYSTORE_B64+x}" ] || exit 90
      escape_path="$(dirname "$0")/../../../../forbidden-write/escape"
      if printf 'escaped\n' > "$escape_path" 2>/dev/null; then
        exit 92
      fi
      ;;
  esac
done
# Badging is reproduced in the exact shape real aapt emits for these APKs, captured from
# `aapt dump badging` on a locally built release APK: the trailing platformBuildVersion and
# compileSdkVersion fields on the package line, the doubled space before `label`, and the ABI
# order aapt actually prints. A hand-shortened package line hides whether the workflow's greedy
# `.* name='...'` extraction picks the package or a later attribute.
#
# The successor carries the new applicationId while its launcher class stays in the Gradle
# namespace, which does not move. Both are read from the real artifact, not assumed.
case "$*" in
  *"dump badging"*)
    if [ "${MOCK_BADGING_IDENTITY:-}" = bridge ] || case "$apk_path" in *ha-paneld-v1.2.3-rc1-manual-setup-required.apk*) true ;; *) false ;; esac; then
      cat <<'BRIDGE_BADGING'
package: name='io.github.maxlyth.hapaneld' versionCode='702' versionName='1.2.3-rc1' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
sdkVersion:'26'
launchable-activity: name='io.github.maxlyth.hapaneld.MainActivity'  label='' icon=''
native-code: 'arm64-v8a' 'armeabi-v7a'
BRIDGE_BADGING
      exit 0
    fi
    cat <<'BADGING'
package: name='io.panelassistant.android' versionCode='701' versionName='1.2.3-rc1' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
sdkVersion:'26'
launchable-activity: name='io.github.maxlyth.hapaneld.MainActivity'  label='' icon=''
native-code: 'arm64-v8a' 'armeabi-v7a'
BADGING
    ;;
  *"dump xmltree"*)
    if [ "${MOCK_XMLTREE_MODE:-}" = foreign-root ]; then
      cat <<'FOREIGN_XMLTREE'
E: manifest (line=2)
  E: application (line=8)
E: foreign-root (line=20)
  E: application (line=21)
    E: meta-data (line=22)
      A: android:name(0x01010003)="io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY"
      A: android:value(0x01010024)="hapaneld-db:v1:ha-paneld.db:11:14"
FOREIGN_XMLTREE
      exit 0
    fi
    cat <<'XMLTREE'
E: manifest (line=2)
  E: application (line=8)
    E: meta-data (line=10)
      A: android:name(0x01010003)="io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY" (Raw: "io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY")
      A: android:value(0x01010024)="hapaneld-db:v1:ha-paneld.db:11:14" (Raw: "hapaneld-db:v1:ha-paneld.db:11:14")
    E: meta-data (line=12)
      A: android:name(0x01010003)="io.github.maxlyth.hapaneld.PANEL_ASSISTANT_PROTOCOL" (Raw: "io.github.maxlyth.hapaneld.PANEL_ASSISTANT_PROTOCOL")
      A: android:value(0x01010024)="hapaneld-native:v1:3:3" (Raw: "hapaneld-native:v1:3:3")
    E: activity (line=20)
XMLTREE
    ;;
  *) exit 91 ;;
esac
EOF
cat > "$descriptor_case/android/build-tools/36.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
set -eu
v4_signature_file=
apk=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --v4-signature-file)
      [ "$#" -ge 2 ] || exit 93
      v4_signature_file=$2
      shift 2
      ;;
    --*) shift ;;
    *) apk=$1; shift ;;
  esac
done
if [ -n "$v4_signature_file" ]; then
  [ "$v4_signature_file" = "$apk.idsig" ] || exit 94
  if ! grep -Fxq 'APK Signature Scheme v4 fixture' "$v4_signature_file"; then
    printf 'V4 signature fixture is invalid.\n' >&2
    exit 95
  fi
fi
printf '%s\n' 'Signer #1 certificate SHA-256 digest: ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339'
EOF
chmod 0755 "$descriptor_case/android/build-tools/36.0.0/aapt" \
  "$descriptor_case/android/build-tools/36.0.0/apksigner"

java_home="$(dirname "$(dirname "$(readlink -f "$(command -v keytool)")")")"
if (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    POISON=must-not-reach-generator \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <(extract_named_step 'Generate bounded install descriptor without release credentials')
) > "$descriptor_case/generate.log" 2>&1 && \
   [ -f "$descriptor_case/dist/$descriptor_name" ] && \
   [ ! -L "$descriptor_case/dist/$descriptor_name" ] && \
   [ ! -e "$descriptor_case/forbidden-write/escape" ] && \
   [ "$(jq 'keys | length' "$descriptor_case/dist/$descriptor_name")" -eq 13 ] && \
   jq -e --arg apk_name "$apk_name" '
     .schema == "io.github.maxlyth.hapaneld.install.v1" and
     .releaseTag == "v1.2.3-rc1" and
     .versionName == "1.2.3-rc1" and
     .versionCode == 701 and
     .apkName == $apk_name and
     .minSdk == 26 and
     .supportedAbis == ["arm64-v8a", "armeabi-v7a"] and
     .databaseCompatibility == "hapaneld-db:v1:ha-paneld.db:11:14" and
     .packageId == "io.panelassistant.android" and
     .launchComponent == "io.panelassistant.android/io.github.maxlyth.hapaneld.MainActivity"
   ' "$descriptor_case/dist/$descriptor_name" >/dev/null; then
  pass "credential-free uid-65534 step behaviorally generates the exact 13-field descriptor"
else
  sed -n '1,80p' "$descriptor_case/generate.log" >&2
  fail_test "credential-free uid-65534 step behaviorally generates the exact 13-field descriptor"
fi

if jq -e --arg name "$bridge_apk_name" \
    --arg hash "$(sha256sum "$descriptor_case/dist/$bridge_apk_name" | cut -d' ' -f1)" \
    --argjson size "$(stat --format='%s' "$descriptor_case/dist/$bridge_apk_name")" '
    (keys | length) == 13 and .apkName == $name and .apkSha256 == $hash and .apkSize == $size and
    .packageId == "io.github.maxlyth.hapaneld" and .versionCode == 702 and
    .launchComponent == "io.github.maxlyth.hapaneld/io.github.maxlyth.hapaneld.MainActivity" and
    .schema == "io.github.maxlyth.hapaneld.install.v1" and
    .signerCertificateSha256 == "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"' \
    "$descriptor_case/dist/$bridge_descriptor_name" >/dev/null; then
  pass "credential-free generation binds the bridge's own APK bytes, package and versionCode"
else
  fail_test "credential-free generation binds the bridge's own APK bytes, package and versionCode"
fi

if jq -e --arg successor "$(sha256sum "$descriptor_case/dist/$apk_name" | cut -d' ' -f1)" \
    --arg bridge "$(sha256sum "$descriptor_case/dist/$bridge_apk_name" | cut -d' ' -f1)" '
    keys == ["artifacts","schema"] and .schema == "io.github.maxlyth.hapaneld.protocol.v1" and
    (.artifacts | map(.apkSha256)) == ([$bridge,$successor] | sort) and
    all(.artifacts[]; .protocolMin == 3 and .protocolMax == 3)' \
    "$descriptor_case/dist/$protocol_name" >/dev/null; then
  pass "credential-free generator publishes a protocol companion binding both exact APK hashes"
else
  fail_test "credential-free generator publishes a protocol companion binding both exact APK hashes"
fi

key_store="$descriptor_case/test-release.p12"
key_password=test-release-password
key_alias=test-release
"$java_home/bin/keytool" -genkeypair \
  -alias "$key_alias" \
  -keyalg RSA \
  -keysize 2048 \
  -dname 'CN=release workflow contract test' \
  -validity 2 \
  -storetype PKCS12 \
  -keystore "$key_store" \
  -storepass "$key_password" \
  -keypass "$key_password" \
  -noprompt >/dev/null 2>&1
"$java_home/bin/keytool" -exportcert -rfc \
  -alias "$key_alias" \
  -keystore "$key_store" \
  -storepass "$key_password" \
  > "$descriptor_case/test-release-certificate.pem"
openssl x509 -pubkey -noout \
  -in "$descriptor_case/test-release-certificate.pem" \
  > "$descriptor_case/test-release-public-key.pem"
test_public_key_sha256=$(openssl pkey -pubin \
  -in "$descriptor_case/test-release-public-key.pem" \
  -outform DER | sha256sum | cut -d' ' -f1)
for release_script in \
  "ha-paneld-installer-v1.2.3-rc1.sh" \
  "ha-paneld-provision-v1.2.3-rc1.sh"; do
  {
    printf '%s\n' '#!/usr/bin/env bash' 'write_release_public_key() {'
    cat "$descriptor_case/test-release-public-key.pem"
    printf '%s\n' '}'
  } > "$descriptor_case/dist/$release_script"
  chmod 0755 "$descriptor_case/dist/$release_script"
done
printf 'arm helper fixture\n' > "$descriptor_case/dist/ha-paneld-helper-v1.2.3-rc1-armeabi-v7a"
printf 'arm64 helper fixture\n' > "$descriptor_case/dist/ha-paneld-helper-v1.2.3-rc1-arm64-v8a"
printf '{}\n' > "$descriptor_case/dist/ha-paneld-v1.2.3-rc1-android-gradle-runtime.cdx.json"
printf '{}\n' > "$descriptor_case/dist/ha-paneld-v1.2.3-rc1-profile-editor-runtime.cdx.json"
printf 'APK Signature Scheme v4 fixture\n' > "$descriptor_case/dist/$apk_name.idsig"
printf 'APK Signature Scheme v4 fixture\n' > "$descriptor_case/dist/$bridge_apk_name.idsig"
(
  cd "$descriptor_case/dist" || exit 1
  for subject in \
    "$apk_name" \
    "$bridge_apk_name" \
    ha-paneld-provision-v1.2.3-rc1.sh \
    ha-paneld-helper-v1.2.3-rc1-armeabi-v7a \
    ha-paneld-helper-v1.2.3-rc1-arm64-v8a; do
    sha256sum "$subject" > "$subject.sha256"
  done
)
keystore_b64=$(base64 -w 0 "$key_store")
test_proof_step=$(sed \
  "s/502bf38874682ff337f187022b904adbc3ab0b387fd7ceb4043ce722997273f3/$test_public_key_sha256/g" \
  <<<"$proof_step")
if (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/proof.log" 2>&1 && \
   openssl dgst -sha256 \
     -verify "$descriptor_case/test-release-public-key.pem" \
     -signature "$descriptor_case/dist/$descriptor_name.sig" \
     "$descriptor_case/dist/$descriptor_name" >/dev/null; then
  pass "proof step behaviorally rechecks content, signs the descriptor, and verifies its signature"
else
  sed -n '1,120p' "$descriptor_case/proof.log" >&2
  fail_test "proof step behaviorally rechecks content, signs the descriptor, and verifies its signature"
fi

if openssl dgst -sha256 -verify "$descriptor_case/test-release-public-key.pem" \
    -signature "$descriptor_case/dist/$protocol_name.sig" "$descriptor_case/dist/$protocol_name" >/dev/null; then
  pass "proof step signs and verifies the exact APK protocol companion"
else
  fail_test "proof step signs and verifies the exact APK protocol companion"
fi
if openssl dgst -sha256 -verify "$descriptor_case/test-release-public-key.pem" \
    -signature "$descriptor_case/dist/$bridge_descriptor_name.sig" "$descriptor_case/dist/$bridge_descriptor_name" >/dev/null; then
  pass "proof step signs and verifies the bridge descriptor with the same authority"
else
  fail_test "proof step signs and verifies the bridge descriptor with the same authority"
fi
cp "$descriptor_case/dist/$bridge_descriptor_name" "$descriptor_case/original-bridge-descriptor.json"
for mutation in '.versionCode = 701' '.packageId = "io.panelassistant.android"' '.apkSha256 = ("0" * 64)'; do
  jq -cS "$mutation" "$descriptor_case/original-bridge-descriptor.json" > "$descriptor_case/dist/$bridge_descriptor_name"
  rm -f "$descriptor_case/dist/$bridge_descriptor_name.sig"
  if ! (
    cd "$descriptor_case" || exit 1
    ANDROID_HOME="$descriptor_case/android" JAVA_HOME="$java_home" \
      KEYSTORE_B64="$keystore_b64" KEYSTORE_PASSWORD="$key_password" KEY_ALIAS="$key_alias" KEY_PASSWORD="$key_password" \
      RELEASE_TAG=v1.2.3-rc1 RUNNER_TEMP="$descriptor_case/runner-temp" bash <<<"$test_proof_step"
  ) > "$descriptor_case/bridge-tampered-proof.log" 2>&1 && \
     [ ! -e "$descriptor_case/dist/$bridge_descriptor_name.sig" ] && \
     grep -Fq 'exact canonical 13-field APK contract' "$descriptor_case/bridge-tampered-proof.log"; then
    pass "proof refuses altered bridge descriptor before signing: $mutation"
  else
    fail_test "proof refuses altered bridge descriptor before signing: $mutation"
  fi
  cp "$descriptor_case/original-bridge-descriptor.json" "$descriptor_case/dist/$bridge_descriptor_name"
done

cp "$descriptor_case/dist/$protocol_name" "$descriptor_case/original-protocol.json"
jq -cS '.artifacts[0].protocolMax = 4' "$descriptor_case/original-protocol.json" > "$descriptor_case/dist/$protocol_name"
rm -f "$descriptor_case/dist/$protocol_name.sig"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" KEY_PASSWORD="$key_password" RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" bash <<<"$test_proof_step"
) > "$descriptor_case/tampered-protocol-proof.log" 2>&1 && \
   [ ! -e "$descriptor_case/dist/$protocol_name.sig" ] && \
   grep -Fq 'Protocol companion does not bind the exact signed APK pair' "$descriptor_case/tampered-protocol-proof.log"; then
  pass "proof signing refuses a changed protocol range despite an unchanged APK hash"
else
  sed -n '1,80p' "$descriptor_case/tampered-protocol-proof.log" >&2
  fail_test "proof signing refuses a changed protocol range despite an unchanged APK hash"
fi
cp "$descriptor_case/original-protocol.json" "$descriptor_case/dist/$protocol_name"

cp "$descriptor_case/dist/$descriptor_name" "$descriptor_case/original-descriptor.json"
jq -cS '.versionCode = 702' "$descriptor_case/original-descriptor.json" \
  > "$descriptor_case/dist/$descriptor_name"
rm -f "$descriptor_case/dist/$descriptor_name.sig"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/tampered-proof.log" 2>&1 && \
   [ ! -e "$descriptor_case/dist/$descriptor_name.sig" ] && \
   grep -Fq 'exact canonical 13-field APK contract' "$descriptor_case/tampered-proof.log"; then
  pass "proof signing refuses a descriptor whose authenticated APK content binding was changed"
else
  sed -n '1,80p' "$descriptor_case/tampered-proof.log" >&2
  fail_test "proof signing refuses a descriptor whose authenticated APK content binding was changed"
fi

cp "$descriptor_case/original-descriptor.json" "$descriptor_case/dist/$descriptor_name"
rm -f "$descriptor_case/dist/$descriptor_name.sig"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    MOCK_XMLTREE_MODE=foreign-root \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/foreign-root-proof.log" 2>&1 && \
   [ ! -e "$descriptor_case/dist/$descriptor_name.sig" ] && \
   grep -Fq 'database compatibility contract is invalid' "$descriptor_case/foreign-root-proof.log"; then
  pass "proof signing rejects database metadata from a same-indent foreign application node"
else
  sed -n '1,80p' "$descriptor_case/foreign-root-proof.log" >&2
  fail_test "proof signing rejects database metadata from a same-indent foreign application node"
fi

(
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    JAVA_HOME="$java_home" \
    KEYSTORE_B64="$keystore_b64" \
    KEYSTORE_PASSWORD="$key_password" \
    KEY_ALIAS="$key_alias" \
    KEY_PASSWORD="$key_password" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_proof_step"
) > "$descriptor_case/restored-proof.log" 2>&1
test_final_step=$(sed \
  "s/502bf38874682ff337f187022b904adbc3ab0b387fd7ceb4043ce722997273f3/$test_public_key_sha256/g" \
  <<<"$final_step")
if (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/final.log" 2>&1; then
  pass "final pre-upload step behaviorally verifies the exact asset set, checksums, APK signer, and signatures"
else
  sed -n '1,120p' "$descriptor_case/final.log" >&2
  fail_test "final pre-upload step behaviorally verifies the exact asset set, checksums, APK signer, and signatures"
fi

mv "$descriptor_case/dist/$bridge_descriptor_name" "$descriptor_case/held-bridge-descriptor.json"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" bash <<<"$test_final_step"
) > "$descriptor_case/missing-bridge-final.log" 2>&1 && \
   grep -Fq 'Final release asset set is not exact' "$descriptor_case/missing-bridge-final.log"; then
  pass "final exact inventory requires the bridge descriptor asset"
else
  fail_test "final exact inventory requires the bridge descriptor asset"
fi
mv "$descriptor_case/held-bridge-descriptor.json" "$descriptor_case/dist/$bridge_descriptor_name"
cp "$descriptor_case/dist/$bridge_descriptor_name.sig" "$descriptor_case/original-bridge.sig"
printf '\n' >> "$descriptor_case/dist/$bridge_descriptor_name"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" bash <<<"$test_final_step"
) > "$descriptor_case/bridge-tampered-final.log" 2>&1; then
  pass "final publication refuses bridge descriptor bytes changed after signing"
else
  fail_test "final publication refuses bridge descriptor bytes changed after signing"
fi
cp "$descriptor_case/original-bridge-descriptor.json" "$descriptor_case/dist/$bridge_descriptor_name"

cp "$descriptor_case/dist/$apk_name.idsig" "$descriptor_case/original.idsig"
printf 'corrupt V4 signature fixture\n' > "$descriptor_case/dist/$apk_name.idsig"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/corrupt-idsig-final.log" 2>&1 && \
   grep -Fq 'V4 signature fixture is invalid.' "$descriptor_case/corrupt-idsig-final.log"; then
  pass "final pre-upload verification rejects a corrupt V4 signature sidecar"
else
  fail_test "final pre-upload verification rejects a corrupt V4 signature sidecar"
fi
mv "$descriptor_case/original.idsig" "$descriptor_case/dist/$apk_name.idsig"

openssl pkcs12 \
  -in "$key_store" \
  -nodes \
  -nocerts \
  -passin "pass:$key_password" \
  -out "$descriptor_case/test-release-private-key.pem" >/dev/null 2>&1
for mutation in '.versionCode = 701' '.packageId = "io.panelassistant.android"' '.apkSha256 = ("0" * 64)'; do
  jq -cS "$mutation" "$descriptor_case/original-bridge-descriptor.json" > "$descriptor_case/dist/$bridge_descriptor_name"
  openssl dgst -sha256 -sign "$descriptor_case/test-release-private-key.pem" \
    -out "$descriptor_case/dist/$bridge_descriptor_name.sig" "$descriptor_case/dist/$bridge_descriptor_name"
  if ! (
    cd "$descriptor_case" || exit 1
    ANDROID_HOME="$descriptor_case/android" RELEASE_TAG=v1.2.3-rc1 \
      RUNNER_TEMP="$descriptor_case/runner-temp" bash <<<"$test_final_step"
  ) > "$descriptor_case/bridge-mixed-final.log" 2>&1 && \
     grep -Fq 'does not bind the exact final release APK' "$descriptor_case/bridge-mixed-final.log"; then
    pass "final publication refuses validly signed foreign bridge proof: $mutation"
  else
    fail_test "final publication refuses validly signed foreign bridge proof: $mutation"
  fi
done
cp "$descriptor_case/original-bridge-descriptor.json" "$descriptor_case/dist/$bridge_descriptor_name"
cp "$descriptor_case/original-bridge.sig" "$descriptor_case/dist/$bridge_descriptor_name.sig"
jq -cS '.artifacts[0].apkSha256 = ("0" * 64)' "$descriptor_case/original-protocol.json" > "$descriptor_case/dist/$protocol_name"
openssl dgst -sha256 -sign "$descriptor_case/test-release-private-key.pem" \
  -out "$descriptor_case/dist/$protocol_name.sig" "$descriptor_case/dist/$protocol_name"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" bash <<<"$test_final_step"
) > "$descriptor_case/foreign-protocol-final.log" 2>&1 && \
   grep -Fq 'Final protocol companion does not bind the exact final APK pair' "$descriptor_case/foreign-protocol-final.log"; then
  pass "final pre-upload verification rejects a valid signed protocol companion for another APK"
else
  sed -n '1,80p' "$descriptor_case/foreign-protocol-final.log" >&2
  fail_test "final pre-upload verification rejects a valid signed protocol companion for another APK"
fi
cp "$descriptor_case/original-protocol.json" "$descriptor_case/dist/$protocol_name"
openssl dgst -sha256 -sign "$descriptor_case/test-release-private-key.pem" \
  -out "$descriptor_case/dist/$protocol_name.sig" "$descriptor_case/dist/$protocol_name"
printf 'different valid same-tag APK fixture\n' > "$descriptor_case/foreign.apk"
foreign_apk_sha256=$(sha256sum "$descriptor_case/foreign.apk" | cut -d' ' -f1)
foreign_apk_size=$(stat --format='%s' "$descriptor_case/foreign.apk")
jq -cS \
  --arg apk_sha256 "$foreign_apk_sha256" \
  --argjson apk_size "$foreign_apk_size" \
  '.apkSha256 = $apk_sha256 | .apkSize = $apk_size' \
  "$descriptor_case/original-descriptor.json" > "$descriptor_case/dist/$descriptor_name"
openssl dgst -sha256 \
  -sign "$descriptor_case/test-release-private-key.pem" \
  -out "$descriptor_case/dist/$descriptor_name.sig" \
  "$descriptor_case/dist/$descriptor_name"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/mixed-valid-final.log" 2>&1 && \
   grep -Fq 'does not bind the exact final release APK' "$descriptor_case/mixed-valid-final.log"; then
  pass "final pre-upload verification rejects a valid signed descriptor for another same-tag APK"
else
  sed -n '1,80p' "$descriptor_case/mixed-valid-final.log" >&2
  fail_test "final pre-upload verification rejects a valid signed descriptor for another same-tag APK"
fi

cp "$descriptor_case/original-descriptor.json" "$descriptor_case/dist/$descriptor_name"
openssl dgst -sha256 \
  -sign "$descriptor_case/test-release-private-key.pem" \
  -out "$descriptor_case/dist/$descriptor_name.sig" \
  "$descriptor_case/dist/$descriptor_name"
printf '\n' >> "$descriptor_case/dist/$descriptor_name"
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/tampered-final.log" 2>&1; then
  pass "final pre-upload verification refuses a descriptor changed after proof signing"
else
  fail_test "final pre-upload verification refuses a descriptor changed after proof signing"
fi

# --- two installable identities from one tag -------------------------------------------------
#
# The release publishes a `bridge` APK under the legacy application id and a `successor` APK under
# the new one. Three things have to hold together or panels in the field break:
#   1. both are built, and each is asserted to carry its own application id;
#   2. the bridge keeps the exact asset name every earlier release used;
#   3. the bridge is the FIRST `.apk` asset of the published release, because every shipped 0.9.7
#      and 0.9.8-rc1 updater resolves its download by taking the first `.apk` it finds.
# A panel handed the successor by that rule cannot install it over the package it is running, and
# it stops updating with no remote recovery.

build_step="$(extract_named_step 'Build release APKs for both identities')"
collect_step="$(extract_named_step 'Collect unsigned release inputs')"
stage_step="$(extract_named_step 'Stage shared release inputs')"
ordering_step="$(extract_named_step 'Verify shipped updaters resolve the bridge APK')"

if grep -Fq 'bridge) ./gradlew :app:assembleRelease -x lintVitalRelease --stacktrace ;;' <<<"$build_step" && \
   grep -Fq 'successor) ./gradlew :app:assembleRelease -x lintVitalRelease -PappIdentity=successor --stacktrace ;;' <<<"$build_step" && \
   grep -Fq 'release-build/$IDENTITY' <<<"$build_step" && \
   grep -Fqx '        identity: [bridge, successor]' <<<"$package_job" && \
   grep -Fqx '          path: release-build/bridge' <<<"$package_job" && \
   grep -Fqx '          path: release-build/successor' <<<"$package_job" && \
   grep -Fq 'collect_identity bridge ha-paneld-unsigned.apk io.github.maxlyth.hapaneld' <<<"$collect_step" && \
   grep -Fq 'collect_identity successor panel-assistant-unsigned.apk io.panelassistant.android' <<<"$collect_step" && \
   grep -Fq 'Unsigned $identity APK package is' <<<"$collect_step" && \
   grep -Fq 'the identity property did not take effect' <<<"$collect_step"; then
  pass "the package job builds both identities and asserts the application id per artifact"
else
  fail_test "the package job builds both identities and asserts the application id per artifact"
fi

if grep -Fq 'sign_identity release-input/ha-paneld-unsigned.apk "$apk_name" io.github.maxlyth.hapaneld' <<<"$asset_step" && \
   grep -Fq 'sign_identity release-input/panel-assistant-unsigned.apk "$successor_apk_name" io.panelassistant.android' <<<"$asset_step" && \
   [ "$(grep -Fc 'expected_signer_digest=ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339' <<<"$asset_step")" -eq 1 ] && \
   grep -Fq 'authenticate_apk "$signed_apk" "$apk_name"' <<<"$proof_step" && \
   grep -Fq 'authenticate_apk "$successor_signed_apk" "$successor_apk_name"' <<<"$proof_step"; then
  pass "both APKs are signed and authenticated under the one release certificate"
else
  fail_test "both APKs are signed and authenticated under the one release certificate"
fi

if grep -Fq 'bridge_apk_name="ha-paneld-${RELEASE_TAG}-manual-setup-required.apk"' <<<"$ordering_step" && \
   grep -Fq 'successor_apk_name="panel-assistant-${RELEASE_TAG}-manual-setup-required.apk"' <<<"$ordering_step" && \
   grep -Fq 'select(endswith(".apk"))' <<<"$ordering_step" && \
   grep -Fq 'expected the bridge' <<<"$ordering_step" && \
   grep -Fq 'expected exactly 2' <<<"$ordering_step" && \
   grep -Fq 'Verify shipped updaters resolve the bridge APK' <<<"$publish_job"; then
  pass "publication is followed by an assertion that the first .apk asset is the bridge"
else
  fail_test "publication is followed by an assertion that the first .apk asset is the bridge"
fi

# The release jobs restore the basic Gradle cache CI writes on main and never write one. Full lint runs
# on every push to main, never on tags, so the verify gate finds a finished "Android lint" check for the
# exact commit instead of waiting on a run the tag started.
LINT_WORKFLOW="${RELEASE_LINT_WORKFLOW_UNDER_TEST:-$ROOT/.github/workflows/lint.yml}"
if [ "$(grep -Fc 'uses: gradle/actions/setup-gradle@' "$WORKFLOW")" -eq 2 ] && \
   [ "$(grep -Fxc '          cache-provider: basic' "$WORKFLOW")" -eq 2 ] && \
   [ "$(grep -Fxc '          cache-read-only: true' "$WORKFLOW")" -eq 2 ] && \
   ! grep -Fq 'cache-write-only' "$WORKFLOW" && \
   grep -Fqx '    name: Android lint' "$LINT_WORKFLOW" && \
   [ "$(grep -Fxc '    branches: [main]' "$LINT_WORKFLOW")" -eq 2 ] && \
   ! grep -Eq '^[[:space:]]+tags:' "$LINT_WORKFLOW"; then
  pass "release builds only read main's Gradle cache and publication waits on lint from main"
else
  fail_test "release builds only read main's Gradle cache and publication waits on lint from main"
fi

# Each job starts from a clean checkout of the tag, or none at all. A step may read a repository path
# only if Git tracks it (generated files such as the bundled helper assets are ignored and absent), and
# a build output only after a Gradle run in the same job. `seal` has no checkout, so it reads no
# repository path. A job that reads a file another job generated fails on every clean release.
if python3 - "$WORKFLOW" "$ROOT" <<'PY'
import re
import subprocess
import sys
from pathlib import Path

lines = Path(sys.argv[1]).read_text().splitlines()
tracked = set(subprocess.run(["git", "-C", sys.argv[2], "ls-files"], check=True, capture_output=True, text=True).stdout.splitlines())
headers = [index for index, line in enumerate(lines) if re.fullmatch(r"  [\w-]+:", line)]
problems = []
for start, end in zip(headers, [*headers[1:], len(lines)]):
    job = lines[start].strip().rstrip(":")
    if job not in {"package", "package-apk", "seal"}:
        continue
    gradle_ran = False
    for line in lines[start:end]:
        if line.lstrip().startswith("#"):
            continue
        for path in re.findall(r"app/[\w./-]+", line):
            path = path.rstrip(".")
            if job == "seal":
                problems.append(f"{job}: reads {path} without a checkout")
            elif path.startswith("app/build/"):
                if not gradle_ran:
                    problems.append(f"{job}: reads {path} before any Gradle run")
            elif path not in tracked and not any(name.startswith(path.rstrip("/") + "/") for name in tracked):
                problems.append(f"{job}: reads untracked {path}")
        if "./gradlew " in line:
            gradle_ran = True
for problem in problems:
    print(problem, file=sys.stderr)
sys.exit(1 if problems else 0)
PY
then
  pass "package, identity and seal jobs read only tracked files or outputs they built themselves"
else
  fail_test "package, identity and seal jobs read only tracked files or outputs they built themselves"
fi

seal_helper_step="$(extract_named_step 'Verify standalone helpers match APK-bundled helpers')"
if grep -Fq 'unzip -p "$apk" assets/hapaneld-helper-arm | cmp release-input/hapaneld-helper-armeabi-v7a -' <<<"$seal_helper_step" && \
   grep -Fq 'unzip -p "$apk" assets/hapaneld-helper-arm64 | cmp release-input/hapaneld-helper-arm64-v8a -' <<<"$seal_helper_step" && \
   grep -Fq 'for apk in release-input/ha-paneld-unsigned.apk release-input/panel-assistant-unsigned.apk; do' <<<"$seal_helper_step" && \
   grep -Fq 'set -euo pipefail' <<<"$seal_helper_step"; then
  pass "standalone helpers are compared with the helper packaged in both APKs"
else
  fail_test "standalone helpers are compared with the helper packaged in both APKs"
fi

# The descriptor names the successor and the installer pins it, while the bridge asset name is
# frozen at what every earlier release published.
if grep -Fq 'apk_name="panel-assistant-${RELEASE_TAG}-manual-setup-required.apk"' <<<"$descriptor_step" && \
   grep -Fq 'descriptor_name="ha-paneld-${RELEASE_TAG}-install.json"' <<<"$descriptor_step" && \
   grep -Fq 'RELEASE_APK_NAME=\"$SUCCESSOR_APK_NAME\"' <<<"$package_job" && \
   grep -Fq 'bridge_apk_name="ha-paneld-${RELEASE_TAG}-manual-setup-required.apk"' <<<"$stage_step" && \
   grep -Fq 'successor_apk_name="panel-assistant-${RELEASE_TAG}-manual-setup-required.apk"' <<<"$stage_step"; then
  pass "the descriptor and installer pin the successor while the bridge asset name is unchanged"
else
  fail_test "the descriptor and installer pin the successor while the bridge asset name is unchanged"
fi

# Shipped 0.4.1 verifiers compare these byte for byte. A migration that moves any of them silently
# breaks every integration and panel already in the field, so they are asserted as literals.
if [ "$(grep -Fc 'io.github.maxlyth.hapaneld.install.v1' "$WORKFLOW")" -ge 1 ] && \
   ! grep -Fq 'io.panelassistant.android.install.v1' "$WORKFLOW" && \
   grep -Fq "'io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY'" <<<"$proof_step" && \
   ! grep -Fq 'io.panelassistant.android.DATABASE_COMPATIBILITY' "$WORKFLOW" && \
   grep -Fq 'hapaneld-db:v1:ha-paneld\.db' <<<"$proof_step" && \
   grep -Fq '.schema == "io.github.maxlyth.hapaneld.install.v1"' <<<"$final_step" && \
   grep -Fq 'verify_final_descriptor "$successor_apk_name" "$descriptor_name" io.panelassistant.android' <<<"$final_step" && \
   grep -Fq '.launchComponent == ($package_id+"/io.github.maxlyth.hapaneld.MainActivity")' <<<"$final_step"; then
  pass "frozen schema and database contracts survive the identity move while the descriptor id moves"
else
  fail_test "frozen schema and database contracts survive the identity move while the descriptor id moves"
fi

# Behavioral: the final step refuses a pair that is not one bridge and one successor. This is the
# guard against a dropped `-PappIdentity` property reaching publication.
if ! (
  cd "$descriptor_case" || exit 1
  ANDROID_HOME="$descriptor_case/android" \
    MOCK_BADGING_IDENTITY=bridge \
    RELEASE_TAG=v1.2.3-rc1 \
    RUNNER_TEMP="$descriptor_case/runner-temp" \
    bash <<<"$test_final_step"
) > "$descriptor_case/same-identity-final.log" 2>&1 && \
   grep -Fq 'do not carry one bridge and one successor application id' "$descriptor_case/same-identity-final.log"; then
  pass "final pre-upload verification rejects two APKs carrying the same application id"
else
  sed -n '1,80p' "$descriptor_case/same-identity-final.log" >&2
  fail_test "final pre-upload verification rejects two APKs carrying the same application id"
fi

# Behavioral: the post-publication ordering guard, driven against a mocked release listing. The
# accepted case, the reversed case that would strand every shipped updater, and a release that
# published only one APK.
ordering_case="$TMP/publish-ordering"
mkdir -p "$ordering_case/mock-bin"
cat > "$ordering_case/mock-bin/gh" <<'MOCK_GH'
#!/usr/bin/env bash
set -eu
printf '%s\n' $MOCK_RELEASE_APKS
MOCK_GH
chmod 0755 "$ordering_case/mock-bin/gh"

run_ordering_case() {
  (
    cd "$ordering_case" || exit 1
    PATH="$ordering_case/mock-bin:$PATH" \
      MOCK_RELEASE_APKS="$1" \
      RELEASE_REPOSITORY=panel-assistant/android \
      RELEASE_TAG=v1.2.3-rc1 \
      bash <<<"$ordering_step"
  ) > "$ordering_case/ordering.log" 2>&1
}

bridge_first='ha-paneld-v1.2.3-rc1-manual-setup-required.apk panel-assistant-v1.2.3-rc1-manual-setup-required.apk'
successor_first='panel-assistant-v1.2.3-rc1-manual-setup-required.apk ha-paneld-v1.2.3-rc1-manual-setup-required.apk'

if run_ordering_case "$bridge_first"; then
  pass "publication ordering guard accepts the bridge as the first .apk asset"
else
  sed -n '1,40p' "$ordering_case/ordering.log" >&2
  fail_test "publication ordering guard accepts the bridge as the first .apk asset"
fi

if ! run_ordering_case "$successor_first" && \
   grep -Fq 'expected the bridge' "$ordering_case/ordering.log"; then
  pass "publication ordering guard rejects a release whose successor .apk sorts first"
else
  sed -n '1,40p' "$ordering_case/ordering.log" >&2
  fail_test "publication ordering guard rejects a release whose successor .apk sorts first"
fi

if ! run_ordering_case 'ha-paneld-v1.2.3-rc1-manual-setup-required.apk' && \
   grep -Fq 'expected exactly 2' "$ordering_case/ordering.log"; then
  pass "publication ordering guard rejects a release that published only one APK"
else
  sed -n '1,40p' "$ordering_case/ordering.log" >&2
  fail_test "publication ordering guard rejects a release that published only one APK"
fi

# --- the pinned installer must agree with the workflow that pinned it --------------------------
#
# The release workflow rewrites `RELEASE_APK_NAME=` in the published installer, and the installer
# then asserts at startup that the value it was given is the one its own `release_apk_name` would
# produce for that tag. The two live in different files and are edited by different lanes, so a
# disagreement is invisible until a user runs the published one-liner and it refuses itself. This
# calls the installer's real function and compares it against the name the workflow pins.

installer_apk_name="$(
  { sed -n '/^release_apk_name() {/p' "$ROOT/scripts/install.sh"; printf 'release_apk_name v1.2.3-rc1\n'; } | bash
)"

if grep -Fq 'RELEASE_APK_NAME=\"$SUCCESSOR_APK_NAME\"' <<<"$package_job"; then
  workflow_pinned_apk_name=panel-assistant-v1.2.3-rc1-manual-setup-required.apk
else
  workflow_pinned_apk_name=ha-paneld-v1.2.3-rc1-manual-setup-required.apk
fi

if [ -n "$installer_apk_name" ] && [ "$installer_apk_name" = "$workflow_pinned_apk_name" ]; then
  pass "the published installer resolves the same APK asset the release workflow pins into it"
else
  printf 'installer resolves: %s\nworkflow pins:      %s\n' \
    "${installer_apk_name:-<none>}" "$workflow_pinned_apk_name" >&2
  fail_test "the published installer resolves the same APK asset the release workflow pins into it"
fi

# The installer downloads what it was pinned to, so its repository must be the one the workflow
# writes the download URL for. One definition per script, both pointing at the same place.
installer_repo="$(sed -n 's/^REPO="\([^"]*\)"$/\1/p' "$ROOT/scripts/install.sh" | head -1)"
workflow_repo="$(sed -n 's/^  RELEASE_REPOSITORY: \(.*\)$/\1/p' "$WORKFLOW" | head -1)"
if [ -n "$installer_repo" ] && [ "$installer_repo" = "$workflow_repo" ]; then
  pass "the installer and the release workflow name the same repository"
else
  printf 'installer REPO: %s\nworkflow REPO:  %s\n' \
    "${installer_repo:-<none>}" "${workflow_repo:-<none>}" >&2
  fail_test "the installer and the release workflow name the same repository"
fi

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
