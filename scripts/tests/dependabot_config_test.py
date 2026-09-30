"""Contract tests for `.github/dependabot.yml`.

Dependabot edits `gradle/libs.versions.toml` and nothing else, so it cannot complete a
Gradle version bump in this repository: `gradle/verification-metadata.xml`,
`app/gradle.lockfile` and `settings-gradle.lockfile` all have to be regenerated, and
regenerating verification metadata bootstraps trust in whatever signing keys the new
artifacts carry. The configuration therefore switches Gradle *version* proposals off and
keeps *security* proposals on, while leaving `gradle-wrapper` alone because Dependabot
rewrites the wrapper completely enough to finish in place.

Both halves of that are expressed as glob patterns, which is the fragile part, so they are
re-derived here from the real dependency names rather than restated:

* every coordinate declared in the version catalog and the build scripts must be matched by
  at least one ignore pattern, or a bump for it would open the unmergeable pull request this
  configuration exists to prevent;
* `gradle-wrapper` must be matched by none of them, or the one Gradle update path that does
  work would be silently switched off;
* no Gradle ignore rule may carry `versions:`, because dependabot-core honours that key for
  security updates as well and it would silence a vulnerable-dependency proposal.

The matcher is dependabot-core's, reimplemented from `common/lib/wildcard_matcher.rb`: `*`
becomes `.*`, every other character is quoted, and the result is anchored and matched
case-insensitively.
"""

import re
import tomllib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".github" / "dependabot.yml"
CATALOG = ROOT / "gradle" / "libs.versions.toml"
BUILD_SCRIPTS = (
    ROOT / "build.gradle.kts",
    ROOT / "app" / "build.gradle.kts",
    ROOT / "settings.gradle.kts",
)

# dependabot-core's literal name for the Gradle distribution, from
# gradle/lib/dependabot/gradle/file_parser/distributions_finder.rb.
WRAPPER_DEPENDENCY_NAME = "gradle-wrapper"

SEMVER_UPDATE_TYPES = frozenset(
    {
        "version-update:semver-major",
        "version-update:semver-minor",
        "version-update:semver-patch",
    }
)

# "group:artifact" or "group:artifact:version" as written in a build script.
COORDINATE = re.compile(r'"([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)(?::[^"]*)?"')


def wildcard_match(pattern, candidate):
    """Port of dependabot-core `WildcardMatcher.match?`."""
    regex = ".*".join(re.escape(part) for part in pattern.lower().split("*"))
    return re.fullmatch(regex, candidate.lower()) is not None


def catalog_dependency_names():
    """Every Gradle dependency name Dependabot could propose a version for."""
    catalog = tomllib.loads(CATALOG.read_text(encoding="utf-8"))
    names = set()
    for library in catalog.get("libraries", {}).values():
        if not isinstance(library, dict):
            continue
        if "module" in library:
            names.add(library["module"])
        elif "group" in library and "name" in library:
            names.add(f"{library['group']}:{library['name']}")
    for plugin in catalog.get("plugins", {}).values():
        if isinstance(plugin, dict) and "id" in plugin:
            names.add(plugin["id"])
    for script in BUILD_SCRIPTS:
        if not script.exists():
            continue
        for match in COORDINATE.finditer(script.read_text(encoding="utf-8")):
            names.add(match.group(1))
    return names


def ecosystem_blocks():
    """Split the config into `{(ecosystem, directory): [lines]}` without a YAML parser.

    The file is repository-owned and uniformly indented, so this reads the block structure
    directly. Comments are stripped so a commented-out key can never satisfy an assertion.
    """
    blocks = {}
    key = None
    for raw in CONFIG.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].rstrip() if not raw.lstrip().startswith("#") else ""
        if raw.startswith("  - package-ecosystem:"):
            key = [raw.split(":", 1)[1].strip(), None]
            blocks[tuple(key)] = []
            continue
        if key is None:
            continue
        if raw.startswith("    directory:") and key[1] is None:
            previous = blocks.pop(tuple(key))
            key[1] = raw.split(":", 1)[1].strip()
            blocks[tuple(key)] = previous
        if line:
            blocks[tuple(key)].append(line)
    return blocks


def ignore_rules(block_lines):
    """Return the `ignore:` entries of one ecosystem block as dictionaries."""
    rules = []
    inside = False
    for line in block_lines:
        stripped = line.strip()
        if line == "    ignore:":
            inside = True
            continue
        if inside and line.startswith("    ") and not line.startswith("     "):
            inside = False
        if not inside:
            continue
        if stripped.startswith("- dependency-name:"):
            rules.append({"dependency-name": stripped.split(":", 1)[1].strip().strip('"')})
        elif stripped in ("update-types:", "versions:"):
            rules[-1][stripped.rstrip(":")] = []
        elif stripped.startswith("- ") and rules:
            last = list(rules[-1])[-1]
            if isinstance(rules[-1][last], list):
                rules[-1][last].append(stripped[2:].strip().strip('"'))
    return rules


class WildcardMatcherTest(unittest.TestCase):
    """The reimplemented matcher, pinned against dependabot-core's own behaviour."""

    def test_star_spans_any_run_and_other_characters_are_literal(self):
        self.assertTrue(wildcard_match("*:*", "androidx.webkit:webkit"))
        self.assertTrue(wildcard_match("*:*", "junit:junit"))
        self.assertTrue(wildcard_match("*.*", "com.android.application"))
        self.assertFalse(wildcard_match("*:*", "com.android.application"))
        self.assertFalse(wildcard_match("*.*", "junit:junit"))

    def test_matching_is_anchored_and_case_insensitive(self):
        self.assertTrue(wildcard_match("io.ktor:*", "IO.Ktor:ktor-server-cio"))
        self.assertFalse(wildcard_match("io.ktor:*", "prefix-io.ktor:ktor-server-cio"))
        self.assertFalse(wildcard_match("gradle", WRAPPER_DEPENDENCY_NAME))


class GradleIgnoreCoverageTest(unittest.TestCase):
    def setUp(self):
        blocks = ecosystem_blocks()
        gradle = [lines for (eco, _), lines in blocks.items() if eco == "gradle"]
        self.assertEqual(len(gradle), 1, "expected exactly one Gradle ecosystem block")
        self.rules = ignore_rules(gradle[0])
        self.patterns = [rule["dependency-name"] for rule in self.rules]

    def test_every_declared_dependency_is_covered_by_an_ignore_pattern(self):
        names = catalog_dependency_names()
        self.assertTrue(names, "no dependency names were discovered to check")
        uncovered = sorted(
            name
            for name in names
            if not any(wildcard_match(pattern, name) for pattern in self.patterns)
        )
        self.assertEqual(
            uncovered,
            [],
            "these dependencies would still open an unmergeable Gradle version pull request; "
            "extend the ignore patterns or record why the exception is wanted",
        )

    def test_the_gradle_wrapper_is_left_proposing_updates(self):
        matched = [p for p in self.patterns if wildcard_match(p, WRAPPER_DEPENDENCY_NAME)]
        self.assertEqual(
            matched,
            [],
            "gradle-wrapper is the one Gradle update Dependabot can finish in place; "
            "an ignore pattern that matches it switches that off silently",
        )

    def test_no_gradle_ignore_rule_can_reach_security_updates(self):
        for rule in self.rules:
            with self.subTest(dependency_name=rule["dependency-name"]):
                self.assertNotIn(
                    "versions",
                    rule,
                    "dependabot-core honours `versions` for security updates, so this rule "
                    "would also suppress a vulnerable-dependency proposal",
                )
                self.assertEqual(
                    set(rule.get("update-types", [])),
                    set(SEMVER_UPDATE_TYPES),
                    "an ignore rule that does not name all three semver update types leaves "
                    "part of the version-update surface open",
                )


class EcosystemInventoryTest(unittest.TestCase):
    def test_each_ecosystem_and_directory_pair_is_unique(self):
        keys = []
        for raw in CONFIG.read_text(encoding="utf-8").splitlines():
            if raw.startswith("  - package-ecosystem:"):
                keys.append([raw.split(":", 1)[1].strip(), None])
            elif raw.startswith("    directory:") and keys and keys[-1][1] is None:
                keys[-1][1] = raw.split(":", 1)[1].strip()
        pairs = [tuple(key) for key in keys]
        self.assertEqual(
            sorted(pairs),
            sorted(set(pairs)),
            "Dependabot rejects the whole file when two entries share an ecosystem and directory",
        )

    def test_the_ecosystems_dependabot_can_still_complete_are_retained(self):
        pairs = set(ecosystem_blocks())
        for expected in (
            ("github-actions", "/"),
            ("npm", "/test"),
            ("npm", "/tools/profile-editor"),
            ("docker", "/.devcontainer"),
        ):
            self.assertIn(
                expected,
                pairs,
                "these ecosystems have no lock state Dependabot cannot regenerate and their "
                "pull requests merge as they are",
            )


if __name__ == "__main__":
    unittest.main()
