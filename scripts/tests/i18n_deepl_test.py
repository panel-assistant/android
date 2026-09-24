import ast
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("i18n_deepl", ROOT / "scripts/i18n_deepl.py")
DEEPL = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEEPL)


REVISION = "1" * 40
SOURCE_REVISION = "2" * 40


def source_record(text, *, frozen=None, placeholders=None):
    frozen = frozen or []
    placeholders = placeholders or []
    return {
        "text": text,
        "sourceHash": DEEPL.catalogue.source_hash(text),
        "surface": "settings",
        "context": "A concise setting label.",
        "risk": "ordinary",
        "siblings": [],
        "placeholders": placeholders,
        "frozen": frozen,
        "softMaxChars": max(20, len(text)),
        "hardMaxChars": max(40, len(text)),
    }


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


class FakeHttp:
    def __init__(self, translations, usages=((100, 500_000), (1_000, 500_000)), languages=None):
        self.translations = iter(translations)
        self.usages = iter(usages)
        self.languages = languages or [
            {"language": "DE", "name": "German", "supports_formality": True},
            {"language": "FR", "name": "French", "supports_formality": True},
            {"language": "IT", "name": "Italian", "supports_formality": True},
            {"language": "ES", "name": "Spanish", "supports_formality": True},
            {"language": "ZH-HANS", "name": "Chinese (simplified)", "supports_formality": False},
            {"language": "NL", "name": "Dutch", "supports_formality": True},
            {"language": "PL", "name": "Polish", "supports_formality": True},
            {"language": "UK", "name": "Ukrainian", "supports_formality": False},
        ]
        self.requests = []

    def __call__(self, request):
        self.requests.append(request)
        if "/v2/languages?type=target" in request.full_url:
            return json.dumps(self.languages).encode()
        if request.full_url.endswith("/v2/usage"):
            count, limit = next(self.usages)
            return json.dumps({"character_count": count, "character_limit": limit}).encode()
        request_body = json.loads(request.data)
        return json.dumps({
            "translations": [{
                "text": next(self.translations),
                "billed_characters": len(request_body["text"][0]),
            }],
        }).encode()


class DeepLAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source_path = self.root / "en.json"
        self.target_dir = self.root / "targets"
        self.context_path = self.root / "context.json"
        self.context_path.write_bytes(
            (ROOT / "app/src/main/assets/i18n/context/home-assistant-terminology.json").read_bytes()
        )
        self.source = {
            "schema": 1,
            "locale": "en",
            "sourceRevision": SOURCE_REVISION,
            "strings": {
                "settings.alpha.label": source_record("Alpha"),
                "settings.beta.help": source_record("Keep {name} on MQTT", frozen=["MQTT"], placeholders=["{name}"]),
                "settings.gamma.label": source_record("Gamma"),
            },
        }
        write_json(self.source_path, self.source)
        for locale in DEEPL.TARGETS:
            self.target(locale, {})

    def tearDown(self):
        self.temp.cleanup()

    def target(self, locale, records, source_revision=SOURCE_REVISION):
        write_json(self.target_dir / f"{locale}.json", {
            "schema": 1,
            "locale": locale,
            "sourceRevision": source_revision,
            "strings": records,
        })

    def test_provider_targets_are_unique_complete_and_mutation_sensitive(self):
        script = ROOT / "scripts/i18n_deepl.py"
        tree = ast.parse(script.read_text(encoding="utf-8"))
        assignment = next(
            node for node in tree.body
            if isinstance(node, ast.Assign)
            and any(
                isinstance(target, ast.Name) and target.id == "TARGETS"
                for target in node.targets
            )
        )
        self.assertIsInstance(assignment.value, ast.Dict)
        literal_keys = [ast.literal_eval(key) for key in assignment.value.keys]
        self.assertEqual(
            len(literal_keys), len(set(literal_keys)), "DeepL targets have duplicate locale keys"
        )
        self.assertSetEqual(set(literal_keys), DEEPL.catalogue.LOCALES)
        DEEPL.validate_locale_configuration()

        selected = sorted(DEEPL.catalogue.LOCALES)[0]
        for mutation in (
            {key: value for key, value in DEEPL.TARGETS.items() if key != selected},
            {**DEEPL.TARGETS, "extra-locale": ("XX", "default")},
        ):
            with (
                self.subTest(mutation=mutation),
                mock.patch.object(DEEPL, "TARGETS", mutation),
                self.assertRaisesRegex(DEEPL.DeepLError, "must exactly cover"),
            ):
                DEEPL.validate_locale_configuration()

    def test_provider_context_terms_exactly_cover_supported_locales(self):
        DEEPL._load_context(self.context_path)
        context = json.loads(self.context_path.read_text(encoding="utf-8"))
        selected = sorted(DEEPL.catalogue.LOCALES)[0]
        missing = json.loads(json.dumps(context))
        missing["terms"][0]["translations"].pop(selected)
        extra = json.loads(json.dumps(context))
        extra["terms"][0]["translations"]["extra-locale"] = "Extra"
        for mutation in (missing, extra):
            write_json(self.context_path, mutation)
            with (
                self.subTest(locales=mutation["terms"][0]["translations"]),
                self.assertRaisesRegex(
                    DEEPL.DeepLError, "malformed terminology context term value"
                ),
            ):
                DEEPL._load_context(self.context_path)

    def test_plan_selects_only_missing_stale_and_fallback_records(self):
        self.target("de", {
            "settings.alpha.label": {
                "text": "Alpha DE",
                "sourceHash": self.source["strings"]["settings.alpha.label"]["sourceHash"],
                "state": "machine-draft",
            },
            "settings.beta.help": {
                "text": "old",
                "sourceHash": "0" * 64,
                "state": "machine-cross-checked",
            },
        }, source_revision="3" * 40)
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        records = plan["batches"][0]["records"]
        self.assertEqual([item["key"] for item in records], ["settings.beta.help", "settings.gamma.label"])
        self.assertEqual(records[0]["sourceHash"], self.source["strings"]["settings.beta.help"]["sourceHash"])
        self.assertEqual(records[0]["context"], "A concise setting label.")
        self.assertEqual(records[0]["priorTarget"], {
            "text": "old",
            "sourceHash": "0" * 64,
            "state": "machine-cross-checked",
        })
        self.assertIsNone(records[1]["priorTarget"])
        self.assertEqual(plan["requestedCharacters"], len("Keep {name} on MQTT") + len("Gamma"))
        self.assertGreaterEqual(plan["maximumBilledCharacters"], plan["requestedCharacters"])
        self.assertEqual(
            plan["batches"][0]["baseTargetHash"],
            DEEPL._source_digest(self.target_dir / "de.json"),
        )

    def test_plan_does_not_regenerate_consequential_strings_held_for_review(self):
        for key in ("settings.alpha.label", "settings.beta.help"):
            self.source["strings"][key]["risk"] = "consequential"
        write_json(self.source_path, self.source)

        def fallback(key):
            record = self.source["strings"][key]
            return {"text": record["text"], "sourceHash": record["sourceHash"], "state": "english-fallback"}

        keys = ("settings.alpha.label", "settings.beta.help", "settings.gamma.label")
        for locale in ("nl", "de"):
            self.target(locale, {key: fallback(key) for key in keys}, source_revision="3" * 40)

        def planned(locale, reconsider=frozenset()):
            plan = DEEPL.build_plan(
                self.source_path, self.target_dir, self.context_path, [locale], REVISION, set(reconsider),
            )
            return [item["key"] for item in plan["batches"][0]["records"]]

        self.assertEqual(planned("nl"), ["settings.gamma.label"])
        self.assertEqual(planned("nl", {"settings.beta.help"}), ["settings.beta.help", "settings.gamma.label"])
        self.assertEqual(planned("de"), list(keys))

    def test_plan_admits_web_surface_records(self):
        self.source["strings"]["settings.gamma.label"]["surface"] = "dashboard"
        write_json(self.source_path, self.source)
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        records = {item["key"]: item for item in plan["batches"][0]["records"]}
        self.assertEqual("dashboard", records["settings.gamma.label"]["surface"])

    def test_plan_rejects_a_missing_or_malformed_base_target(self):
        (self.target_dir / "de.json").unlink()
        with self.assertRaisesRegex(DEEPL.DeepLError, "base target catalogue is missing"):
            DEEPL.build_plan(
                self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
            )
        (self.target_dir / "de.json").write_text('{"schema":1,"schema":1}', encoding="utf-8")
        with self.assertRaisesRegex(DEEPL.catalogue.CatalogueError, "duplicate JSON key"):
            DEEPL.build_plan(
                self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
            )

    def test_plan_refuses_to_replace_current_community_correction(self):
        record = self.source["strings"]["settings.alpha.label"]
        self.target("fr", {
            "settings.alpha.label": {
                "text": "Alpha corrigé",
                "sourceHash": record["sourceHash"],
                "state": "community-corrected",
            },
        })
        with self.assertRaisesRegex(DEEPL.DeepLError, "community correction"):
            DEEPL.build_plan(
                self.source_path, self.target_dir, self.context_path, ["fr"], REVISION,
                {"settings.alpha.label"},
            )

    def test_plan_binds_exact_terminology_context_bytes(self):
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
        )

        self.assertEqual(plan["contextArtifactId"], "home-assistant-terminology")
        self.assertEqual(plan["contextArtifactHash"], DEEPL._source_digest(self.context_path))
        self.assertEqual(plan["contextArtifactBytes"], len(self.context_path.read_bytes()))

    def test_generate_rejects_context_byte_drift_before_provider_access(self):
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
        )
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        self.context_path.write_bytes(self.context_path.read_bytes() + b"\n")
        fake = FakeHttp([])
        output = self.root / "output"

        with self.assertRaisesRegex(DEEPL.DeepLError, "context drifted"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", fake)

        self.assertEqual(fake.requests, [])
        self.assertFalse(output.exists())

    def test_generate_rejects_base_target_drift_before_provider_access(self):
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
        )
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        self.target("de", {
            "settings.alpha.label": {
                "text": "Alpha DE",
                "sourceHash": self.source["strings"]["settings.alpha.label"]["sourceHash"],
                "state": "machine-draft",
            },
        })
        fake = FakeHttp([])
        output = self.root / "output"

        with self.assertRaisesRegex(DEEPL.DeepLError, "base target drifted"):
            DEEPL.generate(
                plan_path, self.source_path, self.target_dir, self.context_path,
                output, "key:fx", fake,
            )

        self.assertEqual(fake.requests, [])
        self.assertFalse(output.exists())

    def test_generate_rejects_self_consistent_plan_record_substitution_before_provider_access(self):
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
        )
        record = plan["batches"][0]["records"][0]
        record["english"] = "Altered Alpha"
        record["sourceHash"] = DEEPL.catalogue.source_hash(record["english"])
        record["maximumBilledCharacters"] = len(DEEPL._protected_xml(record)[0])
        plan["requestedCharacters"] = sum(
            len(item["english"])
            for batch in plan["batches"]
            for item in batch["records"]
        )
        plan["maximumBilledCharacters"] = sum(
            item["maximumBilledCharacters"]
            for batch in plan["batches"]
            for item in batch["records"]
        )
        plan_path = self.root / "substituted-plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp([])
        output = self.root / "substituted-output"

        with self.assertRaisesRegex(DEEPL.DeepLError, "plan does not match"):
            DEEPL.generate(
                plan_path, self.source_path, self.target_dir, self.context_path,
                output, "key:fx", fake,
            )

        self.assertEqual(fake.requests, [])
        self.assertFalse(output.exists())

    def test_generate_rejects_prior_target_substitution_before_provider_access(self):
        source_record = self.source["strings"]["settings.alpha.label"]
        self.target("de", {
            "settings.alpha.label": {
                "text": "Veraltetes Alpha",
                "sourceHash": "0" * 64,
                "state": "machine-draft",
            },
        })
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
        )
        record = next(
            item for item in plan["batches"][0]["records"]
            if item["key"] == "settings.alpha.label"
        )
        self.assertEqual(record["sourceHash"], source_record["sourceHash"])
        record["priorTarget"]["text"] = "Invented review context"
        plan_path = self.root / "prior-substituted-plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp([])

        with self.assertRaisesRegex(DEEPL.DeepLError, "plan does not match"):
            DEEPL.generate(
                plan_path, self.source_path, self.target_dir, self.context_path,
                self.root / "prior-substituted-output", "key:fx", fake,
            )

        self.assertEqual(fake.requests, [])

    def test_generate_rejects_injected_current_community_correction_before_provider_access(self):
        source_record = self.source["strings"]["settings.alpha.label"]
        self.target("de", {
            "settings.alpha.label": {
                "text": "Kuratiertes Alpha",
                "sourceHash": source_record["sourceHash"],
                "state": "community-corrected",
            },
        })
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
        )
        injected = DEEPL._selected_record("settings.alpha.label", source_record, self.source)
        injected["priorTarget"] = {
            "text": "Kuratiertes Alpha",
            "sourceHash": source_record["sourceHash"],
            "state": "community-corrected",
        }
        plan["batches"][0]["records"].append(injected)
        plan["batches"][0]["records"].sort(key=lambda item: item["key"])
        plan["requestedCharacters"] += len(injected["english"])
        plan["maximumBilledCharacters"] += injected["maximumBilledCharacters"]
        plan_path = self.root / "injected-plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp([])

        with self.assertRaisesRegex(DEEPL.DeepLError, "plan does not match"):
            DEEPL.generate(
                plan_path, self.source_path, self.target_dir, self.context_path,
                self.root / "injected-output", "key:fx", fake,
            )

        self.assertEqual(fake.requests, [])

    def test_explicit_reconsideration_is_bound_and_rederived(self):
        source_record = self.source["strings"]["settings.alpha.label"]
        self.target("de", {
            "settings.alpha.label": {
                "text": "Alpha DE",
                "sourceHash": source_record["sourceHash"],
                "state": "machine-draft",
            },
        })
        plan = DEEPL.build_plan(
            self.source_path,
            self.target_dir,
            self.context_path,
            ["de"],
            REVISION,
            {"settings.alpha.label"},
        )

        self.assertEqual(plan["reconsideredKeys"], ["settings.alpha.label"])
        source, context = DEEPL._validate_plan_inputs(
            plan, self.source_path, self.target_dir, self.context_path,
        )
        self.assertEqual(source["sourceRevision"], SOURCE_REVISION)
        self.assertEqual(context["id"], "home-assistant-terminology")

    def test_context_and_provider_responses_reject_duplicate_json_keys(self):
        self.context_path.write_text('{"schema":1,"schema":1}', encoding="utf-8")
        with self.assertRaisesRegex(DEEPL.DeepLError, "duplicate JSON key"):
            DEEPL._load_context(self.context_path)

        attacker_key = "::error::provider echoed key:fx"
        encoded_key = json.dumps(attacker_key)
        response = f'{{{encoded_key}:1,{encoded_key}:2}}'.encode()
        with self.assertRaisesRegex(DEEPL.DeepLError, "^duplicate JSON key$") as raised:
            DEEPL._request_json("/v2/usage", "key:fx", lambda _request: response)
        self.assertNotIn(attacker_key, str(raised.exception))

    def test_outgoing_request_body_is_bounded_before_http(self):
        calls = []
        with self.assertRaisesRegex(DEEPL.DeepLError, "request body is too large"):
            DEEPL._request_json(
                "/v2/translate",
                "key:fx",
                lambda request: calls.append(request) or b"{}",
                {"text": ["x" * DEEPL.MAX_REQUEST_BYTES]},
            )
        self.assertEqual(calls, [])

    def test_protected_xml_round_trip_requires_exact_tokens(self):
        record = DEEPL._selected_record(
            "settings.beta.help", self.source["strings"]["settings.beta.help"], self.source,
        )
        protected, tokens = DEEPL._protected_xml(record)
        self.assertIn('<x id="0">{name}</x>', protected)
        self.assertIn("MQTT</x>", protected)
        translated = protected.replace("Keep ", "Behalte ").replace(" on ", " auf ")
        self.assertEqual(DEEPL._restore_xml(translated, tokens, record["key"]), "Behalte {name} auf MQTT")
        with self.assertRaisesRegex(DEEPL.DeepLError, "changed protected token"):
            DEEPL._restore_xml(translated.replace("MQTT", "Mqtt"), tokens, record["key"])

    def test_protected_xml_accepts_overlapping_frozen_tokens(self):
        record = {
            "key": "settings.url.help",
            "english": "Use HTTPS, not HTTP.",
            "placeholders": [],
            "frozen": ["HTTPS", "HTTP"],
        }
        protected, tokens = DEEPL._protected_xml(record)
        self.assertEqual(protected.count("<x "), 2)
        self.assertEqual(DEEPL._restore_xml(protected, tokens, record["key"]), record["english"])

    def test_default_http_rejects_oversized_response(self):
        class Response:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, limit):
                self.asserted_limit = limit
                return b"x" * limit

        response = Response()
        request = DEEPL.urllib.request.Request(DEEPL.API_ORIGIN + "/v2/usage")
        opener = mock.Mock()
        opener.open.return_value = response
        with mock.patch.object(DEEPL.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(DEEPL.DeepLError, "too large"):
                DEEPL._default_http(request)
        self.assertEqual(response.asserted_limit, DEEPL.MAX_RESPONSE_BYTES + 1)

    def test_authenticated_requests_never_follow_redirects(self):
        request = DEEPL.urllib.request.Request(
            DEEPL.API_ORIGIN + "/v2/usage",
            headers={"Authorization": "DeepL-Auth-Key test-key"},
        )
        redirected = DEEPL._NoRedirect().redirect_request(
            request, None, 302, "Found", {}, "https://example.invalid/collect",
        )
        self.assertIsNone(redirected)

    def test_plan_ignores_removed_stale_target_keys(self):
        self.target("de", {
            "settings.removed.label": {
                "text": "Entfernt",
                "sourceHash": "0" * 64,
                "state": "machine-draft",
            },
        })
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        self.assertEqual(len(plan["batches"][0]["records"]), 3)

    def test_generate_preflights_translates_once_and_postflights(self):
        self.target("de", {
            "settings.alpha.label": {
                "text": "previous private review context",
                "sourceHash": "0" * 64,
                "state": "community-corrected",
            },
        })
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        self.assertEqual(
            plan["batches"][0]["records"][0]["priorTarget"]["text"],
            "previous private review context",
        )
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(["Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE"])
        output = self.root / "output"

        result = DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "secret-value:fx", fake)

        self.assertEqual(result["status"], "generated")
        self.assertEqual([request.full_url for request in fake.requests], [
            DEEPL.API_ORIGIN + "/v2/languages?type=target",
            DEEPL.API_ORIGIN + "/v2/usage",
            DEEPL.API_ORIGIN + "/v2/translate",
            DEEPL.API_ORIGIN + "/v2/translate",
            DEEPL.API_ORIGIN + "/v2/translate",
            DEEPL.API_ORIGIN + "/v2/usage",
        ])
        request_body = json.loads(fake.requests[3].data)
        self.assertEqual(request_body["target_lang"], "DE")
        self.assertEqual(request_body["formality"], "less")
        self.assertEqual(request_body["tag_handling"], "xml")
        self.assertEqual(request_body["tag_handling_version"], "v2")
        self.assertIs(request_body["show_billed_characters"], True)
        self.assertIn("software settings UI", request_body["custom_instructions"][0])
        self.assertIn("supplied term", request_body["custom_instructions"][1])
        self.assertIn("A concise setting label.", request_body["context"])
        self.assertIn("ha-paneld is an Android wall-panel client", request_body["context"])
        self.assertIn("Entity = Entität", request_body["context"])
        self.assertNotIn("previous private review context", json.dumps([
            json.loads(request.data)
            for request in fake.requests
            if request.data is not None
        ]))
        self.assertNotIn("secret-value", json.dumps(result))
        self.assertEqual(result["contextArtifactHash"], plan["contextArtifactHash"])
        self.assertEqual(result["contextArtifactBytes"], plan["contextArtifactBytes"])
        self.assertEqual(result["billedCharacters"], result["maximumBilledCharacters"])
        candidate = json.loads((output / "candidates/de.json").read_text())
        self.assertEqual(candidate["sourceRevision"], SOURCE_REVISION)
        self.assertEqual(candidate["sourceCatalogueHash"], DEEPL._source_digest(self.source_path))
        self.assertEqual(candidate["translations"][1]["translation"], "Behalte {name} auf MQTT")
        self.assertEqual(result["resultHashes"]["de"], DEEPL._source_digest(output / "candidates/de.json"))

    def test_custom_instruction_support_covers_documented_language_families(self):
        supported = {
            "DE", "DE-AT", "EN", "EN-GB", "EN-US", "ES", "ES-419", "FR", "IT",
            "JA", "KO", "ZH", "ZH-HANS", "ZH-HANT",
        }
        unsupported = {"NL", "PL", "CS", "PT-BR", "PT-PT", "UK"}

        self.assertEqual(
            {target for target in supported if DEEPL._supports_custom_instructions(target)},
            supported,
        )
        self.assertEqual(
            {target for target in unsupported if DEEPL._supports_custom_instructions(target)},
            set(),
        )

    def test_custom_instruction_support_rejects_noncanonical_target_codes(self):
        malformed = (
            None, True, 1, "", "de", "Pt-BR", "PT_br", "-DE", "DE-", "DE--AT",
            "DE-A", "DE-ABCDEFGHI",
        )
        for target in malformed:
            with self.subTest(target=target):
                with self.assertRaisesRegex(DEEPL.DeepLError, "target language is not canonical"):
                    DEEPL._supports_custom_instructions(target)

    def test_request_body_preserves_tier_a_and_omits_instructions_for_tier_b(self):
        record = DEEPL._selected_record(
            "settings.alpha.label",
            self.source["strings"]["settings.alpha.label"],
            self.source,
        )
        context = DEEPL._load_context(self.context_path)[0]
        protected_text = DEEPL._protected_xml(record)[0]
        tier_a = {
            "de": "DE",
            "fr": "FR",
            "it": "IT",
            "es": "ES",
            "zh-Hans": "ZH-HANS",
        }
        tier_b = {
            "nl": ("NL", "prefer_less"),
            "pl": ("PL", "prefer_less"),
            "cs": ("CS", "prefer_less"),
            "pt-BR": ("PT-BR", "prefer_less"),
            "uk": ("UK", "prefer_less"),
        }

        for locale, target in tier_a.items():
            with self.subTest(locale=locale):
                first = DEEPL._translation_request_body(record, locale, context, protected_text)
                second = DEEPL._translation_request_body(record, locale, context, protected_text)
                self.assertEqual(first, second)
                self.assertEqual(first["target_lang"], target)
                self.assertEqual(len(first["custom_instructions"]), 2)
                self.assertEqual(
                    list(first),
                    [
                        "text", "source_lang", "target_lang", "context", "show_billed_characters",
                        "formality", "model_type", "custom_instructions", "tag_handling",
                        "tag_handling_version", "ignore_tags", "preserve_formatting",
                    ],
                )

        with mock.patch.dict(DEEPL.TARGETS, tier_b):
            for locale, (target, formality) in tier_b.items():
                with self.subTest(locale=locale):
                    first = DEEPL._translation_request_body(record, locale, context, protected_text)
                    second = DEEPL._translation_request_body(record, locale, context, protected_text)
                    self.assertEqual(first, second)
                    self.assertEqual(first["target_lang"], target)
                    self.assertEqual(first["formality"], formality)
                    self.assertNotIn("custom_instructions", first)
                    self.assertEqual(first["context"], second["context"])
                    self.assertEqual(first["text"], second["text"])
                    self.assertEqual(first["tag_handling"], "xml")
                    self.assertEqual(first["tag_handling_version"], "v2")
                    self.assertEqual(first["ignore_tags"], ["x"])
                    self.assertIs(first["show_billed_characters"], True)

                    fake = FakeHttp([f"Alpha {locale}"])
                    translated, billed = DEEPL._translate(record, locale, context, "key:fx", fake)
                    emitted = json.loads(fake.requests[0].data)
                    self.assertEqual(translated, f"Alpha {locale}")
                    self.assertEqual(billed, len(protected_text))
                    self.assertEqual(emitted, first)
                    self.assertNotIn("custom_instructions", emitted)

    def test_malformed_configured_target_fails_before_http(self):
        record = DEEPL._selected_record(
            "settings.alpha.label",
            self.source["strings"]["settings.alpha.label"],
            self.source,
        )
        context = DEEPL._load_context(self.context_path)[0]
        fake = FakeHttp(["Alpha"])

        with mock.patch.dict(DEEPL.TARGETS, {"bad": ("pt-BR", "prefer_less")}):
            with self.assertRaisesRegex(DEEPL.DeepLError, "target language is not canonical"):
                DEEPL._translate(record, "bad", context, "key:fx", fake)

        self.assertEqual(fake.requests, [])

    def test_insufficient_quota_is_a_no_op_with_reserve(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        requested = plan["requestedCharacters"]
        fake = FakeHttp([], usages=((450_000 - requested + 1, 500_000),))
        output = self.root / "output"

        result = DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", fake)

        self.assertEqual(result["status"], "skipped-quota")
        self.assertFalse((output / "candidates").exists())
        self.assertEqual(len(fake.requests), 2)

    def test_no_changes_needs_no_credential_or_api_call(self):
        translations = {
            "settings.alpha.label": "Alpha FR",
            "settings.beta.help": "Garder {name} sur MQTT",
            "settings.gamma.label": "Gamma FR",
        }
        current = {
            key: {
                "text": translations[key],
                "sourceHash": record["sourceHash"],
                "state": "machine-draft",
            }
            for key, record in self.source["strings"].items()
        }
        self.target("fr", current)
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        output = self.root / "output"
        result = DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "")
        self.assertEqual(result["status"], "no-changes")

    def test_ambiguous_translation_failure_is_not_retried_or_published(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["es"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        calls = []

        def failing(request):
            calls.append(request)
            if "/v2/languages?type=target" in request.full_url:
                return json.dumps(FakeHttp([]).languages).encode()
            if request.full_url.endswith("/usage"):
                return b'{"character_count":0,"character_limit":500000}'
            raise DEEPL.DeepLError("translation request outcome is ambiguous; not retrying")

        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "ambiguous; not retrying"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", failing)
        self.assertEqual(len(calls), 3)
        self.assertFalse(output.exists())

    def test_malformed_postflight_discards_all_candidates(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["it"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(["Alfa", 'Tieni <x id="0">{name}</x> su <x id="1">MQTT</x>', "Gamma IT"], usages=((0, 500_000), (50, 400_000)))
        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", fake)
        self.assertFalse(output.exists())

    def test_decreasing_postflight_usage_discards_all_candidates(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(
            ["Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE"],
            usages=((100, 500_000), (99, 500_000)),
        )
        output = self.root / "output"

        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", fake)
        self.assertFalse(output.exists())

    def test_postflight_usage_must_corroborate_reported_billing(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        billed = plan["maximumBilledCharacters"]
        fake = FakeHttp(
            ["Alpha FR", 'Garder <x id="0">{name}</x> sur <x id="1">MQTT</x>', "Gamma FR"],
            usages=((100, 500_000), (100 + billed - 1, 500_000)),
        )
        output = self.root / "output"

        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", fake)
        self.assertFalse(output.exists())

    def test_run_validation_rejects_uncorroborated_generated_billing(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["it"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp(
            ["Alfa", 'Tieni <x id="0">{name}</x> su <x id="1">MQTT</x>', "Gamma IT"],
        )
        result = DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, self.root / "output", "key:fx", fake)
        result["accountUsageAfter"] = result["accountUsageBefore"] + result["billedCharacters"] - 1

        with self.assertRaisesRegex(DEEPL.DeepLError, "inconsistent"):
            DEEPL._validate_run(result, plan)

    def test_public_summary_omits_private_account_quota_telemetry(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        output = self.root / "output"
        DEEPL.generate(
            plan_path,
            self.source_path,
            self.target_dir,
            self.context_path,
            output,
            "key:fx",
            FakeHttp(["Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE"]),
        )

        rendered = DEEPL.summary(plan_path, output / "run.json")
        self.assertIn("Run billed characters", rendered)
        self.assertNotIn("Account usage", rendered)
        self.assertNotIn("500000", rendered)

    def test_public_bundle_binds_inputs_and_exact_merged_catalogue_without_account_telemetry(self):
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["fr", "de"], REVISION, set(),
        )
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        generated = self.root / "generated"
        run = DEEPL.generate(
            plan_path, self.source_path, self.target_dir, self.context_path, generated,
            "key:fx",
            FakeHttp([
                "Alpha FR", 'Garder <x id="0">{name}</x> sur <x id="1">MQTT</x>', "Gamma FR",
                "Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE",
            ]),
        )
        bundle = self.root / "bundle"

        receipt = DEEPL.build_bundle(
            self.source_path, self.target_dir, self.context_path, plan_path,
            generated / "run.json", generated / "candidates", bundle,
        )

        self.assertEqual(set(path.name for path in bundle.iterdir()), {
            "de.json", "fr.json", "plan.json", "receipt.json",
        })
        self.assertEqual(receipt["planHash"], DEEPL._source_digest(plan_path))
        self.assertEqual(receipt["baseTargetHashes"], {
            "de": next(batch["baseTargetHash"] for batch in plan["batches"] if batch["locale"] == "de"),
            "fr": next(batch["baseTargetHash"] for batch in plan["batches"] if batch["locale"] == "fr"),
        })
        self.assertEqual(list(receipt["baseTargetHashes"]), ["de", "fr"])
        self.assertEqual(receipt["providerCandidateHashes"], run["resultHashes"])
        self.assertEqual(receipt["catalogueHashes"]["de"], DEEPL._source_digest(bundle / "de.json"))
        self.assertNotIn("accountUsageBefore", receipt)
        self.assertNotIn("accountUsageAfter", receipt)
        self.assertNotIn("accountCharacterLimit", receipt)
        self.assertEqual(DEEPL.validate_bundle(bundle, self.source_path), receipt)

        merged = json.loads((bundle / "de.json").read_text(encoding="utf-8"))
        merged["strings"]["settings.alpha.label"]["text"] = "Manipuliert"
        write_json(bundle / "de.json", merged)
        with self.assertRaisesRegex(DEEPL.DeepLError, "bundled catalogue hash mismatch"):
            DEEPL.validate_bundle(bundle, self.source_path)

    def test_bundle_rejects_provider_candidate_drift_or_plan_selection_substitution(self):
        plan = DEEPL.build_plan(
            self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set(),
        )
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        generated = self.root / "generated"
        DEEPL.generate(
            plan_path, self.source_path, self.target_dir, self.context_path, generated,
            "key:fx",
            FakeHttp(["Alfa", 'Behalte <x id="0">{name}</x> auf <x id="1">MQTT</x>', "Gamma DE"]),
        )
        candidate_path = generated / "candidates/de.json"
        candidate_path.write_bytes(candidate_path.read_bytes() + b"\n")
        with self.assertRaisesRegex(DEEPL.DeepLError, "provider candidate hash mismatch"):
            DEEPL.build_bundle(
                self.source_path, self.target_dir, self.context_path, plan_path,
                generated / "run.json", generated / "candidates", self.root / "bundle-drift",
            )
        self.assertFalse((self.root / "bundle-drift").exists())

        candidate = DEEPL.catalogue.read_json(candidate_path)
        candidate["translations"] = candidate["translations"][:-1]
        write_json(candidate_path, candidate)
        run = DEEPL.catalogue.read_json(generated / "run.json")
        run["resultHashes"]["de"] = DEEPL._source_digest(candidate_path)
        write_json(generated / "run.json", run)
        with self.assertRaisesRegex(DEEPL.DeepLError, "does not match plan selection"):
            DEEPL.build_bundle(
                self.source_path, self.target_dir, self.context_path, plan_path,
                generated / "run.json", generated / "candidates", self.root / "bundle-substitution",
            )
        self.assertFalse((self.root / "bundle-substitution").exists())

        plan_path.write_bytes(plan_path.read_bytes() + b"\n")
        with self.assertRaisesRegex(DEEPL.DeepLError, "run plan hash mismatch"):
            DEEPL.build_bundle(
                self.source_path, self.target_dir, self.context_path, plan_path,
                generated / "run.json", generated / "candidates", self.root / "bundle-plan-drift",
            )
        self.assertFalse((self.root / "bundle-plan-drift").exists())

    def test_missing_target_capability_fails_before_usage_or_translation(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp([], languages=[
            {"language": "FR", "name": "French", "supports_formality": True},
        ])
        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "target language is unavailable"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", fake)
        self.assertEqual(len(fake.requests), 1)
        self.assertFalse(output.exists())

    def test_unsupported_configured_formality_fails_before_billing(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["fr"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        fake = FakeHttp([], languages=[
            {"language": "FR", "name": "French", "supports_formality": False},
        ])
        with self.assertRaisesRegex(DEEPL.DeepLError, "formality is unsupported"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, self.root / "output", "key:fx", fake)
        self.assertEqual(len(fake.requests), 1)

    def test_missing_billed_characters_discards_candidate_without_retry(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["es"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)
        calls = []

        def missing_billing(request):
            calls.append(request)
            if "/v2/languages?type=target" in request.full_url:
                return json.dumps(FakeHttp([]).languages).encode()
            if request.full_url.endswith("/usage"):
                return b'{"character_count":0,"character_limit":500000}'
            return b'{"translations":[{"text":"Alfa"}]}'

        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "billed_characters"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", missing_billing)
        self.assertEqual(len(calls), 3)
        self.assertFalse(output.exists())

    def test_excessive_reported_billing_discards_candidate(self):
        plan = DEEPL.build_plan(self.source_path, self.target_dir, self.context_path, ["de"], REVISION, set())
        plan_path = self.root / "plan.json"
        write_json(plan_path, plan)

        def excessive_billing(request):
            if "/v2/languages?type=target" in request.full_url:
                return json.dumps(FakeHttp([]).languages).encode()
            if request.full_url.endswith("/usage"):
                return b'{"character_count":0,"character_limit":500000}'
            return b'{"translations":[{"text":"Alfa","billed_characters":999999}]}'

        output = self.root / "output"
        with self.assertRaisesRegex(DEEPL.DeepLError, "excessive billed_characters"):
            DEEPL.generate(plan_path, self.source_path, self.target_dir, self.context_path, output, "key:fx", excessive_billing)
        self.assertFalse(output.exists())

    def test_usage_rejects_unparseable_limit(self):
        fake = FakeHttp([], usages=((0, "500000"),))
        with self.assertRaisesRegex(DEEPL.DeepLError, "invalid character"):
            DEEPL._usage("key:fx", fake)

    def test_check_base_rejects_moved_main(self):
        completed = mock.Mock(stdout="3" * 40 + "\n")
        with mock.patch.object(DEEPL.subprocess, "run", return_value=completed) as run:
            with self.assertRaisesRegex(DEEPL.DeepLError, "stale base"):
                DEEPL.check_base(REVISION, self.root, "origin/main")
        run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
