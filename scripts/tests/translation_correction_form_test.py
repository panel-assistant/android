"""Contract tests binding the translation-correction form to catalogue policy.

This deliberately is not a general YAML parser.  The checked-in issue form has a small,
stable indentation contract: body items start at two spaces, their IDs at four spaces,
and option entries at eight spaces.  Extracting only those sections keeps the test in the
standard library while producing a direct failure if the form's structure drifts.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
FORM = ROOT / ".github" / "ISSUE_TEMPLATE" / "translation_correction.yml"
CATALOGUE_SCRIPT = ROOT / "scripts" / "i18n_catalogue.py"

SPEC = importlib.util.spec_from_file_location("i18n_catalogue", CATALOGUE_SCRIPT)
assert SPEC and SPEC.loader
i18n = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(i18n)


def body_sections() -> dict[str, list[str]]:
    """Return form body sections keyed by their four-space-indented ``id``."""
    sections: dict[str, list[str]] = {}
    current: list[str] | None = None
    for line in FORM.read_text(encoding="utf-8").splitlines():
        if line.startswith("  - type: "):
            if current is not None:
                _store_section(sections, current)
            current = [line]
        elif current is not None:
            current.append(line)
    if current is not None:
        _store_section(sections, current)
    if not sections:
        raise AssertionError(f"{FORM}: no body sections found at the expected indentation")
    return sections


def _store_section(sections: dict[str, list[str]], lines: list[str]) -> None:
    ids = [line.removeprefix("    id: ").strip() for line in lines if line.startswith("    id: ")]
    if not ids:  # Markdown instructions intentionally have no ID.
        return
    if len(ids) != 1:
        raise AssertionError(f"{FORM}: body section must have exactly one id, found {ids!r}")
    if ids[0] in sections:
        raise AssertionError(f"{FORM}: duplicate body id {ids[0]!r}")
    sections[ids[0]] = lines


def option_lines(section: list[str]) -> list[str]:
    """Extract eight-space option entries from one known form section."""
    try:
        start = section.index("      options:") + 1
    except ValueError as error:
        raise AssertionError("form section has no attributes/options block") from error
    options = []
    for line in section[start:]:
        if line.startswith("        - "):
            options.append(line.removeprefix("        - "))
        elif line and not line.startswith("        "):
            break
    if not options:
        raise AssertionError("form section has no options at the expected indentation")
    return options


class TranslationCorrectionFormTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sections = body_sections()

    def test_language_options_match_catalogue_locales(self) -> None:
        locale_pattern = re.compile(r".+ \(([^()]+)\)\Z")
        locales = []
        for option in option_lines(self.sections["language"]):
            match = locale_pattern.fullmatch(option)
            self.assertIsNotNone(
                match,
                f"language option must end with its catalogue locale in parentheses: {option!r}",
            )
            locales.append(match.group(1))

        self.assertEqual(len(locales), len(set(locales)), "language options contain duplicate locales")
        self.assertSetEqual(
            set(locales),
            i18n.LOCALES,
            "translation-correction locale options must exactly match i18n_catalogue.LOCALES",
        )

    def test_correction_fields_remain_required(self) -> None:
        required_fields = {
            "language",
            "current_wording",
            "proposed_wording",
            "location",
            "version",
            "rationale",
        }
        self.assertTrue(
            required_fields <= self.sections.keys(),
            f"missing required correction fields: {sorted(required_fields - self.sections.keys())}",
        )
        for field_id in sorted(required_fields):
            section = self.sections[field_id]
            self.assertIn(
                "    validations:",
                section,
                f"{field_id}: expected a validations block at four-space indentation",
            )
            self.assertIn(
                "      required: true",
                section,
                f"{field_id}: correction field must remain required",
            )

    def test_all_three_confirmations_remain_required(self) -> None:
        expected = {
            "I know the selected language and variant well enough to propose this correction.",
            "I authored the proposed wording and have the right to submit it.",
            "I agree that this contribution may be distributed under the project's Apache License 2.0.",
        }
        section = self.sections["confirmations"]
        labels = {
            option.removeprefix("label: ")
            for option in option_lines(section)
            if option.startswith("label: ")
        }
        self.assertSetEqual(labels, expected, "the correction form's confirmation wording drifted")

        for index, line in enumerate(section):
            if line.startswith("        - label: "):
                following = section[index + 1] if index + 1 < len(section) else ""
                self.assertEqual(
                    following,
                    "          required: true",
                    f"confirmation must remain required: {line.removeprefix('        - label: ')}",
                )


if __name__ == "__main__":
    unittest.main()
