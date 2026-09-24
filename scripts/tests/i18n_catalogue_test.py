import ast
import importlib.util
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).parents[1] / "i18n_catalogue.py"
SPEC = importlib.util.spec_from_file_location("i18n_catalogue", SCRIPT)
assert SPEC and SPEC.loader
i18n = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(i18n)


class CatalogueTest(unittest.TestCase):
    def source(self):
        text = "Keep {name} on MQTT."
        return {
            "schema": 1,
            "locale": "en",
            "sourceRevision": "e" * 40,
            "strings": {
                "settings.example.help": {
                    "text": text,
                    "sourceHash": i18n.source_hash(text),
                    "surface": "settings",
                    "context": "Configure example help",
                    "risk": "ordinary",
                    "siblings": [],
                    "placeholders": ["{name}"],
                    "frozen": ["MQTT"],
                    "softMaxChars": 40,
                    "hardMaxChars": 80,
                }
            },
        }

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False) + "\n", encoding="utf-8")

    def committed_catalogues(self, root, source, locale, target):
        worktree = root / "repo"
        catalogue_dir = worktree / "app/src/main/assets/i18n"
        source_path, target_path = catalogue_dir / "en.json", catalogue_dir / f"{locale}.json"
        self.write(source_path, source)
        self.write(target_path, target)
        subprocess.run(["git", "init", "-q"], cwd=worktree, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=worktree, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=worktree, check=True)
        subprocess.run(["git", "add", "app/src/main/assets/i18n"], cwd=worktree, check=True)
        subprocess.run(["git", "commit", "-qm", "test fixture"], cwd=worktree, check=True)
        return worktree, source_path, target_path

    def report_source(self):
        source = self.source()
        strings = {}
        for index, suffix in enumerate(("a", "b", "c", "d", "e", "f")):
            text = f"English setting {suffix}."
            strings[f"settings.{suffix}.label"] = {
                "text": text,
                "sourceHash": i18n.source_hash(text),
                "surface": "settings",
                "context": f"Configure setting {suffix}",
                "risk": ("ordinary", "setup", "consequential")[index // 2],
                "siblings": [],
                "placeholders": [],
                "frozen": [],
                "softMaxChars": 40,
                "hardMaxChars": 80,
            }
        source["strings"] = strings
        return source

    def report_context(self):
        return {
            "schema": 1,
            "id": "test-terminology",
            "productContext": "Test product context.",
            "instruction": "Use the pinned term.",
            "license": "Apache-2.0",
            "notice": "Synthetic test fixture.",
            "sources": [{
                "id": "frontend",
                "repository": "https://github.com/home-assistant/frontend",
                "revision": "b" * 40,
                "artifact": "synthetic frontend artifact",
                "artifactSha256": "c" * 64,
                "license": "Apache-2.0",
            }],
            "terms": [{
                "id": "settings",
                "meaning": "Settings surface.",
                "english": "Settings",
                "source": "frontend",
                "sourceKey": "panel.config",
                "translations": {
                    "de": "Einstellungen",
                    "es": "Configuración",
                    "fr": "Paramètres",
                    "it": "Impostazioni",
                    "nl": "Instellingen",
                    "pl": "Ustawienia",
                    "uk": "Налаштування",
                    "zh-Hans": "设置",
                },
            }],
        }

    def test_source_and_target_validate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "de.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "{name} auf MQTT behalten.",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-draft",
                }},
            })
            parsed = i18n.validate_source(source_path)
            i18n.validate_target(target_path, parsed)

    def test_target_script_policy_is_unique_complete_and_mutation_sensitive(self):
        tree = ast.parse(SCRIPT.read_text(encoding="utf-8"))
        assignment = next(
            node for node in tree.body
            if isinstance(node, ast.Assign)
            and any(
                isinstance(target, ast.Name) and target.id == "TARGET_SCRIPT_POLICIES"
                for target in node.targets
            )
        )
        self.assertIsInstance(assignment.value, ast.Dict)
        literal_keys = [ast.literal_eval(key) for key in assignment.value.keys]
        self.assertEqual(
            len(literal_keys), len(set(literal_keys)), "script policy has duplicate locale keys"
        )
        self.assertSetEqual(set(literal_keys), i18n.LOCALES)

        source_record = {"text": "Example", "placeholders": [], "frozen": []}
        selected = sorted(i18n.LOCALES)[0]
        for mutation in (
            {
                key: value
                for key, value in i18n.TARGET_SCRIPT_POLICIES.items()
                if key != selected
            },
            {**i18n.TARGET_SCRIPT_POLICIES, "extra-locale": "latin"},
        ):
            with (
                self.subTest(mutation=mutation),
                mock.patch.object(i18n, "TARGET_SCRIPT_POLICIES", mutation),
                self.assertRaisesRegex(i18n.CatalogueError, "must exactly cover"),
            ):
                i18n.validate_target_language(
                    "settings.example.label", "Beispiel", selected, source_record
                )
        with (
            mock.patch.dict(i18n.TARGET_SCRIPT_POLICIES, {selected: "unsupported"}),
            self.assertRaisesRegex(i18n.CatalogueError, "policy is invalid"),
        ):
            i18n.validate_target_script_policies()

    def test_uk_registration_requires_the_reserved_cyrillic_policy(self):
        # Ukrainian is now a registered release locale, so this exercises the reserved-policy
        # requirement against the real locale set rather than a hypothetical future one: an
        # incorrect or missing script policy for "uk" must still be rejected even though the
        # locale itself is live.
        self.assertIn("uk", i18n.LOCALES)
        self.assertEqual(i18n.REQUIRED_TARGET_SCRIPT_POLICIES.get("uk"), "ukrainian-cyrillic")
        source_record = {"text": "Settings", "placeholders": [], "frozen": []}
        without_uk = {key: value for key, value in i18n.TARGET_SCRIPT_POLICIES.items() if key != "uk"}
        for policies, message in (
            (without_uk, "must exactly cover"),
            ({**i18n.TARGET_SCRIPT_POLICIES, "uk": "latin"}, "violates locale requirement"),
        ):
            with (
                self.subTest(policies=policies),
                mock.patch.object(i18n, "TARGET_SCRIPT_POLICIES", policies),
                self.assertRaisesRegex(i18n.CatalogueError, message),
            ):
                i18n.validate_target_language(
                    "settings.example.label", "Налаштування", "uk", source_record
                )

    def test_ukrainian_policy_accepts_ukrainian_and_exact_protected_technical_tokens(self):
        future_locales = {*i18n.LOCALES, "uk"}
        future_policies = {**i18n.TARGET_SCRIPT_POLICIES, "uk": "ukrainian-cyrillic"}
        cases = (
            (
                "settings.example.label",
                "Settings",
                [],
                "Налаштування",
            ),
            (
                "settings.example.help",
                "Keep MQTT connected.",
                ["MQTT"],
                "Зберігати повʼязане з’єднання MQTT.",
            ),
            (
                "settings.region.label",
                "Russia",
                [],
                "Слава Украине 🇺🇦",
            ),
        )
        with (
            mock.patch.object(i18n, "LOCALES", future_locales),
            mock.patch.object(i18n, "TARGET_SCRIPT_POLICIES", future_policies),
        ):
            for key, source_text, frozen, target_text in cases:
                source_record = {
                    "text": source_text,
                    "placeholders": [],
                    "frozen": frozen,
                }
                with self.subTest(key=key):
                    i18n.validate_target_language(key, target_text, "uk", source_record)

    def test_ukrainian_policy_rejects_latin_russian_only_and_other_cyrillic_letters(self):
        future_locales = {*i18n.LOCALES, "uk"}
        future_policies = {**i18n.TARGET_SCRIPT_POLICIES, "uk": "ukrainian-cyrillic"}
        source_record = {"text": "System settings", "placeholders": [], "frozen": []}
        invalid_targets = (
            "123",
            "System settings",
            "Системні settings",
            "Системы",
            "Налады ўжо",
            "Росія",
        )
        with (
            mock.patch.object(i18n, "LOCALES", future_locales),
            mock.patch.object(i18n, "TARGET_SCRIPT_POLICIES", future_policies),
        ):
            for target_text in invalid_targets:
                with (
                    self.subTest(target_text=target_text),
                    self.assertRaises(i18n.CatalogueError),
                ):
                    i18n.validate_target_language(
                        "settings.example.label", target_text, "uk", source_record
                    )

    def test_ukrainian_policy_checks_additions_to_token_only_sources(self):
        future_locales = {*i18n.LOCALES, "uk"}
        future_policies = {**i18n.TARGET_SCRIPT_POLICIES, "uk": "ukrainian-cyrillic"}
        frozen_source = {"text": "MQTT", "placeholders": [], "frozen": ["MQTT"]}
        placeholder_source = {
            "text": "{detail} · {health}",
            "placeholders": ["{detail}", "{health}"],
            "frozen": [],
        }
        with (
            mock.patch.object(i18n, "LOCALES", future_locales),
            mock.patch.object(i18n, "TARGET_SCRIPT_POLICIES", future_policies),
        ):
            i18n.validate_target_language(
                "settings.protocol.label", "MQTT", "uk", frozen_source
            )
            i18n.validate_target_language(
                "settings.status.label",
                "{detail} · {health}",
                "uk",
                placeholder_source,
            )
            for source_record, target_text in (
                (frozen_source, "Русский MQTT"),
                (placeholder_source, "System {detail} · {health}"),
            ):
                with (
                    self.subTest(target_text=target_text),
                    self.assertRaises(i18n.CatalogueError),
                ):
                    i18n.validate_target_language(
                        "settings.example.label", target_text, "uk", source_record
                    )

    def test_ukrainian_english_fallback_preserves_machine_diagnostics(self):
        future_locales = {*i18n.LOCALES, "uk"}
        future_policies = {**i18n.TARGET_SCRIPT_POLICIES, "uk": "ukrainian-cyrillic"}
        source = self.source()
        key = "settings.example.help"
        diagnostic = "Raw diagnostic ru_RU from MQTT."
        source["strings"][key].update({
            "text": diagnostic,
            "sourceHash": i18n.source_hash(diagnostic),
            "placeholders": [],
            "frozen": ["MQTT"],
            "hardMaxChars": 80,
        })
        with tempfile.TemporaryDirectory() as directory:
            target_path = Path(directory) / "uk.json"
            self.write(target_path, {
                "schema": 1,
                "locale": "uk",
                "sourceRevision": "e" * 40,
                "strings": {key: {
                    "text": diagnostic,
                    "sourceHash": source["strings"][key]["sourceHash"],
                    "state": "english-fallback",
                }},
            })
            with (
                mock.patch.object(i18n, "LOCALES", future_locales),
                mock.patch.object(i18n, "TARGET_SCRIPT_POLICIES", future_policies),
            ):
                target = i18n.validate_target(target_path, source, expected_locale="uk")
            self.assertEqual(diagnostic, target["strings"][key]["text"])

    def test_terminology_translations_exactly_cover_supported_locales(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "context.json"
            context = self.report_context()
            self.write(path, context)
            self.assertEqual(1, i18n.context_report(path)["terms"])

            selected = sorted(i18n.LOCALES)[0]
            missing = json.loads(json.dumps(context))
            missing["terms"][0]["translations"].pop(selected)
            extra = json.loads(json.dumps(context))
            extra["terms"][0]["translations"]["extra-locale"] = "Extra"
            for mutation in (missing, extra):
                self.write(path, mutation)
                with (
                    self.subTest(locales=mutation["terms"][0]["translations"]),
                    self.assertRaisesRegex(
                        i18n.CatalogueError, "malformed terminology context term value"
                    ),
                ):
                    i18n.context_report(path)

    def test_zigbee_join_confirmation_allows_only_three_paragraphs(self):
        key = "configure.zigbee.join_confirm"
        i18n.validate_target_text_hygiene(key, "Erster Absatz.\n\nZweiter Absatz.\n\nDritter Absatz.")

        invalid = {
            "wrong key": ("configure.zigbee.other", "Eins.\n\nZwei.\n\nDrei."),
            "single line break": (key, "Eins.\nZwei.\n\nDrei."),
            "only two paragraphs": (key, "Eins.\n\nZwei."),
            "four paragraphs": (key, "Eins.\n\nZwei.\n\nDrei.\n\nVier."),
            "empty paragraph": (key, "Eins.\n\n\n\nDrei."),
            "leading line break": (key, "\n\nEins.\n\nZwei."),
            "trailing line break": (key, "Eins.\n\nZwei.\n\n"),
            "carriage return": (key, "Eins.\r\n\r\nZwei.\r\n\r\nDrei."),
            "other control": (key, "Eins.\n\nZwei.\x00\n\nDrei."),
        }
        for name, (candidate_key, text) in invalid.items():
            with self.subTest(name=name), self.assertRaises(i18n.CatalogueError):
                i18n.validate_target_text_hygiene(candidate_key, text)

    def test_profile_delete_detail_requires_one_line_then_one_paragraph_break(self):
        key = "profiles.modal.delete_detail"
        i18n.validate_target_text_hygiene(
            key,
            "{profile}\nsha256:{sha256}\n\nThis cannot be undone.",
        )

        invalid = {
            "wrong key": ("profiles.modal.other", "Profile\nsha256:value\n\nWarning."),
            "only paragraph break": (key, "Profile\n\nsha256:value\n\nWarning."),
            "only line breaks": (key, "Profile\nsha256:value\nWarning."),
            "reversed runs": (key, "Profile\n\nsha256:value\nWarning."),
            "extra line": (key, "Profile\nsha256:value\nextra\n\nWarning."),
            "leading line break": (key, "\nProfile\nsha256:value\n\nWarning."),
            "trailing line break": (key, "Profile\nsha256:value\n\nWarning.\n"),
        }
        for name, (candidate_key, text) in invalid.items():
            with self.subTest(name=name), self.assertRaises(i18n.CatalogueError):
                i18n.validate_target_text_hygiene(candidate_key, text)

    def test_web_surface_is_admitted_but_unknown_surface_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "en.json"
            source = self.source()
            source["strings"]["settings.example.help"]["surface"] = "shell"
            self.write(source_path, source)
            self.assertEqual("shell", i18n.validate_source(source_path)["strings"]["settings.example.help"]["surface"])

            source["strings"]["settings.example.help"]["surface"] = "typo"
            self.write(source_path, source)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_source(source_path)

    def test_duplicate_key_and_changed_placeholder_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            duplicate = root / "duplicate.json"
            duplicate.write_text('{"schema":1,"schema":1}', encoding="utf-8")
            with self.assertRaises(i18n.CatalogueError):
                i18n.read_json(duplicate)

            source_path, target_path = root / "en.json", root / "de.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "Ohne Namen auf MQTT behalten.",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-cross-checked",
                }},
            })
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

            target = json.loads(target_path.read_text(encoding="utf-8"))
            record = target["strings"]["settings.example.help"]
            record["text"] = "{name} auf MQTT MQTT behalten."
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

            record["text"] = "{name} auf MQTT behalten."
            record["state"] = "english-fallback"
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

    def test_candidate_requires_exact_order_and_becomes_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, candidate_path, output = root / "en.json", root / "candidate.json", root / "de.json"
            self.write(source_path, self.source())
            source_hash = i18n.hashlib.sha256(source_path.read_bytes()).hexdigest()
            self.write(candidate_path, {
                "schema": 1,
                "targetLocale": "de",
                "sourceRevision": "e" * 40,
                "sourceCatalogueHash": source_hash,
                "translations": [{"key": "settings.example.help", "translation": "{name} auf MQTT behalten."}],
            })
            i18n.candidate_to_target(source_path, candidate_path, output)
            target = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("machine-draft", target["strings"]["settings.example.help"]["state"])

            candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
            candidate["sourceCatalogueHash"] = "0" * 64
            self.write(candidate_path, candidate)
            with self.assertRaises(i18n.CatalogueError):
                i18n.candidate_to_target(source_path, candidate_path, output)

    def test_target_filename_must_match_declared_locale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "fr.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {},
            })
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path), expected_locale=target_path.stem)

    def test_stale_target_is_valid_and_partial_merge_preserves_unselected_records(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, base_path = root / "en.json", root / "de.json"
            candidate_path, output = root / "candidate.json", root / "merged" / "de.json"
            source = self.source()
            second_text = "Enable panel mode."
            source["strings"]["settings.second.help"] = {
                "text": second_text,
                "sourceHash": i18n.source_hash(second_text),
                "surface": "settings",
                "context": "Configure second help",
                "risk": "ordinary",
                "siblings": [],
                "placeholders": [],
                "frozen": [],
                "softMaxChars": 40,
                "hardMaxChars": 80,
            }
            source["strings"] = dict(sorted(source["strings"].items()))
            self.write(source_path, source)
            self.write(base_path, {
                "schema": 1, "locale": "de", "sourceRevision": "a" * 40,
                "strings": {
                    "settings.example.help": {
                        "text": "Alte Übersetzung.", "sourceHash": "0" * 64,
                        "state": "community-corrected",
                    },
                    "settings.second.help": {
                        "text": "Panelmodus aktivieren.",
                        "sourceHash": source["strings"]["settings.second.help"]["sourceHash"],
                        "state": "machine-cross-checked",
                    },
                    "settings.removed.help": {
                        "text": "Entfernter Text.", "sourceHash": "9" * 64,
                        "state": "machine-cross-checked",
                    },
                },
            })
            i18n.validate_target(base_path, i18n.validate_source(source_path), expected_locale="de")
            self.write(candidate_path, {
                "schema": 1, "targetLocale": "de", "sourceRevision": "e" * 40,
                "sourceCatalogueHash": i18n.hashlib.sha256(source_path.read_bytes()).hexdigest(),
                "translations": [{
                    "key": "settings.example.help", "translation": "{name} auf MQTT behalten.",
                }],
            })
            base_before = json.loads(base_path.read_text(encoding="utf-8"))
            i18n.merge_candidate(source_path, base_path, candidate_path, output)
            merged = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("machine-draft", merged["strings"]["settings.example.help"]["state"])
            self.assertEqual(
                base_before["strings"]["settings.second.help"],
                merged["strings"]["settings.second.help"],
            )
            self.assertNotIn("settings.removed.help", merged["strings"])

            protected = json.loads(base_path.read_text(encoding="utf-8"))
            protected["strings"]["settings.example.help"]["sourceHash"] = source["strings"]["settings.example.help"]["sourceHash"]
            protected["strings"]["settings.example.help"]["text"] = "{name} auf MQTT behalten."
            self.write(base_path, protected)
            with self.assertRaises(i18n.CatalogueError):
                i18n.merge_candidate(source_path, base_path, candidate_path, output)

    def test_apply_community_correction_changes_exactly_one_current_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            replacement_path, output = root / "replacement.txt", root / "out" / "de.json"
            source = self.source()
            second_text = "Enable panel mode."
            source["strings"]["settings.second.help"] = {
                "text": second_text,
                "sourceHash": i18n.source_hash(second_text),
                "surface": "settings",
                "context": "Configure second help",
                "risk": "ordinary",
                "siblings": [],
                "placeholders": [],
                "frozen": [],
                "softMaxChars": 40,
                "hardMaxChars": 80,
            }
            source["strings"] = dict(sorted(source["strings"].items()))
            current_text = "{name} auf MQTT behalten."
            base = {
                "schema": 1,
                "locale": "de",
                "sourceRevision": "a" * 40,
                "strings": {
                    "settings.example.help": {
                        "text": current_text,
                        "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                        "state": "machine-cross-checked",
                    },
                    "settings.second.help": {
                        "text": "Panelmodus aktivieren.",
                        "sourceHash": source["strings"]["settings.second.help"]["sourceHash"],
                        "state": "machine-draft",
                    },
                },
            }
            worktree, source_path, base_path = self.committed_catalogues(root, source, "de", base)
            replacement_path.write_text("{name} in MQTT beibehalten.\n", encoding="utf-8")

            i18n.apply_community_correction(
                worktree,
                source_path,
                base_path,
                "de",
                "settings.example.help",
                source["strings"]["settings.example.help"]["sourceHash"],
                i18n.source_hash(current_text),
                replacement_path,
                output,
            )

            result = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(source["sourceRevision"], result["sourceRevision"])
            self.assertEqual(
                {
                    "text": "{name} in MQTT beibehalten.",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "community-corrected",
                },
                result["strings"]["settings.example.help"],
            )
            self.assertEqual(base["strings"]["settings.second.help"], result["strings"]["settings.second.help"])
            self.assertEqual(base, json.loads(base_path.read_text(encoding="utf-8")))
            i18n.validate_target(output, i18n.validate_source(source_path), expected_locale="de")
            report = i18n.catalogue_report(source_path, [output])
            self.assertEqual(1, report["locales"]["de"]["stateCounts"]["community-corrected"])

            replacement_path.write_bytes(b"{name} in MQTT beibehalten.\r\n")
            crlf_output = root / "out" / "de-crlf.json"
            i18n.apply_community_correction(
                worktree,
                source_path,
                base_path,
                "de",
                "settings.example.help",
                source["strings"]["settings.example.help"]["sourceHash"],
                i18n.source_hash(current_text),
                replacement_path,
                crlf_output,
            )
            self.assertEqual(
                "{name} in MQTT beibehalten.",
                json.loads(crlf_output.read_text(encoding="utf-8"))["strings"]["settings.example.help"]["text"],
            )

    def test_apply_community_correction_fails_closed_for_stale_or_invalid_input(self):
        invalid_replacements = {
            "placeholder": "Ohne Namen auf MQTT behalten.",
            "frozen": "{name} ohne Broker behalten.",
            "hard budget": "{name} auf MQTT behalten. " + "x" * 80,
            "script": "{name} auf MQTT behalten. Ελληνικά",
            "NUL control": "{name} auf MQTT behalten.\x00",
            "bidi override": "{name} auf MQTT behalten.\u202e",
            "embedded newline": "{name} auf\nMQTT behalten.",
            "double terminal newline": "{name} auf MQTT behalten.\n\n",
            "bare terminal carriage return": "{name} auf MQTT behalten.\r",
            "empty": "",
        }
        for name, replacement in invalid_replacements.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                replacement_path, output = root / "replacement.txt", root / "out" / "de.json"
                source = self.source()
                current_text = "{name} auf MQTT behalten."
                base = {
                    "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                    "strings": {"settings.example.help": {
                        "text": current_text,
                        "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                        "state": "machine-cross-checked",
                    }},
                }
                worktree, source_path, base_path = self.committed_catalogues(root, source, "de", base)
                replacement_path.write_text(replacement, encoding="utf-8")
                with self.assertRaises(i18n.CatalogueError):
                    i18n.apply_community_correction(
                        worktree, source_path, base_path, "de", "settings.example.help",
                        source["strings"]["settings.example.help"]["sourceHash"],
                        i18n.source_hash(current_text), replacement_path, output,
                    )
                self.assertFalse(output.exists())
                self.assertEqual(base, json.loads(base_path.read_text(encoding="utf-8")))

    def test_apply_community_correction_rejects_drift_protected_records_and_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            replacement_path, output = root / "replacement.txt", root / "corrected.json"
            source = self.source()
            current_text = "{name} auf MQTT behalten."
            base = {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": current_text,
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-cross-checked",
                }},
            }
            worktree, source_path, base_path = self.committed_catalogues(root, source, "de", base)
            replacement_path.write_text("{name} in MQTT beibehalten.", encoding="utf-8")
            valid_args = (
                worktree, source_path, base_path, "de", "settings.example.help",
                source["strings"]["settings.example.help"]["sourceHash"],
                i18n.source_hash(current_text), replacement_path, output,
            )
            mutations = (
                ("stale English", valid_args[:5] + ("0" * 64,) + valid_args[6:]),
                ("changed target", valid_args[:6] + ("0" * 64,) + valid_args[7:]),
                ("unsupported locale", valid_args[:3] + ("nl",) + valid_args[4:]),
                ("unknown key", valid_args[:4] + ("settings.unknown.help",) + valid_args[5:]),
                ("source output alias", valid_args[:-1] + (source_path,)),
                ("target output alias", valid_args[:-1] + (base_path,)),
                ("replacement output alias", valid_args[:-1] + (replacement_path,)),
                (
                    "other locale output",
                    valid_args[:-1] + (worktree / "app/src/main/assets/i18n/fr.json",),
                ),
            )
            for name, args in mutations:
                with self.subTest(name=name), self.assertRaises(i18n.CatalogueError):
                    i18n.apply_community_correction(*args)

            noncanonical_source = root / "en-copy.json"
            self.write(noncanonical_source, source)
            with self.assertRaisesRegex(i18n.CatalogueError, "source must be the canonical catalogue"):
                i18n.apply_community_correction(
                    worktree, noncanonical_source, *valid_args[2:]
                )

            self.assertFalse(output.exists())

    def test_apply_community_correction_rejects_stale_protected_and_uncommitted_catalogues(self):
        cases = ("stale target", "protected", "oversized")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = self.source()
                if case == "oversized":
                    source["strings"]["settings.example.help"]["hardMaxChars"] = 20_000
                current_text = "{name} auf MQTT behalten."
                target = {
                    "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                    "strings": {"settings.example.help": {
                        "text": current_text,
                        "sourceHash": (
                            "0" * 64 if case == "stale target"
                            else source["strings"]["settings.example.help"]["sourceHash"]
                        ),
                        "state": "community-corrected" if case == "protected" else "machine-cross-checked",
                    }},
                }
                worktree, source_path, base_path = self.committed_catalogues(root, source, "de", target)
                replacement_path, output = root / "replacement.txt", root / "corrected.json"
                replacement = (
                    "{name} auf MQTT " + "x" * 16_384
                    if case == "oversized" else "{name} in MQTT beibehalten."
                )
                replacement_path.write_text(replacement, encoding="utf-8")
                expected_error = {
                    "stale target": "target record is stale",
                    "protected": "community correction is protected",
                    "oversized": "unreasonably large",
                }[case]
                with self.assertRaisesRegex(i18n.CatalogueError, expected_error):
                    i18n.apply_community_correction(
                        worktree, source_path, base_path, "de", "settings.example.help",
                        source["strings"]["settings.example.help"]["sourceHash"],
                        i18n.source_hash(current_text), replacement_path, output,
                    )
                self.assertFalse(output.exists())

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.source()
            current_text = "{name} auf MQTT behalten."
            target = {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": current_text,
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-cross-checked",
                }},
            }
            worktree, source_path, base_path = self.committed_catalogues(root, source, "de", target)
            replacement_path, output = root / "replacement.txt", root / "corrected.json"
            replacement_path.write_text("{name} in MQTT beibehalten.", encoding="utf-8")
            target["sourceRevision"] = "a" * 40
            self.write(base_path, target)
            with self.assertRaisesRegex(i18n.CatalogueError, "clean committed worktree"):
                i18n.apply_community_correction(
                    worktree, source_path, base_path, "de", "settings.example.help",
                    source["strings"]["settings.example.help"]["sourceHash"],
                    i18n.source_hash(current_text), replacement_path, output,
                )

    def test_apply_community_correction_preserves_existing_staging_name_and_cli_dispatches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.source()
            current_text = "{name} auf MQTT behalten."
            target = {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": current_text,
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-cross-checked",
                }},
            }
            worktree, source_path, base_path = self.committed_catalogues(root, source, "de", target)
            replacement_path = root / "replacement.txt"
            output = root / "out" / "de.json"
            old_staging = output.parent / ".de.json.validation"
            replacement_path.write_text("{name} in MQTT beibehalten.", encoding="utf-8")
            old_staging.parent.mkdir(parents=True)
            old_staging.write_text("unrelated marker", encoding="utf-8")
            command = [
                sys.executable, str(SCRIPT), "apply-community-correction",
                "--worktree", str(worktree),
                "--source", str(source_path),
                "--base-target", str(base_path),
                "--locale", "de",
                "--key", "settings.example.help",
                "--expected-source-hash", source["strings"]["settings.example.help"]["sourceHash"],
                "--expected-target-hash", i18n.source_hash(current_text),
                "--replacement-file", str(replacement_path),
                "--output", str(output),
            ]
            subprocess.run(command, check=True)
            self.assertEqual("unrelated marker", old_staging.read_text(encoding="utf-8"))
            self.assertEqual(
                "community-corrected",
                json.loads(output.read_text(encoding="utf-8"))["strings"]["settings.example.help"]["state"],
            )
            failed_command = list(command)
            failed_command[failed_command.index("--expected-target-hash") + 1] = "0" * 64
            failed_command[failed_command.index("--output") + 1] = str(root / "failed.json")
            failed = subprocess.run(failed_command, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)

            existing_output = root / "existing.json"
            existing_output.write_text("do not replace", encoding="utf-8")
            existing_command = list(command)
            existing_command[existing_command.index("--output") + 1] = str(existing_output)
            failed = subprocess.run(existing_command, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("correction output already exists", failed.stderr)
            self.assertEqual("do not replace", existing_output.read_text(encoding="utf-8"))

    def test_community_replacement_read_is_bounded_before_decode(self):
        class RecordingBytes(io.BytesIO):
            requested = None

            def read(self, size=-1):
                self.requested = size
                return super().read(size)

        content = RecordingBytes(b"{name} auf MQTT behalten.\n")
        with mock.patch.object(Path, "open", return_value=content):
            self.assertEqual(
                "{name} auf MQTT behalten.",
                i18n.read_community_replacement(Path("unused"), "settings.example.help"),
            )
        self.assertEqual(i18n.MAX_REPLACEMENT_FILE_BYTES + 1, content.requested)

        maximum = RecordingBytes(("\U0001f600" * i18n.MAX_TARGET_TEXT_CHARS + "\r\n").encode("utf-8"))
        with mock.patch.object(Path, "open", return_value=maximum):
            self.assertEqual(
                "\U0001f600" * i18n.MAX_TARGET_TEXT_CHARS,
                i18n.read_community_replacement(Path("unused"), "settings.example.help"),
            )

    def test_community_correction_loses_output_race_without_clobbering_winner(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.source()
            current_text = "{name} auf MQTT behalten."
            target = {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": current_text,
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-cross-checked",
                }},
            }
            worktree, source_path, base_path = self.committed_catalogues(root, source, "de", target)
            replacement_path, output = root / "replacement.txt", root / "corrected.json"
            replacement_path.write_text("{name} in MQTT beibehalten.\n", encoding="utf-8")

            def racing_link(_source, destination):
                Path(destination).write_text("concurrent winner", encoding="utf-8")
                raise FileExistsError(destination)

            with mock.patch.object(i18n.os, "link", side_effect=racing_link):
                with self.assertRaisesRegex(i18n.CatalogueError, "output already exists"):
                    i18n.apply_community_correction(
                        worktree, source_path, base_path, "de", "settings.example.help",
                        source["strings"]["settings.example.help"]["sourceHash"],
                        i18n.source_hash(current_text), replacement_path, output,
                    )
            self.assertEqual("concurrent winner", output.read_text(encoding="utf-8"))

    def test_empty_frozen_literal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "en.json"
            source = self.source()
            source["strings"]["settings.example.help"]["frozen"] = [""]
            self.write(source_path, source)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_source(source_path)

    def test_product_name_and_exact_db_unit_require_frozen_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "en.json"
            for literal, text in (
                ("Home Assistant", "Send audio to Home Assistant."),
                ("dB", "Microphone gain (dB)"),
            ):
                with self.subTest(literal=literal):
                    source = self.source()
                    record = source["strings"]["settings.example.help"]
                    record["text"] = text
                    record["sourceHash"] = i18n.source_hash(text)
                    record["placeholders"] = []
                    record["frozen"] = []
                    record["hardMaxChars"] = max(80, len(text))
                    self.write(source_path, source)
                    with self.assertRaisesRegex(
                        i18n.CatalogueError,
                        f"required frozen literal missing: {literal}",
                    ):
                        i18n.validate_source(source_path)
                    record["frozen"] = [literal]
                    self.write(source_path, source)
                    i18n.validate_source(source_path)

            source = self.source()
            record = source["strings"]["settings.example.help"]
            record["text"] = "Wi-Fi signal in dBm."
            record["sourceHash"] = i18n.source_hash(record["text"])
            record["placeholders"] = []
            record["frozen"] = ["dBm"]
            self.write(source_path, source)
            i18n.validate_source(source_path)

    def test_zh_hans_requires_han_text_and_rejects_residual_source_words(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "zh-Hans.json"
            source = self.source()
            self.write(source_path, source)
            target = {
                "schema": 1, "locale": "zh-Hans", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "在 MQTT 上 keep {name}。",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-draft",
                }},
            }
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))
            target["strings"]["settings.example.help"]["text"] = "在 MQTT 上保留 {name}。"
            self.write(target_path, target)
            i18n.validate_target(target_path, i18n.validate_source(source_path))
            target["strings"]["settings.example.help"]["text"] = "在 MQTT 上自动 This {name}。"
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))
            target["strings"]["settings.example.help"]["text"] = "{name} on MQTT."
            self.write(target_path, target)
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

    def test_latin_locale_rejects_any_unexpected_alphabetic_script(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path, target_path = root / "en.json", root / "de.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40,
                "strings": {"settings.example.help": {
                    "text": "{name} auf MQTT behalten. Ελληνικά",
                    "sourceHash": source["strings"]["settings.example.help"]["sourceHash"],
                    "state": "machine-draft",
                }},
            })
            with self.assertRaises(i18n.CatalogueError):
                i18n.validate_target(target_path, i18n.validate_source(source_path))

    def test_target_language_exceptions_are_exact_and_key_scoped(self):
        latin_locales = ("de", "es", "fr", "it")
        catalogue_dir = SCRIPT.parents[1] / "app/src/main/assets/i18n"
        source = i18n.validate_source(catalogue_dir / "en.json")
        for pair, expected in {
            ("de", "setup.progress.name"): "Name",
            ("de", "setup.progress.server"): "Server",
            ("de", "entities.dynamic.default_dashboard"): "Dashboard",
            ("de", "entities.issue.default_dashboard"): "Dashboard",
            ("es", "configure.proximity.experimental"): "experimental",
            ("es", "shell.runtime.duration_minutes"): "{count} min",
            ("es", "shell.runtime.duration_seconds"): "{count} s",
            ("fr", "entities.dynamic.source"): "Source",
            ("fr", "entities.issue.source"): "Source",
            ("fr", "entities.row.option.auto"): "Auto",
            ("fr", "install.shared.version"): "Version",
            ("fr", "profiles.editor.codemirror.diagnostics"): "Diagnostics",
            ("fr", "shell.runtime.duration_minutes"): "{count} min",
            ("fr", "shell.runtime.duration_seconds"): "{count} s",
            ("it", "entities.dynamic.default_dashboard"): "Dashboard",
            ("it", "entities.issue.default_dashboard"): "Dashboard",
            ("it", "shell.runtime.duration_minutes"): "{count} min",
            ("it", "shell.runtime.duration_seconds"): "{count} s",
        }.items():
            self.assertEqual(expected, i18n.UNCHANGED_TARGET_EXCEPTIONS.get(pair))
        for (locale, key), text in i18n.UNCHANGED_TARGET_EXCEPTIONS.items():
            target = i18n.validate_target(
                catalogue_dir / f"{locale}.json",
                source,
                expected_locale=locale,
            )
            self.assertEqual(text, target["strings"][key]["text"])
            source_record = source["strings"][key]
            protected_positions = {
                index
                for match in i18n.PLACEHOLDER_RE.finditer(text)
                for index in range(*match.span())
            }
            for token in source_record["frozen"]:
                start = 0
                while (offset := text.find(token, start)) >= 0:
                    protected_positions.update(range(offset, offset + len(token)))
                    start = offset + len(token)
            case_mutated = text
            for index, character in enumerate(text):
                if character.isalpha() and index not in protected_positions:
                    replacement = character.lower() if character.isupper() else character.upper()
                    case_mutated = f"{text[:index]}{replacement}{text[index + 1:]}"
                    break
            with self.subTest(locale=locale, key=key, mutation="none"):
                i18n.validate_target_language(key, text, locale, source_record)

            other_locale = next(
                candidate
                for candidate in latin_locales
                if i18n.UNCHANGED_TARGET_EXCEPTIONS.get((candidate, key)) != text
            )
            for mutated_key, mutated_locale, mutated_text in (
                (f"{key}.other", locale, text),
                (key, other_locale, text),
                (key, locale, case_mutated),
            ):
                with (
                    self.subTest(
                        locale=locale,
                        key=key,
                        mutated_key=mutated_key,
                        mutated_locale=mutated_locale,
                        mutated_text=mutated_text,
                    ),
                    self.assertRaises(i18n.CatalogueError),
                ):
                    i18n.validate_target_language(
                        mutated_key,
                        mutated_text,
                        mutated_locale,
                        source_record,
                    )

        voice = {
            "text": "Send recognised speech to Home Assistant Assist.",
            "placeholders": [],
            "frozen": ["Home Assistant"],
        }
        valid = "将识别出的语音发送至 Home Assistant Assist。"
        i18n.validate_target_language("settings.voice_enabled.help", valid, "zh-Hans", voice)
        for key, text in (
            ("settings.other.help", valid),
            ("settings.voice_enabled.help", "将识别出的语音发送至 Home Assistant Assistant。"),
        ):
            with self.subTest(key=key, text=text), self.assertRaises(i18n.CatalogueError):
                i18n.validate_target_language(key, text, "zh-Hans", voice)

        mqtt_help = source["strings"]["setup.mqtt.help.body"]
        reviewed = "请在 Home Assistant 中打开 Mosquitto broker。"
        i18n.validate_target_language("setup.mqtt.help.body", reviewed, "zh-Hans", mqtt_help)
        for key, text in (
            ("setup.mqtt.help.title", reviewed),
            ("setup.mqtt.help.body", reviewed.replace("broker", "brokers")),
        ):
            with self.subTest(key=key, text=text), self.assertRaises(i18n.CatalogueError):
                i18n.validate_target_language(key, text, "zh-Hans", mqtt_help)

        zh_target = i18n.validate_target(
            catalogue_dir / "zh-Hans.json",
            source,
            expected_locale="zh-Hans",
        )
        expected_entities_literals = {
            "entities.dynamic.body": ("ID",),
            "entities.issue.auto-entities-options-dynamic.summary": ("Auto-entities",),
            "entities.issue.auto-entities-options-javascript.summary": ("Auto-entities",),
            "entities.issue.auto-entities-seed-row-dynamic.summary": ("Auto-entities",),
            "entities.issue.auto-entities-typed-row-dynamic.summary": ("Auto-entities",),
            "entities.issue.kio\u0073k-mode-dynamic-javascript.recommendation": ("Kiosk",),
            "entities.status.unresolved_help": ("ID",),
        }
        for key, literals in expected_entities_literals.items():
            pair = ("zh-Hans", key)
            self.assertEqual(literals, i18n.TARGET_LITERAL_EXCEPTIONS.get(pair))
            text = zh_target["strings"][key]["text"]
            with mock.patch.dict(i18n.TARGET_LITERAL_EXCEPTIONS, {pair: ()}):
                with self.subTest(key=key), self.assertRaises(i18n.CatalogueError):
                    i18n.validate_target_language(key, text, "zh-Hans", source["strings"][key])

    def test_every_unchanged_english_fallback_target_is_registered_as_an_exception(self):
        # An english-fallback record whose text is byte-identical to the source is exactly
        # the shape the translation-candidate pipeline re-offers for regeneration on every
        # push (it always retries english-fallback records). If the provider returns the
        # same, correct cognate text again, an unregistered pair fails the candidate bundle
        # step with nothing catching it beforehand. Ask the real validator, per key, whether
        # that would happen, rather than guessing from the record shape.
        catalogue_dir = SCRIPT.parents[1] / "app/src/main/assets/i18n"
        source = i18n.validate_source(catalogue_dir / "en.json")
        for locale in sorted(i18n.LOCALES):
            target = json.loads((catalogue_dir / f"{locale}.json").read_text(encoding="utf-8"))
            for key, record in target["strings"].items():
                if record.get("state") != "english-fallback":
                    continue
                source_text = source["strings"][key]["text"]
                if record["text"] != source_text:
                    continue
                source_record = source["strings"][key]
                if i18n.held_for_review(locale, record, source_record):
                    continue
                pair = (locale, key)
                with self.subTest(locale=locale, key=key):
                    with mock.patch.dict(i18n.UNCHANGED_TARGET_EXCEPTIONS, {}, clear=False):
                        i18n.UNCHANGED_TARGET_EXCEPTIONS.pop(pair, None)
                        try:
                            i18n.validate_target_language(key, source_text, locale, source_record)
                        except i18n.CatalogueError:
                            registration_required = True
                        else:
                            registration_required = False
                    if not registration_required:
                        continue
                    self.assertEqual(
                        source_text,
                        i18n.UNCHANGED_TARGET_EXCEPTIONS.get(pair),
                        f"{locale}/{key} carries an unchanged english-fallback text that the "
                        "validator rejects without a registered exception, so a future "
                        "regeneration would fail the candidate bundle step unless "
                        "UNCHANGED_TARGET_EXCEPTIONS carries this exact pair.",
                    )

    def test_install_information_symbol_exception_is_exact_and_key_scoped(self):
        key = "install.presentation.status_no_renderer"
        source_record = {
            "text": "ℹ MQTT is configured.",
            "placeholders": [],
            "frozen": ["MQTT"],
        }
        targets = {
            "de": "ℹ MQTT ist konfiguriert.",
            "es": "ℹ MQTT está configurado.",
            "fr": "ℹ MQTT est configuré.",
            "it": "ℹ MQTT è configurato.",
            "zh-Hans": "ℹ MQTT 已配置。",
        }
        for locale, text in targets.items():
            pair = (locale, key)
            self.assertEqual(("ℹ",), i18n.TARGET_LITERAL_EXCEPTIONS.get(pair))
            i18n.validate_target_language(key, text, locale, source_record)
            with self.subTest(locale=locale, key=f"{key}.other"), self.assertRaises(i18n.CatalogueError):
                i18n.validate_target_language(f"{key}.other", text, locale, source_record)
            with mock.patch.dict(i18n.TARGET_LITERAL_EXCEPTIONS, {pair: ()}):
                with self.subTest(locale=locale, mutation="removed"), self.assertRaises(i18n.CatalogueError):
                    i18n.validate_target_language(key, text, locale, source_record)

    def test_install_chinese_diagnostic_literals_are_exact_and_key_scoped(self):
        cases = {
            "install.apk.dynamic.paste_url": (
                "Paste an https:// URL.",
                "请粘贴 https:// 网址。",
                "https://",
            ),
            "install.apk_status.invalid_url": (
                "The URL must use https://.",
                "网址必须使用 https://。",
                "https://",
            ),
            "install.presentation.status_zigbee_legacy_watchdog": (
                "LD_LIBRARY_PATH still selects old libraries.",
                "LD_LIBRARY_PATH 仍在选择旧版库。",
                "LD_LIBRARY_PATH",
            ),
        }
        for key, (source_text, target_text, literal) in cases.items():
            pair = ("zh-Hans", key)
            source_record = {"text": source_text, "placeholders": [], "frozen": []}
            self.assertEqual((literal,), i18n.TARGET_LITERAL_EXCEPTIONS.get(pair))
            i18n.validate_target_language(key, target_text, "zh-Hans", source_record)
            with self.subTest(key=key, mutation="other-key"), self.assertRaises(i18n.CatalogueError):
                i18n.validate_target_language(f"{key}.other", target_text, "zh-Hans", source_record)
            with mock.patch.dict(i18n.TARGET_LITERAL_EXCEPTIONS, {pair: ()}):
                with self.subTest(key=key, mutation="removed"), self.assertRaises(i18n.CatalogueError):
                    i18n.validate_target_language(key, target_text, "zh-Hans", source_record)

    def test_report_counts_current_translation_and_effective_fallback_per_locale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "catalogues" / "en.json"
            target_path = root / "catalogues" / "de.json"
            output_path = root / "catalogues" / "report.json"
            context_path = source_path.parent / "context" / "home-assistant-terminology.json"
            source = self.report_source()
            self.write(source_path, source)
            self.write(context_path, self.report_context())
            hashes = {key: record["sourceHash"] for key, record in source["strings"].items()}
            self.write(target_path, {
                "schema": 1,
                "locale": "de",
                "sourceRevision": "a" * 40,
                "strings": {
                    "settings.a.label": {
                        "text": "Deutsche Einstellung A.",
                        "sourceHash": hashes["settings.a.label"],
                        "state": "machine-cross-checked",
                    },
                    "settings.b.label": {
                        "text": "Deutsche Einstellung B.",
                        "sourceHash": hashes["settings.b.label"],
                        "state": "community-corrected",
                    },
                    "settings.c.label": {
                        "text": "Deutsche Einstellung C.",
                        "sourceHash": hashes["settings.c.label"],
                        "state": "machine-draft",
                    },
                    "settings.d.label": {
                        "text": "Veraltete Einstellung D.",
                        "sourceHash": "0" * 64,
                        "state": "machine-cross-checked",
                    },
                    "settings.e.label": {
                        "text": "English setting e.",
                        "sourceHash": hashes["settings.e.label"],
                        "state": "english-fallback",
                    },
                    "settings.removed.label": {
                        "text": "Entfernte Einstellung.",
                        "sourceHash": "9" * 64,
                        "state": "machine-draft",
                    },
                },
            })

            expected = {
                "schema": 1,
                "source": {
                    "locale": "en",
                    "revision": "e" * 40,
                    "fileSha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
                    "strings": 6,
                    "surfaceCounts": {"settings": 6},
                    "riskCounts": {"consequential": 2, "ordinary": 2, "setup": 2},
                },
                "context": {
                    "id": "test-terminology",
                    "fileSha256": hashlib.sha256(context_path.read_bytes()).hexdigest(),
                    "terms": 1,
                    "sourcePins": [{
                        "id": "frontend",
                        "revision": "b" * 40,
                        "artifactSha256": "c" * 64,
                    }],
                },
                "locales": {"de": {
                    "catalogueRecords": 6,
                    "fileSha256": hashlib.sha256(target_path.read_bytes()).hexdigest(),
                    "sourceRevision": "a" * 40,
                    "sourceRevisionMatches": False,
                    "stateCounts": {
                        "community-corrected": 1,
                        "english-fallback": 1,
                        "machine-cross-checked": 2,
                        "machine-draft": 2,
                    },
                    "surfaces": {"settings": {
                        "source": 6,
                        "stateCounts": {
                            "community-corrected": 1,
                            "english-fallback": 1,
                            "machine-cross-checked": 2,
                            "machine-draft": 1,
                        },
                        "missing": 1,
                        "stale": 1,
                        "current": 4,
                        "translated": 2,
                        "fallback": 4,
                    }},
                    "risks": {
                        "consequential": {
                            "source": 2,
                            "stateCounts": {
                                "community-corrected": 0,
                                "english-fallback": 1,
                                "machine-cross-checked": 0,
                                "machine-draft": 0,
                            },
                            "missing": 1, "stale": 0, "current": 1,
                            "translated": 0, "fallback": 2,
                        },
                        "ordinary": {
                            "source": 2,
                            "stateCounts": {
                                "community-corrected": 1,
                                "english-fallback": 0,
                                "machine-cross-checked": 1,
                                "machine-draft": 0,
                            },
                            "missing": 0, "stale": 0, "current": 2,
                            "translated": 2, "fallback": 0,
                        },
                        "setup": {
                            "source": 2,
                            "stateCounts": {
                                "community-corrected": 0,
                                "english-fallback": 0,
                                "machine-cross-checked": 1,
                                "machine-draft": 1,
                            },
                            "missing": 0, "stale": 1, "current": 1,
                            "translated": 0, "fallback": 2,
                        },
                    },
                    "missing": {
                        "count": 1, "percent": 16.67,
                        "keys": ["settings.f.label"],
                    },
                    "stale": {
                        "count": 1, "percent": 16.67,
                        "keys": ["settings.d.label"],
                    },
                    "current": {"count": 4, "percent": 66.67},
                    "translated": {"count": 2, "percent": 33.33},
                    "fallback": {"count": 4, "percent": 66.67},
                    "extra": 1,
                }},
            }
            self.assertEqual(expected, i18n.catalogue_report(source_path, [target_path]))

            command = [
                sys.executable, str(SCRIPT), "report",
                "--source", str(source_path),
                "--target-dir", str(source_path.parent),
                "--output", str(output_path),
            ]
            subprocess.run(command, check=True)
            first_bytes = output_path.read_bytes()
            self.assertEqual(expected, json.loads(first_bytes))
            subprocess.run(command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())

            directory_alias = root / "catalogue-alias"
            directory_alias.symlink_to(source_path.parent, target_is_directory=True)
            aliased_command = list(command)
            aliased_command[aliased_command.index("--target-dir") + 1] = str(directory_alias)
            subprocess.run(aliased_command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            subprocess.run(aliased_command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())

            external_context_path = root / "external" / "terminology.json"
            self.write(external_context_path, self.report_context())
            explicit_context_path = source_path.parent / "terminology.json"
            explicit_context_path.symlink_to(external_context_path)
            explicit_command = command[:-2] + [
                "--context", str(explicit_context_path), "--output", str(output_path),
            ]
            subprocess.run(explicit_command, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            explicit_aliased_dir = list(explicit_command)
            explicit_aliased_dir[explicit_aliased_dir.index("--target-dir") + 1] = str(directory_alias)
            subprocess.run(explicit_aliased_dir, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            explicit_context_alias = list(explicit_command)
            explicit_context_alias[explicit_context_alias.index("--context") + 1] = str(
                directory_alias / explicit_context_path.name
            )
            subprocess.run(explicit_context_alias, check=True)
            self.assertEqual(first_bytes, output_path.read_bytes())
            explicit_context_path.unlink()

            source_before = source_path.read_bytes()
            source_collision = command[:-1] + [str(source_path)]
            failed = subprocess.run(source_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite the source catalogue", failed.stderr)
            self.assertEqual(source_before, source_path.read_bytes())

            target_before = target_path.read_bytes()
            target_collision = command[:-1] + [str(target_path)]
            failed = subprocess.run(target_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite a target catalogue", failed.stderr)
            self.assertEqual(target_before, target_path.read_bytes())

            context_before = context_path.read_bytes()
            context_collision = command[:-1] + [str(context_path)]
            failed = subprocess.run(context_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite the context artifact", failed.stderr)
            self.assertEqual(context_before, context_path.read_bytes())

            context_alias = root / "context-output.json"
            context_alias.symlink_to(context_path)
            context_alias_collision = command[:-1] + [str(context_alias)]
            failed = subprocess.run(context_alias_collision, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite the context artifact", failed.stderr)
            self.assertEqual(context_before, context_path.read_bytes())

            alias_path = source_path.parent / "fr.json"
            alias_path.symlink_to(output_path.name)
            output_before = output_path.read_bytes()
            failed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertIn("must not overwrite a target catalogue", failed.stderr)
            self.assertEqual(output_before, output_path.read_bytes())

            with self.assertRaisesRegex(
                i18n.CatalogueError,
                "report requires at least one target catalogue",
            ):
                i18n.catalogue_report(source_path, [])

    def test_report_counts_current_machine_draft_as_translated_only_for_early_access_locales(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "catalogues" / "en.json"
            source = self.report_source()
            self.write(source_path, source)
            hashes = {key: record["sourceHash"] for key, record in source["strings"].items()}

            def draft_target(locale):
                return {
                    "schema": 1,
                    "locale": locale,
                    "sourceRevision": "e" * 40,
                    "strings": {
                        # Current (matching sourceHash) machine-draft: renders for an
                        # early-access locale, still falls back to English otherwise.
                        "settings.a.label": {
                            "text": f"{locale} draft a",
                            "sourceHash": hashes["settings.a.label"],
                            "state": "machine-draft",
                        },
                        # Stale machine-draft: never renders, early-access or not.
                        "settings.b.label": {
                            "text": f"{locale} draft b",
                            "sourceHash": "0" * 64,
                            "state": "machine-draft",
                        },
                        "settings.c.label": {"text": f"{locale} c", "sourceHash": hashes["settings.c.label"], "state": "machine-cross-checked"},
                        "settings.d.label": {
                            "text": source["strings"]["settings.d.label"]["text"],
                            "sourceHash": hashes["settings.d.label"], "state": "english-fallback",
                        },
                        "settings.e.label": {
                            "text": source["strings"]["settings.e.label"]["text"],
                            "sourceHash": hashes["settings.e.label"], "state": "english-fallback",
                        },
                        "settings.f.label": {
                            "text": source["strings"]["settings.f.label"]["text"],
                            "sourceHash": hashes["settings.f.label"], "state": "english-fallback",
                        },
                    },
                }

            nl_path = source_path.parent / "nl.json"
            de_path = source_path.parent / "de.json"
            self.write(nl_path, draft_target("nl"))
            self.write(de_path, draft_target("de"))

            report = i18n.catalogue_report(source_path, [nl_path, de_path])

            # nl is early-access (AppLocale.EARLY_ACCESS_LOCALES): the current machine-draft
            # record joins the cross-checked one as translated/renderable.
            self.assertEqual(2, report["locales"]["nl"]["translated"]["count"])
            self.assertEqual(4, report["locales"]["nl"]["fallback"]["count"])

            # de is Tier A: machine-draft never renders regardless of currency.
            self.assertEqual(1, report["locales"]["de"]["translated"]["count"])
            self.assertEqual(5, report["locales"]["de"]["fallback"]["count"])

    def test_report_rejects_malformed_public_context_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "en.json"
            target_path = root / "de.json"
            context_path = root / "context.json"
            source = self.source()
            self.write(source_path, source)
            self.write(target_path, {
                "schema": 1,
                "locale": "de",
                "sourceRevision": "e" * 40,
                "strings": {},
            })
            malformed = []
            context = self.report_context()
            context["sources"][0]["artifactSha256"] = "not-a-pin"
            malformed.append(context)
            context = self.report_context()
            context["sources"][0]["repository"] = 7
            malformed.append(context)
            context = self.report_context()
            context["sources"][0]["license"] = "Proprietary"
            malformed.append(context)
            context = self.report_context()
            context["terms"] = [None]
            malformed.append(context)
            context = self.report_context()
            context["terms"][0]["source"] = "missing-source"
            malformed.append(context)
            context = self.report_context()
            context["terms"][0]["source"] = ["frontend"]
            malformed.append(context)
            context = self.report_context()
            context["terms"][0]["source"] = {"id": "frontend"}
            malformed.append(context)
            for index, context in enumerate(malformed):
                with self.subTest(index=index):
                    self.write(context_path, context)
                    with self.assertRaises(i18n.CatalogueError):
                        i18n.catalogue_report(source_path, [target_path], context_path)

    def test_report_context_entry_does_not_hide_same_named_external_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalogue_dir = root / "catalogues"
            source_path = catalogue_dir / "en.json"
            context_path = catalogue_dir / "de.json"
            external_target = root / "target" / "de.json"
            backing_context = root / "external" / "context.json"
            aliased_target = root / "alias-target" / "de.json"
            self.write(source_path, self.source())
            self.write(backing_context, self.report_context())
            context_path.symlink_to(backing_context)
            self.write(external_target, {
                "schema": 1, "locale": "de", "sourceRevision": "e" * 40, "strings": {},
            })
            aliased_target.parent.mkdir(parents=True)
            aliased_target.symlink_to(backing_context)

            self.assertEqual(
                [external_target, aliased_target],
                i18n.report_targets(
                    source_path,
                    [external_target, aliased_target],
                    catalogue_dir,
                    None,
                    context_path,
                ),
            )


if __name__ == "__main__":
    unittest.main()
