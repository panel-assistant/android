#!/usr/bin/env python3
"""Prepare and generate trusted, read-only translation candidate artifacts."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from typing import Any, Callable
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree

import i18n_catalogue as catalogue


API_ORIGIN = "https://api-free.deepl.com"
QUOTA_RESERVE = 50_000
MAX_RESPONSE_BYTES = 1_048_576
MAX_REQUEST_BYTES = 131_072
PLAN_SCHEMA = 1
RUN_SCHEMA = 1
RECEIPT_SCHEMA = 1
CONTEXT_SCHEMA = 1
TARGETS = {
    "de": ("DE", "less"),
    "fr": ("FR", "more"),
    "it": ("IT", "less"),
    "es": ("ES", "less"),
    "zh-Hans": ("ZH-HANS", "default"),
    "nl": ("NL", "less"),
    "pl": ("PL", "more"),
    "uk": ("UK", "default"),
}
CUSTOM_INSTRUCTION_LANGUAGE_FAMILIES = frozenset({
    "DE", "EN", "ES", "FR", "IT", "JA", "KO", "ZH",
})
TARGET_LANGUAGE_RE = re.compile(r"[A-Z]{2}(?:-[A-Z0-9]{2,8})*")
HTTP = Callable[[urllib.request.Request], bytes]

CONTEXT_ROOT_KEYS = {
    "schema", "id", "productContext", "instruction", "license", "notice", "sources", "terms",
}
CONTEXT_SOURCE_KEYS = {"id", "repository", "revision", "artifact", "artifactSha256", "license"}
CONTEXT_TERM_KEYS = {"id", "meaning", "english", "source", "sourceKey", "translations"}
RECEIPT_KEYS = {
    "schema", "status", "baseRevision", "planHash", "sourceCatalogueHash",
    "contextArtifactId", "contextArtifactHash", "contextArtifactBytes",
    "requestedCharacters", "maximumBilledCharacters", "billedCharacters",
    "selectedRecords", "baseTargetHashes", "providerCandidateHashes", "catalogueHashes",
}


class DeepLError(ValueError):
    """A sanitized, user-facing adapter failure."""


def validate_locale_configuration() -> None:
    """Keep the retired adapter limited to recognized catalogue locales."""
    if not TARGETS or not set(TARGETS).issubset(catalogue.LOCALES):
        raise DeepLError("DeepL targets must be a non-empty subset of supported locales")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Reject redirects so an authenticated header can never reach another URL."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def _write_json(path: Path, value: dict[str, Any]) -> None:
    catalogue.write_json_atomic(path, value)


def _source_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DeepLError("duplicate JSON key")
        result[key] = value
    return result


def _load_context(path: Path) -> tuple[dict[str, Any], str, int]:
    validate_locale_configuration()
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_json_object)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise DeepLError("terminology context is not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise DeepLError("terminology context root must be an object")
    catalogue.exact_keys(value, CONTEXT_ROOT_KEYS, "terminology context root")
    if (
        value["schema"] != CONTEXT_SCHEMA
        or not isinstance(value["id"], str)
        or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", value["id"])
        or not isinstance(value["productContext"], str)
        or not value["productContext"].strip()
        or not isinstance(value["instruction"], str)
        or not value["instruction"].strip()
        or value["license"] != "Apache-2.0"
        or not isinstance(value["notice"], str)
        or not value["notice"].strip()
        or not isinstance(value["sources"], list)
        or not value["sources"]
        or not isinstance(value["terms"], list)
        or not value["terms"]
    ):
        raise DeepLError("malformed terminology context")

    source_ids: list[str] = []
    for source in value["sources"]:
        if not isinstance(source, dict):
            raise DeepLError("malformed terminology context source")
        catalogue.exact_keys(source, CONTEXT_SOURCE_KEYS, "terminology context source")
        if (
            not isinstance(source["id"], str)
            or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", source["id"])
            or not isinstance(source["repository"], str)
            or not source["repository"].startswith("https://github.com/home-assistant/")
            or not isinstance(source["revision"], str)
            or not catalogue.REV_RE.fullmatch(source["revision"])
            or not isinstance(source["artifact"], str)
            or not source["artifact"].strip()
            or not isinstance(source["artifactSha256"], str)
            or not catalogue.SHA_RE.fullmatch(source["artifactSha256"])
            or source["license"] != "Apache-2.0"
        ):
            raise DeepLError("malformed terminology context source value")
        source_ids.append(source["id"])
    if source_ids != sorted(source_ids) or len(source_ids) != len(set(source_ids)):
        raise DeepLError("terminology context sources are not unique canonical ids")

    term_ids: list[str] = []
    sources = set(source_ids)
    for term in value["terms"]:
        if not isinstance(term, dict):
            raise DeepLError("malformed terminology context term")
        catalogue.exact_keys(term, CONTEXT_TERM_KEYS, "terminology context term")
        translations = term["translations"]
        if (
            not isinstance(term["id"], str)
            or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", term["id"])
            or not isinstance(term["meaning"], str)
            or not term["meaning"].strip()
            or not isinstance(term["english"], str)
            or not term["english"].strip()
            or term["source"] not in sources
            or not isinstance(term["sourceKey"], str)
            or not term["sourceKey"].strip()
            or not isinstance(translations, dict)
            or set(translations) != catalogue.LOCALES
            or any(not isinstance(text, str) or not text.strip() for text in translations.values())
        ):
            raise DeepLError("malformed terminology context term value")
        if list(translations) != sorted(translations):
            raise DeepLError("terminology context translations are not in canonical locale order")
        term_ids.append(term["id"])
    if term_ids != sorted(term_ids) or len(term_ids) != len(set(term_ids)):
        raise DeepLError("terminology context terms are not unique canonical ids")
    return value, hashlib.sha256(raw).hexdigest(), len(raw)


def _target_catalogue(path: Path, locale: str, source: dict[str, Any]) -> dict[str, Any]:
    if not path.is_file():
        raise DeepLError(f"{locale}: base target catalogue is missing")
    return catalogue.validate_target(path, source, expected_locale=locale)


def _selected_record(key: str, record: dict[str, Any], source: dict[str, Any]) -> dict[str, Any]:
    selected = {
        "key": key,
        "english": record["text"],
        "sourceHash": record["sourceHash"],
        "surface": record["surface"],
        "context": record["context"],
        "risk": record["risk"],
        "siblings": [
            {"key": sibling, "english": source["strings"][sibling]["text"]}
            for sibling in record["siblings"]
        ],
        "placeholders": record["placeholders"],
        "frozen": record["frozen"],
        "softMaxChars": record["softMaxChars"],
        "hardMaxChars": record["hardMaxChars"],
        "priorTarget": None,
    }
    selected["maximumBilledCharacters"] = len(_protected_xml(selected)[0])
    return selected


def build_plan(
    source_path: Path,
    target_dir: Path,
    context_path: Path,
    locales: list[str],
    base_revision: str,
    reconsider: set[str],
) -> dict[str, Any]:
    if not catalogue.REV_RE.fullmatch(base_revision):
        raise DeepLError("base revision must be a full lowercase Git SHA")
    if not locales or len(locales) != len(set(locales)) or any(locale not in TARGETS for locale in locales):
        raise DeepLError("locales must be a non-empty, duplicate-free supported locale list")
    source = catalogue.validate_source(source_path)
    context, context_hash, context_bytes = _load_context(context_path)
    unknown = reconsider - set(source["strings"])
    if unknown:
        raise DeepLError(f"unknown reconsidered keys: {', '.join(sorted(unknown))}")

    batches: list[dict[str, Any]] = []
    requested = 0
    for locale in locales:
        target_path = target_dir / f"{locale}.json"
        target_root = _target_catalogue(target_path, locale, source)
        target = target_root["strings"]
        selected: list[dict[str, Any]] = []
        for key, source_record in source["strings"].items():
            current = target.get(key)
            stale = current is None or current["sourceHash"] != source_record["sourceHash"]
            fallback = (
                current is not None
                and current["state"] == "english-fallback"
                and not catalogue.held_for_review(locale, current, source_record)
            )
            explicit = key in reconsider
            if not (stale or fallback or explicit):
                continue
            if (
                current is not None
                and current["state"] == "community-corrected"
                and current["sourceHash"] == source_record["sourceHash"]
            ):
                raise DeepLError(f"{locale}.{key}: refusing to reconsider a current community correction")
            selected_record = _selected_record(key, source_record, source)
            if current is not None:
                selected_record["priorTarget"] = {
                    "text": current["text"],
                    "sourceHash": current["sourceHash"],
                    "state": current["state"],
                }
            selected.append(selected_record)
            requested += len(source_record["text"])
        batches.append({
            "locale": locale,
            "baseTargetHash": _source_digest(target_path),
            "records": selected,
        })

    return {
        "schema": PLAN_SCHEMA,
        "baseRevision": base_revision,
        "sourceRevision": source["sourceRevision"],
        "sourceCatalogueHash": _source_digest(source_path),
        "contextArtifactId": context["id"],
        "contextArtifactHash": context_hash,
        "contextArtifactBytes": context_bytes,
        "reconsideredKeys": sorted(reconsider),
        "requestedCharacters": requested,
        "maximumBilledCharacters": sum(
            record["maximumBilledCharacters"]
            for batch in batches
            for record in batch["records"]
        ),
        "batches": batches,
    }


def _protected_xml(record: dict[str, Any]) -> tuple[str, dict[str, str]]:
    tokens = list(record["placeholders"]) + list(record["frozen"])
    if not tokens:
        return html.escape(record["english"], quote=False), {}
    if any(token not in record["english"] for token in set(tokens)):
        raise DeepLError(f"{record['key']}: protected-token metadata does not match English")
    alternatives = "|".join(re.escape(token) for token in sorted(set(tokens), key=len, reverse=True))
    matches = list(re.finditer(alternatives, record["english"]))
    protected: dict[str, str] = {}
    chunks: list[str] = []
    cursor = 0
    for match in matches:
        chunks.append(html.escape(record["english"][cursor:match.start()], quote=False))
        token = match.group(0)
        identifier = str(len(protected))
        protected[identifier] = token
        chunks.append(f'<x id="{identifier}">{html.escape(token, quote=False)}</x>')
        cursor = match.end()
    chunks.append(html.escape(record["english"][cursor:], quote=False))
    return "".join(chunks), protected


def _restore_xml(value: str, protected: dict[str, str], key: str) -> str:
    try:
        root = ElementTree.fromstring(f"<root>{value}</root>")
    except ElementTree.ParseError as error:
        raise DeepLError(f"{key}: malformed translated XML") from error
    result = root.text or ""
    seen: set[str] = set()
    for child in root:
        if child.tag != "x" or set(child.attrib) != {"id"} or list(child):
            raise DeepLError(f"{key}: unexpected translated XML structure")
        identifier = child.attrib["id"]
        if identifier not in protected or identifier in seen or (child.text or "") != protected[identifier]:
            raise DeepLError(f"{key}: changed protected token")
        seen.add(identifier)
        result += protected[identifier]
        result += child.tail or ""
    if seen != set(protected):
        raise DeepLError(f"{key}: missing protected token")
    return result


def _default_http(request: urllib.request.Request) -> bytes:
    try:
        opener = urllib.request.build_opener(_NoRedirect())
        with opener.open(request, timeout=45) as response:
            if response.status != 200:
                raise DeepLError("translation service returned an unexpected status")
            value = response.read(MAX_RESPONSE_BYTES + 1)
            if len(value) > MAX_RESPONSE_BYTES:
                raise DeepLError("translation service response is too large")
            return value
    except urllib.error.HTTPError as error:
        # The status (403 key, 456 quota, 429 rate) is the only clue a CI log can show; never the body.
        raise DeepLError(f"translation service rejected the request (HTTP {error.code})") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        endpoint = request.full_url.removeprefix(API_ORIGIN)
        if endpoint == "/v2/translate":
            raise DeepLError("translation request outcome is ambiguous; not retrying") from None
        raise DeepLError("usage request failed") from None


def _request_json(
    endpoint: str,
    key: str,
    http: HTTP,
    body: dict[str, Any] | None = None,
) -> Any:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    if data is not None and len(data) > MAX_REQUEST_BYTES:
        raise DeepLError(f"{endpoint}: request body is too large")
    headers = {
        "Authorization": f"DeepL-Auth-Key {key}",
        "User-Agent": "ha-paneld-i18n-candidates/1",
    }
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(API_ORIGIN + endpoint, data=data, headers=headers, method="GET" if data is None else "POST")
    raw = http(request)
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_json_object)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise DeepLError(f"{endpoint}: malformed JSON response") from error
    return value


def _usage(key: str, http: HTTP) -> tuple[int, int]:
    value = _request_json("/v2/usage", key, http)
    if not isinstance(value, dict):
        raise DeepLError("usage response root must be an object")
    count, limit = value.get("character_count"), value.get("character_limit")
    if (
        isinstance(count, bool)
        or isinstance(limit, bool)
        or not isinstance(count, int)
        or not isinstance(limit, int)
        or count < 0
        or limit <= 0
        or count > limit
    ):
        raise DeepLError("usage response has invalid character_count or character_limit")
    return count, limit


def _check_capabilities(locales: list[str], key: str, http: HTTP) -> None:
    value = _request_json("/v2/languages?type=target", key, http)
    if not isinstance(value, list):
        raise DeepLError("target-language response root must be an array")
    capabilities: dict[str, bool] = {}
    for item in value:
        if (
            not isinstance(item, dict)
            or set(item) != {"language", "name", "supports_formality"}
            or not isinstance(item["language"], str)
            or not item["language"]
            or not isinstance(item["name"], str)
            or not item["name"]
            or not isinstance(item["supports_formality"], bool)
            or item["language"] in capabilities
        ):
            raise DeepLError("malformed or duplicate target-language capability")
        capabilities[item["language"]] = item["supports_formality"]
    for locale in locales:
        target, formality = TARGETS[locale]
        if target not in capabilities:
            raise DeepLError(f"{locale}: configured target language is unavailable")
        if formality in {"more", "less"} and not capabilities[target]:
            raise DeepLError(f"{locale}: configured formality is unsupported")


def _translation_context(record: dict[str, Any], locale: str, context: dict[str, Any]) -> str:
    lines = [record["context"]]
    sibling_text = "\n".join(item["english"] for item in record["siblings"])
    if sibling_text:
        lines.extend(["Related strings on the same interface:", sibling_text])
    lines.extend([context["productContext"], "Pinned Home Assistant terminology for this locale:"])
    lines.extend(
        f"{term['english']} = {term['translations'][locale]} ({term['meaning']})"
        for term in context["terms"]
        if locale in term["translations"]
    )
    return "\n".join(lines)


def _supports_custom_instructions(target_lang: str) -> bool:
    if not isinstance(target_lang, str) or not TARGET_LANGUAGE_RE.fullmatch(target_lang):
        raise DeepLError("configured target language is not canonical")
    return target_lang.partition("-")[0] in CUSTOM_INSTRUCTION_LANGUAGE_FAMILIES


def _translation_request_body(
    record: dict[str, Any],
    locale: str,
    context: dict[str, Any],
    protected_text: str,
) -> dict[str, Any]:
    target_lang, formality = TARGETS[locale]
    body = {
        "text": [protected_text],
        "source_lang": "EN",
        "target_lang": target_lang,
        "context": _translation_context(record, locale, context),
        "show_billed_characters": True,
        "formality": formality,
        "model_type": "quality_optimized",
    }
    if _supports_custom_instructions(target_lang):
        body["custom_instructions"] = [
            "Use concise software settings UI language. Preserve meaning; do not add actions, warnings, or guarantees.",
            context["instruction"],
        ]
    body.update({
        "tag_handling": "xml",
        "tag_handling_version": "v2",
        "ignore_tags": ["x"],
        "preserve_formatting": True,
    })
    return body


def _translate(
    record: dict[str, Any],
    locale: str,
    context: dict[str, Any],
    key: str,
    http: HTTP,
) -> tuple[str, int]:
    protected_text, protected = _protected_xml(record)
    response = _request_json(
        "/v2/translate",
        key,
        http,
        _translation_request_body(record, locale, context, protected_text),
    )
    if not isinstance(response, dict):
        raise DeepLError(f"{record['key']}: translation response root must be an object")
    translations = response.get("translations")
    if not isinstance(translations, list) or len(translations) != 1 or not isinstance(translations[0], dict):
        raise DeepLError(f"{record['key']}: malformed translation response")
    translated = translations[0].get("text")
    billed = translations[0].get("billed_characters")
    if not isinstance(translated, str) or not translated:
        raise DeepLError(f"{record['key']}: empty translation response")
    if (
        isinstance(billed, bool)
        or not isinstance(billed, int)
        or billed < 0
        or billed > record["maximumBilledCharacters"]
    ):
        raise DeepLError(f"{record['key']}: invalid or excessive billed_characters")
    return _restore_xml(translated, protected, record["key"]), billed


def _validate_plan(plan: dict[str, Any]) -> None:
    catalogue.exact_keys(
        plan,
        {
            "schema", "baseRevision", "sourceRevision", "sourceCatalogueHash", "requestedCharacters",
            "contextArtifactId", "contextArtifactHash", "contextArtifactBytes",
            "reconsideredKeys", "maximumBilledCharacters", "batches",
        },
        "plan root",
    )
    if (
        plan["schema"] != PLAN_SCHEMA
        or not isinstance(plan["baseRevision"], str)
        or not catalogue.REV_RE.fullmatch(plan["baseRevision"])
        or not isinstance(plan["sourceRevision"], str)
        or not catalogue.REV_RE.fullmatch(plan["sourceRevision"])
        or not isinstance(plan["sourceCatalogueHash"], str)
        or not catalogue.SHA_RE.fullmatch(plan["sourceCatalogueHash"])
        or not isinstance(plan["contextArtifactId"], str)
        or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", plan["contextArtifactId"])
        or not isinstance(plan["contextArtifactHash"], str)
        or not catalogue.SHA_RE.fullmatch(plan["contextArtifactHash"])
        or isinstance(plan["contextArtifactBytes"], bool)
        or not isinstance(plan["contextArtifactBytes"], int)
        or plan["contextArtifactBytes"] <= 0
        or not isinstance(plan["reconsideredKeys"], list)
        or any(
            not isinstance(key, str) or not catalogue.KEY_RE.fullmatch(key)
            for key in plan["reconsideredKeys"]
        )
        or plan["reconsideredKeys"] != sorted(set(plan["reconsideredKeys"]))
        or isinstance(plan["requestedCharacters"], bool)
        or not isinstance(plan["requestedCharacters"], int)
        or plan["requestedCharacters"] < 0
        or isinstance(plan["maximumBilledCharacters"], bool)
        or not isinstance(plan["maximumBilledCharacters"], int)
        or plan["maximumBilledCharacters"] < plan["requestedCharacters"]
        or not isinstance(plan["batches"], list)
    ):
        raise DeepLError("malformed plan")
    locales: set[str] = set()
    calculated = 0
    for batch in plan["batches"]:
        if not isinstance(batch, dict) or set(batch) != {"locale", "baseTargetHash", "records"}:
            raise DeepLError("malformed plan batch")
        locale, records = batch["locale"], batch["records"]
        if (
            locale not in TARGETS
            or locale in locales
            or not isinstance(batch["baseTargetHash"], str)
            or not catalogue.SHA_RE.fullmatch(batch["baseTargetHash"])
            or not isinstance(records, list)
        ):
            raise DeepLError("malformed plan locale batch")
        locales.add(locale)
        keys: list[str] = []
        for record in records:
            if not isinstance(record, dict) or set(record) != {
                "key", "english", "sourceHash", "surface", "context", "risk", "siblings",
                "placeholders", "frozen", "softMaxChars", "hardMaxChars", "priorTarget",
                "maximumBilledCharacters",
            }:
                raise DeepLError("malformed plan record")
            if (
                not isinstance(record["key"], str)
                or not catalogue.KEY_RE.fullmatch(record["key"])
                or not isinstance(record["english"], str)
                or not record["english"]
                or not isinstance(record["sourceHash"], str)
                or record["sourceHash"] != catalogue.source_hash(record["english"])
                or record["surface"] not in catalogue.SOURCE_SURFACES
                or not isinstance(record["context"], str)
                or not record["context"].strip()
                or record["risk"] not in {"ordinary", "setup", "consequential"}
                or not isinstance(record["siblings"], list)
                or not isinstance(record["placeholders"], list)
                or not all(isinstance(value, str) and value for value in record["placeholders"])
                or not isinstance(record["frozen"], list)
                or not all(isinstance(value, str) and value for value in record["frozen"])
                or len(record["frozen"]) != len(set(record["frozen"]))
                or isinstance(record["softMaxChars"], bool)
                or not isinstance(record["softMaxChars"], int)
                or isinstance(record["hardMaxChars"], bool)
                or not isinstance(record["hardMaxChars"], int)
                or record["softMaxChars"] <= 0
                or record["hardMaxChars"] < record["softMaxChars"]
                or isinstance(record["maximumBilledCharacters"], bool)
                or not isinstance(record["maximumBilledCharacters"], int)
                or record["maximumBilledCharacters"] < len(record["english"])
            ):
                raise DeepLError("malformed plan record value")
            prior = record["priorTarget"]
            if prior is not None and (
                not isinstance(prior, dict)
                or set(prior) != {"text", "sourceHash", "state"}
                or not isinstance(prior["text"], str)
                or not prior["text"]
                or not isinstance(prior["sourceHash"], str)
                or not catalogue.SHA_RE.fullmatch(prior["sourceHash"])
                or prior["state"] not in catalogue.STATES
            ):
                raise DeepLError("malformed prior target review context")
            for sibling in record["siblings"]:
                if (
                    not isinstance(sibling, dict)
                    or set(sibling) != {"key", "english"}
                    or not isinstance(sibling["key"], str)
                    or not catalogue.KEY_RE.fullmatch(sibling["key"])
                    or not isinstance(sibling["english"], str)
                    or not sibling["english"]
                ):
                    raise DeepLError("malformed plan sibling")
            keys.append(record["key"])
            calculated += len(record["english"])
            if record["maximumBilledCharacters"] != len(_protected_xml(record)[0]):
                raise DeepLError("plan maximumBilledCharacters mismatch")
        if keys != sorted(keys) or len(keys) != len(set(keys)):
            raise DeepLError(f"{locale}: plan records are not unique canonical keys")
    if calculated != plan["requestedCharacters"]:
        raise DeepLError("plan requestedCharacters mismatch")
    calculated_maximum = sum(
        record["maximumBilledCharacters"]
        for batch in plan["batches"]
        for record in batch["records"]
    )
    if calculated_maximum != plan["maximumBilledCharacters"]:
        raise DeepLError("plan maximumBilledCharacters mismatch")


def _validate_plan_inputs(
    plan: dict[str, Any],
    source_path: Path,
    target_dir: Path,
    context_path: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    _validate_plan(plan)
    source = catalogue.validate_source(source_path)
    if (
        source["sourceRevision"] != plan["sourceRevision"]
        or _source_digest(source_path) != plan["sourceCatalogueHash"]
    ):
        raise DeepLError("source catalogue drifted after the plan was created")
    context, context_hash, context_bytes = _load_context(context_path)
    if (
        context["id"] != plan["contextArtifactId"]
        or context_hash != plan["contextArtifactHash"]
        or context_bytes != plan["contextArtifactBytes"]
    ):
        raise DeepLError("terminology context drifted after the plan was created")
    for batch in plan["batches"]:
        locale = batch["locale"]
        target_path = target_dir / f"{locale}.json"
        _target_catalogue(target_path, locale, source)
        if _source_digest(target_path) != batch["baseTargetHash"]:
            raise DeepLError(f"{locale}: base target drifted after the plan was created")
    rebuilt = build_plan(
        source_path,
        target_dir,
        context_path,
        [batch["locale"] for batch in plan["batches"]],
        plan["baseRevision"],
        set(plan["reconsideredKeys"]),
    )
    if rebuilt != plan:
        raise DeepLError("plan does not match the exact source and base target inputs")
    return source, context


def _validate_run(
    run: dict[str, Any],
    plan: dict[str, Any],
    expected_plan_hash: str | None = None,
) -> None:
    catalogue.exact_keys(
        run,
        {
            "schema", "status", "baseRevision", "planHash", "requestedCharacters", "selectedRecords",
            "maximumBilledCharacters", "billedCharacters", "accountUsageBefore", "accountUsageAfter",
            "accountCharacterLimit", "contextArtifactId", "contextArtifactHash",
            "contextArtifactBytes", "resultHashes",
        },
        "run root",
    )
    if (
        run["schema"] != RUN_SCHEMA
        or run["status"] not in {"no-changes", "skipped-quota", "generated"}
        or run["baseRevision"] != plan["baseRevision"]
        or not isinstance(run["planHash"], str)
        or not catalogue.SHA_RE.fullmatch(run["planHash"])
        or run["contextArtifactId"] != plan["contextArtifactId"]
        or run["contextArtifactHash"] != plan["contextArtifactHash"]
        or run["contextArtifactBytes"] != plan["contextArtifactBytes"]
        or run["requestedCharacters"] != plan["requestedCharacters"]
        or run["maximumBilledCharacters"] != plan["maximumBilledCharacters"]
        or isinstance(run["billedCharacters"], bool)
        or not isinstance(run["billedCharacters"], int)
        or run["billedCharacters"] < 0
        or run["billedCharacters"] > plan["maximumBilledCharacters"]
        or isinstance(run["selectedRecords"], bool)
        or not isinstance(run["selectedRecords"], int)
        or run["selectedRecords"] < 0
        or not isinstance(run["resultHashes"], dict)
        or any(locale not in TARGETS for locale in run["resultHashes"])
        or any(not isinstance(value, str) or not catalogue.SHA_RE.fullmatch(value) for value in run["resultHashes"].values())
    ):
        raise DeepLError("malformed run result")
    selected = sum(len(batch["records"]) for batch in plan["batches"])
    if run["selectedRecords"] != selected:
        raise DeepLError("run selectedRecords mismatch")
    if expected_plan_hash is not None and run["planHash"] != expected_plan_hash:
        raise DeepLError("run plan hash mismatch")
    if run["status"] != "generated" and run["billedCharacters"] != 0:
        raise DeepLError("non-generated run reports billed characters")
    account_values = (
        run["accountUsageBefore"], run["accountUsageAfter"], run["accountCharacterLimit"],
    )
    if run["status"] == "no-changes":
        if account_values != (None, None, None) or run["resultHashes"]:
            raise DeepLError("no-change run has unexpected account usage or result hashes")
    else:
        before, after, limit = account_values
        if (
            isinstance(before, bool)
            or isinstance(after, bool)
            or isinstance(limit, bool)
            or not isinstance(before, int)
            or not isinstance(after, int)
            or not isinstance(limit, int)
            or before < 0
            or after < 0
            or limit <= 0
            or before > limit
            or after > limit
        ):
            raise DeepLError("run account usage is malformed")
        if run["status"] == "generated" and (
            after < before or after - before < run["billedCharacters"]
        ):
            raise DeepLError("generated run account usage is inconsistent")
    expected_hashes = {
        batch["locale"] for batch in plan["batches"] if batch["records"]
    } if run["status"] == "generated" else set()
    if set(run["resultHashes"]) != expected_hashes:
        raise DeepLError("run result hash coverage mismatch")


def generate(
    plan_path: Path,
    source_path: Path,
    target_dir: Path,
    context_path: Path,
    output_dir: Path,
    api_key: str,
    http: HTTP = _default_http,
) -> dict[str, Any]:
    plan = catalogue.read_json(plan_path)
    _, context = _validate_plan_inputs(plan, source_path, target_dir, context_path)
    plan_hash = _source_digest(plan_path)
    if output_dir.exists():
        raise DeepLError("output directory already exists")

    selected_count = sum(len(batch["records"]) for batch in plan["batches"])
    if selected_count == 0:
        result = {
            "schema": RUN_SCHEMA,
            "status": "no-changes",
            "baseRevision": plan["baseRevision"],
            "planHash": plan_hash,
            "requestedCharacters": 0,
            "maximumBilledCharacters": 0,
            "billedCharacters": 0,
            "selectedRecords": 0,
            "contextArtifactId": plan["contextArtifactId"],
            "contextArtifactHash": plan["contextArtifactHash"],
            "contextArtifactBytes": plan["contextArtifactBytes"],
            "accountUsageBefore": None,
            "accountUsageAfter": None,
            "accountCharacterLimit": None,
            "resultHashes": {},
        }
        output_dir.mkdir(parents=True)
        _write_json(output_dir / "run.json", result)
        return result

    if not api_key or any(character.isspace() for character in api_key):
        raise DeepLError("DEEPL_API_KEY is missing or malformed")
    active_locales = [batch["locale"] for batch in plan["batches"] if batch["records"]]
    _check_capabilities(active_locales, api_key, http)
    before, limit = _usage(api_key, http)
    if before + plan["maximumBilledCharacters"] > limit - QUOTA_RESERVE:
        result = {
            "schema": RUN_SCHEMA,
            "status": "skipped-quota",
            "baseRevision": plan["baseRevision"],
            "planHash": plan_hash,
            "requestedCharacters": plan["requestedCharacters"],
            "maximumBilledCharacters": plan["maximumBilledCharacters"],
            "billedCharacters": 0,
            "selectedRecords": selected_count,
            "contextArtifactId": plan["contextArtifactId"],
            "contextArtifactHash": plan["contextArtifactHash"],
            "contextArtifactBytes": plan["contextArtifactBytes"],
            "accountUsageBefore": before,
            "accountUsageAfter": before,
            "accountCharacterLimit": limit,
            "resultHashes": {},
        }
        output_dir.mkdir(parents=True)
        _write_json(output_dir / "run.json", result)
        return result

    temporary = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.", dir=output_dir.parent))
    try:
        candidate_dir = temporary / "candidates"
        candidate_dir.mkdir()
        hashes: dict[str, str] = {}
        billed_characters = 0
        for batch in plan["batches"]:
            if not batch["records"]:
                continue
            locale = batch["locale"]
            translations = []
            for record in batch["records"]:
                translation, billed = _translate(record, locale, context, api_key, http)
                billed_characters += billed
                translations.append({"key": record["key"], "translation": translation})
            if billed_characters > plan["maximumBilledCharacters"]:
                raise DeepLError("run billed characters exceed the predeclared bound")
            candidate = {
                "schema": catalogue.SCHEMA,
                "targetLocale": locale,
                "sourceRevision": plan["sourceRevision"],
                "sourceCatalogueHash": plan["sourceCatalogueHash"],
                "translations": translations,
            }
            candidate_path = candidate_dir / f"{locale}.json"
            _write_json(candidate_path, candidate)
            hashes[locale] = _source_digest(candidate_path)
        after, after_limit = _usage(api_key, http)
        if (
            after_limit != limit
            or after > limit
            or after < before
            or after - before < billed_characters
        ):
            raise DeepLError("postflight usage response is inconsistent")
        result = {
            "schema": RUN_SCHEMA,
            "status": "generated",
            "baseRevision": plan["baseRevision"],
            "planHash": plan_hash,
            "requestedCharacters": plan["requestedCharacters"],
            "maximumBilledCharacters": plan["maximumBilledCharacters"],
            "billedCharacters": billed_characters,
            "selectedRecords": selected_count,
            "contextArtifactId": plan["contextArtifactId"],
            "contextArtifactHash": plan["contextArtifactHash"],
            "contextArtifactBytes": plan["contextArtifactBytes"],
            "accountUsageBefore": before,
            "accountUsageAfter": after,
            "accountCharacterLimit": limit,
            "resultHashes": hashes,
        }
        _write_json(temporary / "run.json", result)
        os.replace(temporary, output_dir)
        return result
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def _validate_hash_map(value: Any, owner: str, expected: set[str]) -> None:
    if (
        not isinstance(value, dict)
        or set(value) != expected
        or list(value) != sorted(value)
        or any(locale not in TARGETS for locale in value)
        or any(
            not isinstance(digest, str) or not catalogue.SHA_RE.fullmatch(digest)
            for digest in value.values()
        )
    ):
        raise DeepLError(f"malformed {owner}")


def _validate_receipt(receipt: dict[str, Any], plan: dict[str, Any]) -> None:
    catalogue.exact_keys(receipt, RECEIPT_KEYS, "public receipt root")
    active = {
        batch["locale"] for batch in plan["batches"] if batch["records"]
    }
    all_locales = {batch["locale"] for batch in plan["batches"]}
    if (
        receipt["schema"] != RECEIPT_SCHEMA
        or receipt["status"] != "generated"
        or receipt["baseRevision"] != plan["baseRevision"]
        or not isinstance(receipt["planHash"], str)
        or not catalogue.SHA_RE.fullmatch(receipt["planHash"])
        or receipt["sourceCatalogueHash"] != plan["sourceCatalogueHash"]
        or receipt["contextArtifactId"] != plan["contextArtifactId"]
        or receipt["contextArtifactHash"] != plan["contextArtifactHash"]
        or receipt["contextArtifactBytes"] != plan["contextArtifactBytes"]
        or receipt["requestedCharacters"] != plan["requestedCharacters"]
        or receipt["maximumBilledCharacters"] != plan["maximumBilledCharacters"]
        or isinstance(receipt["billedCharacters"], bool)
        or not isinstance(receipt["billedCharacters"], int)
        or receipt["billedCharacters"] < 0
        or receipt["billedCharacters"] > plan["maximumBilledCharacters"]
        or isinstance(receipt["selectedRecords"], bool)
        or not isinstance(receipt["selectedRecords"], int)
        or receipt["selectedRecords"] != sum(len(batch["records"]) for batch in plan["batches"])
    ):
        raise DeepLError("malformed public receipt")
    _validate_hash_map(receipt["baseTargetHashes"], "receipt base target hashes", all_locales)
    _validate_hash_map(receipt["providerCandidateHashes"], "receipt provider candidate hashes", active)
    _validate_hash_map(receipt["catalogueHashes"], "receipt catalogue hashes", active)
    if receipt["baseTargetHashes"] != {
        batch["locale"]: batch["baseTargetHash"] for batch in plan["batches"]
    }:
        raise DeepLError("receipt base target hashes do not match the plan")


def validate_bundle(bundle_dir: Path, source_path: Path) -> dict[str, Any]:
    if not bundle_dir.is_dir() or bundle_dir.is_symlink():
        raise DeepLError("candidate bundle is not a directory")
    plan_path = bundle_dir / "plan.json"
    receipt_path = bundle_dir / "receipt.json"
    if (
        not plan_path.is_file()
        or plan_path.is_symlink()
        or not receipt_path.is_file()
        or receipt_path.is_symlink()
    ):
        raise DeepLError("candidate bundle plan or receipt is not a regular file")
    plan = catalogue.read_json(plan_path)
    _validate_plan(plan)
    receipt = catalogue.read_json(receipt_path)
    _validate_receipt(receipt, plan)
    if _source_digest(plan_path) != receipt["planHash"]:
        raise DeepLError("candidate bundle plan hash mismatch")
    source = catalogue.validate_source(source_path)
    if (
        source["sourceRevision"] != plan["sourceRevision"]
        or _source_digest(source_path) != plan["sourceCatalogueHash"]
    ):
        raise DeepLError("candidate bundle source catalogue mismatch")
    expected_files = {
        "plan.json", "receipt.json",
        *(f"{locale}.json" for locale in receipt["catalogueHashes"]),
    }
    actual_files = {entry.name for entry in bundle_dir.iterdir()}
    if actual_files != expected_files:
        raise DeepLError("candidate bundle file coverage mismatch")
    for locale, expected_hash in receipt["catalogueHashes"].items():
        target_path = bundle_dir / f"{locale}.json"
        if not target_path.is_file() or target_path.is_symlink():
            raise DeepLError(f"{locale}: bundled catalogue is not a regular file")
        if _source_digest(target_path) != expected_hash:
            raise DeepLError(f"{locale}: bundled catalogue hash mismatch")
        catalogue.validate_target(target_path, source, expected_locale=locale)
    return receipt


def build_bundle(
    source_path: Path,
    target_dir: Path,
    context_path: Path,
    plan_path: Path,
    run_path: Path,
    candidate_dir: Path,
    output_dir: Path,
) -> dict[str, Any]:
    plan = catalogue.read_json(plan_path)
    source, _ = _validate_plan_inputs(plan, source_path, target_dir, context_path)
    run = catalogue.read_json(run_path)
    _validate_run(run, plan, _source_digest(plan_path))
    if run["status"] != "generated":
        raise DeepLError("only a generated run can produce a candidate bundle")
    if output_dir.exists():
        raise DeepLError("candidate bundle output already exists")
    if not candidate_dir.is_dir() or candidate_dir.is_symlink():
        raise DeepLError("provider candidate input is not a directory")

    active_batches = [batch for batch in plan["batches"] if batch["records"]]
    expected_candidates = {f"{batch['locale']}.json" for batch in active_batches}
    if {entry.name for entry in candidate_dir.iterdir()} != expected_candidates:
        raise DeepLError("provider candidate file coverage mismatch")

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.", dir=output_dir.parent))
    try:
        catalogue_hashes: dict[str, str] = {}
        for batch in active_batches:
            locale = batch["locale"]
            candidate_path = candidate_dir / f"{locale}.json"
            if not candidate_path.is_file() or candidate_path.is_symlink():
                raise DeepLError(f"{locale}: provider candidate is not a regular file")
            if _source_digest(candidate_path) != run["resultHashes"][locale]:
                raise DeepLError(f"{locale}: provider candidate hash mismatch")
            candidate = catalogue.read_json(candidate_path)
            translations = candidate.get("translations")
            if not isinstance(translations, list) or [
                item.get("key") if isinstance(item, dict) else None for item in translations
            ] != [record["key"] for record in batch["records"]]:
                raise DeepLError(f"{locale}: provider candidate does not match plan selection")
            target_path = temporary / f"{locale}.json"
            catalogue.merge_candidate(
                source_path,
                target_dir / f"{locale}.json",
                candidate_path,
                target_path,
            )
            catalogue.validate_target(target_path, source, expected_locale=locale)
            catalogue_hashes[locale] = _source_digest(target_path)

        shutil.copyfile(plan_path, temporary / "plan.json")
        receipt = {
            "schema": RECEIPT_SCHEMA,
            "status": "generated",
            "baseRevision": plan["baseRevision"],
            "planHash": run["planHash"],
            "sourceCatalogueHash": plan["sourceCatalogueHash"],
            "contextArtifactId": plan["contextArtifactId"],
            "contextArtifactHash": plan["contextArtifactHash"],
            "contextArtifactBytes": plan["contextArtifactBytes"],
            "requestedCharacters": plan["requestedCharacters"],
            "maximumBilledCharacters": plan["maximumBilledCharacters"],
            "billedCharacters": run["billedCharacters"],
            "selectedRecords": run["selectedRecords"],
            "baseTargetHashes": dict(sorted(
                (batch["locale"], batch["baseTargetHash"]) for batch in plan["batches"]
            )),
            "providerCandidateHashes": dict(sorted(run["resultHashes"].items())),
            "catalogueHashes": dict(sorted(catalogue_hashes.items())),
        }
        _write_json(temporary / "receipt.json", receipt)
        validate_bundle(temporary, source_path)
        os.replace(temporary, output_dir)
        return receipt
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def check_base(expected: str, repository: Path, remote_ref: str) -> None:
    if not catalogue.REV_RE.fullmatch(expected):
        raise DeepLError("expected base must be a full lowercase Git SHA")
    try:
        actual = subprocess.run(
            ["git", "rev-parse", remote_ref], cwd=repository, check=True, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        ).stdout.strip()
    except subprocess.CalledProcessError as error:
        raise DeepLError("could not resolve current trusted base") from error
    if actual != expected:
        raise DeepLError(f"stale base: expected {expected}, current trusted base is {actual}")


def summary(plan_path: Path, run_path: Path) -> str:
    plan = catalogue.read_json(plan_path)
    _validate_plan(plan)
    run = catalogue.read_json(run_path)
    _validate_run(run, plan, _source_digest(plan_path))
    locales = ", ".join(batch["locale"] for batch in plan["batches"] if batch["records"]) or "none"
    return "\n".join([
        "## Translation candidate run",
        "",
        f"- Status: `{run.get('status', 'invalid')}`",
        f"- Trusted base: `{plan['baseRevision']}`",
        f"- Terminology context: `{plan['contextArtifactId']}` (`{plan['contextArtifactHash']}`, {plan['contextArtifactBytes']} bytes)",
        f"- Locales with selected records: {locales}",
        f"- Selected records: {run.get('selectedRecords', 'invalid')}",
        f"- Requested source characters: {run.get('requestedCharacters', 'invalid')}",
        f"- Run billed characters: {run.get('billedCharacters', 'invalid')} (bound: {run.get('maximumBilledCharacters', 'invalid')})",
    ])


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan")
    plan.add_argument("--source", type=Path, required=True)
    plan.add_argument("--target-dir", type=Path, required=True)
    plan.add_argument("--context", type=Path, required=True)
    plan.add_argument("--locale", action="append", required=True)
    plan.add_argument("--base-revision", required=True)
    plan.add_argument("--reconsider", action="append", default=[])
    plan.add_argument("--output", type=Path, required=True)
    create = commands.add_parser("generate")
    create.add_argument("--plan", type=Path, required=True)
    create.add_argument("--source", type=Path, required=True)
    create.add_argument("--target-dir", type=Path, required=True)
    create.add_argument("--context", type=Path, required=True)
    create.add_argument("--output-dir", type=Path, required=True)
    inputs = commands.add_parser("validate-inputs")
    inputs.add_argument("--plan", type=Path, required=True)
    inputs.add_argument("--source", type=Path, required=True)
    inputs.add_argument("--target-dir", type=Path, required=True)
    inputs.add_argument("--context", type=Path, required=True)
    bundle = commands.add_parser("bundle")
    bundle.add_argument("--source", type=Path, required=True)
    bundle.add_argument("--target-dir", type=Path, required=True)
    bundle.add_argument("--context", type=Path, required=True)
    bundle.add_argument("--plan", type=Path, required=True)
    bundle.add_argument("--run", type=Path, required=True)
    bundle.add_argument("--candidate-dir", type=Path, required=True)
    bundle.add_argument("--output-dir", type=Path, required=True)
    verify_bundle = commands.add_parser("validate-bundle")
    verify_bundle.add_argument("--source", type=Path, required=True)
    verify_bundle.add_argument("--bundle-dir", type=Path, required=True)
    base = commands.add_parser("check-base")
    base.add_argument("--expected", required=True)
    base.add_argument("--repository", type=Path, default=Path.cwd())
    base.add_argument("--remote-ref", default="origin/main")
    report = commands.add_parser("summary")
    report.add_argument("--plan", type=Path, required=True)
    report.add_argument("--run", type=Path, required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        validate_locale_configuration()
        if args.command == "plan":
            plan = build_plan(
                args.source, args.target_dir, args.context, args.locale, args.base_revision,
                set(args.reconsider),
            )
            _write_json(args.output, plan)
        elif args.command == "generate":
            generate(
                args.plan, args.source, args.target_dir, args.context, args.output_dir,
                os.environ.get("DEEPL_API_KEY", ""),
            )
        elif args.command == "validate-inputs":
            plan = catalogue.read_json(args.plan)
            _validate_plan_inputs(plan, args.source, args.target_dir, args.context)
        elif args.command == "bundle":
            build_bundle(
                args.source, args.target_dir, args.context, args.plan, args.run,
                args.candidate_dir, args.output_dir,
            )
        elif args.command == "validate-bundle":
            validate_bundle(args.bundle_dir, args.source)
        elif args.command == "check-base":
            check_base(args.expected, args.repository, args.remote_ref)
        else:
            print(summary(args.plan, args.run))
    except (DeepLError, catalogue.CatalogueError, OSError) as error:
        print(f"translation candidate error: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
