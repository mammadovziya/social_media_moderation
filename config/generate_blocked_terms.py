#!/usr/bin/env python3
"""Deterministically maintain the generated Azerbaijani vulgar-phrase block.

Five reviewed phrase matrices cover direct curses, genitive/body constructions,
insertion curses, scatological phrases, and obscene imperatives. Explicit noun
and insulting-predicate paradigms cover only separately reviewed forms. Each
entry is emitted as Azerbaijani plus deterministic ASCII surfaces. The generator
intentionally avoids standalone homographs, fuzzy spellings, leetspeak, and
arbitrary suffix synthesis; those require semantic context rather than an
unconditional local block. Run with ``--check`` in validation and ``--write``
after changing this reviewed specification.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unicodedata


BEGIN_MARKER = "# BEGIN GENERATED AZERBAIJANI VULGAR PHRASES"
END_MARKER = "# END GENERATED AZERBAIJANI VULGAR PHRASES"

DIRECT_TARGETS = (
    "ananı",
    "atanı",
    "bacını",
    "qardaşını",
    "arvadını",
    "ərini",
    "qızını",
    "oğlunu",
    "nəslini",
    "ölünü",
    "ölmüşünü",
    "ağzını",
    "sifətini",
    "başını",
    "səni",
    "sizi",
    "hamınızı",
    "hamını",
    "özünü",
    "ruhunu",
)

BODY_OWNERS = (
    "ananın",
    "atanın",
    "bacının",
    "qardaşının",
    "arvadının",
    "ərinin",
    "qızının",
    "oğlunun",
    "nənənin",
    "babanın",
    "xalanın",
    "bibinin",
    "dayının",
    "əminin",
    "qayınananın",
    "qayınatanın",
    "baldızının",
    "gəlininin",
    "nəslinin",
    "ölüsünün",
)

BODY_OBJECTS = (
    "amını",
    "götünü",
    "ağzını",
    "sifətini",
)

SEXUAL_ACTIONS = (
    "sikim",
    "sikərəm",
    "sikəcəm",
    "sikəcəyəm",
    "sikeyim",
    "sikerim",
    "sikirəm",
    "sikmişəm",
    "sikdim",
    "sikəcəyik",
    "sikək",
    "siksin",
    "siksinlər",
    "siksəm",
)

INSERTION_TARGETS = (
    "amına",
    "götünə",
)

INSERTION_ACTIONS = (
    "qoyum",
    "qoyaram",
    "qoyacam",
    "qoyacağam",
    "qoyaq",
    "qoysun",
    "soxum",
    "soxaram",
    "soxacam",
    "soxacağam",
    "soxaq",
    "soxsun",
)

SCATOLOGICAL_TARGETS = (
    "ağzına",
    "başına",
    "üzünə",
    "sifətinə",
    "evinə",
)

SCATOLOGICAL_ACTIONS = (
    "sıçım",
    "sıçaram",
    "sıçacam",
    "sıçacağam",
    "sıçayım",
    "sıçdım",
    "sıçmışam",
    "sıçsın",
)

IMPERATIVE_OBJECTS = (
    "pox",
    "poxumu",
    "bok",
    "bokumu",
    "daşşağımı",
    "yarrağımı",
    "götümü",
    "amımı",
)

IMPERATIVE_ACTIONS = (
    "ye",
    "yeyin",
    "yesin",
    "yesinlər",
    "yeyərsən",
    "yiyəsən",
    "yeyəsiniz",
)

# Explicitly reviewed noun paradigms. Do not synthesize arbitrary suffixes:
# Azerbaijani/Turkish harmony and stem alternation are lexical, and a generic
# suffix product would create invalid words and unsafe homographs.
NOMINAL_PARADIGMS = (
    ("qəhbə", "qəhbəni", "qəhbənin", "qəhbəyə", "qəhbədə", "qəhbədən", "qəhbələr", "qəhbələri"),
    ("orospu", "orospunu", "orospunun", "orospuya", "orospuda", "orospudan", "orospular", "orospuları"),
    ("götverən", "götverəni", "götverənin", "götverənə", "götverəndə", "götverəndən", "götverənlər", "götverənləri"),
    ("gijdıllaq", "gijdıllağı", "gijdıllağın", "gijdıllağa", "gijdıllaqda", "gijdıllaqdan", "gijdıllaqlar", "gijdıllaqları"),
    ("gicdıllaq", "gicdıllağı", "gicdıllağın", "gicdıllağa", "gicdıllaqda", "gicdıllaqdan", "gicdıllaqlar", "gicdıllaqları"),
    ("dalbayob", "dalbayobu", "dalbayobun", "dalbayoba", "dalbayobda", "dalbayobdan", "dalbayoblar", "dalbayobları"),
    ("dolboyob", "dolboyobu", "dolboyobun", "dolboyoba", "dolboyobda", "dolboyobdan", "dolboyoblar", "dolboyobları"),
    ("daşşaq", "daşşağı", "daşşağın", "daşşağa", "daşşaqda", "daşşaqdan", "daşşaqlar", "daşşaqları"),
    ("yarraq", "yarrağı", "yarrağın", "yarrağa", "yarraqda", "yarraqdan", "yarraqlar", "yarraqları"),
    ("amcıq", "amcığı", "amcığın", "amcığa", "amcıqda", "amcıqdan", "amcıqlar", "amcıqları"),
    ("amcık", "amcığı", "amcığın", "amcığa", "amcıkta", "amcıktan", "amcıklar", "amcıkları"),
    ("yarrak", "yarrağı", "yarrağın", "yarrağa", "yarrakta", "yarraktan", "yarraklar", "yarrakları"),
    ("sikik", "sikiği", "sikiğin", "sikiğe", "sikikte", "sikikten", "sikikler", "sikikleri"),
    ("piçoğlu", "piçoğlunu", "piçoğlunun", "piçoğluna", "piçoğlunda", "piçoğlundan", "piçoğlular", "piçoğluları"),
    ("köpoğlu", "köpoğlunu", "köpoğlunun", "köpoğluna", "köpoğlunda", "köpoğlundan", "köpoğlular", "köpoğluları"),
    ("əclaf", "əclafı", "əclafın", "əclafa", "əclafda", "əclafdan", "əclaflar", "əclafları"),
    ("avanak", "avanağı", "avanağın", "avanağa", "avanakda", "avanakdan", "avanaklar", "avanakları"),
    # "cındır" also carries the literal sense "rag". It is blocked as a reviewed product decision;
    # the literal reading is the cost of that decision, not an oversight.
    ("cındır", "cındırı", "cındırın", "cındıra", "cındırda", "cındırdan", "cındırlar",
     "cındırları"),
    ("göt", "götü", "götün", "götə", "götdə", "götdən", "götlər", "götləri"),
)

# Predicate suffixes make these otherwise literal animal nouns direct personal insults. The bare
# homographs eşşək/essek and qoduq intentionally remain outside the unconditional local policy.
INSULTING_PREDICATES = (
    "piçoğlusan",
    "köpoğlusan",
    "əclafsan",
    "avanaksan",
    "eşşəksən",
    "eşşəksiniz",
    "qoduqsan",
    "qoduqsunuz",
)

ASCII_TRANSLATION = str.maketrans(
    {
        "ı": "i",
        "ə": "e",
        "ö": "o",
        "ü": "u",
        "ş": "s",
        "ç": "c",
        "ğ": "g",
    }
)

MANUAL_FOLD_EXCLUSIONS = frozenset({"göt"})

# The reviewed manual ``götəş`` rule intentionally emits the ASCII ``gotes``
# surface. Unlike bare göt -> got, product policy has explicitly accepted this fold.
EXPECTED_MANUAL_VULGAR = 85
EXPECTED_MANUAL_FOLDS = 12
EXPECTED_RAW_GENERATED = 8_951
EXPECTED_SUBSUMED_GENERATED = 1_495
EXPECTED_EMITTED_GENERATED = 7_468
EXPECTED_CANONICAL_VULGAR = 7_553

SEMANTIC_FORMAT_VERSION = "blocked-terms/v4"
HANDLE_FOLD_PROFILE_VERSION = "handle-vulgar-skeleton-v3"
HANDLE_FOLD_PROFILE_SHA256 = (
    "ff191bbb24fe96396f929c0583ed5482b070f9da1c53d5d1c198cf6f76b25d7c"
)
TERM_CATEGORY_PRIORITY = {
    "OTHER": 1,
    "POLITICAL_CONTENT": 2,
    "HANDLE_VULGAR": 3,
    "VULGAR": 4,
    "HATE": 5,
}


def canonical(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).lower()
    normalized = "".join(
        character
        for character in normalized
        if unicodedata.category(character) != "Cf" and character != "\u0307"
    )
    tokens: list[str] = []
    current: list[str] = []
    for character in normalized:
        category = unicodedata.category(character)
        if character.isspace() or category[0] in {"P", "S", "Z"}:
            if current:
                tokens.append("".join(current))
                current = []
        else:
            current.append(character)
    if current:
        tokens.append("".join(current))
    return " ".join(tokens)


def ascii_fold(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value)
    without_marks = "".join(
        character
        for character in decomposed
        if unicodedata.category(character) != "Mn"
    )
    return unicodedata.normalize("NFC", without_marks.translate(ASCII_TRANSLATION))


def raw_generated_terms() -> set[str]:
    phrases: set[str] = set()

    def add_both_orders(
        subject: str,
        action: str,
        *,
        compact: bool = False,
        letter_spaced_action: bool = False,
    ) -> None:
        phrases.add(f"{subject} {action}")
        phrases.add(f"{action} {subject}")
        if compact:
            phrases.add(f"{subject}{action}")
            phrases.add(f"{action}{subject}")
        if letter_spaced_action:
            spaced_action = " ".join(action)
            phrases.add(f"{subject} {spaced_action}")
            phrases.add(f"{spaced_action} {subject}")

    for target in DIRECT_TARGETS:
        for action in SEXUAL_ACTIONS:
            add_both_orders(
                target,
                action,
                compact=True,
                letter_spaced_action=True,
            )
    for owner in BODY_OWNERS:
        for body_object in BODY_OBJECTS:
            for action in SEXUAL_ACTIONS:
                add_both_orders(f"{owner} {body_object}", action)
    for target in INSERTION_TARGETS:
        for action in INSERTION_ACTIONS:
            add_both_orders(target, action, compact=True)
    for target in SCATOLOGICAL_TARGETS:
        for action in SCATOLOGICAL_ACTIONS:
            add_both_orders(target, action, compact=True)
    for obscene_object in IMPERATIVE_OBJECTS:
        for action in IMPERATIVE_ACTIONS:
            add_both_orders(obscene_object, action, compact=True)

    generated = {
        canonical(surface)
        for phrase in phrases
        for surface in (phrase, ascii_fold(phrase))
    }
    for row_index, paradigm in enumerate(NOMINAL_PARADIGMS):
        for inflection in paradigm:
            generated.add(canonical(inflection))
            # Bare Azerbaijani "göt" must never become the ordinary English word
            # "got". Other reviewed roots are safe to ASCII-fold.
            if row_index < len(NOMINAL_PARADIGMS) - 1:
                generated.add(canonical(ascii_fold(inflection)))
    for predicate in INSULTING_PREDICATES:
        generated.add(canonical(predicate))
        generated.add(canonical(ascii_fold(predicate)))
    if len(generated) != EXPECTED_RAW_GENERATED:
        raise ValueError(
            f"matrix drift: expected {EXPECTED_RAW_GENERATED} raw terms, "
            f"found {len(generated)}"
        )
    return generated


def split_policy(source: str) -> tuple[list[str], list[str], list[str]]:
    lines = source.splitlines()
    try:
        begin = lines.index(BEGIN_MARKER)
        end = lines.index(END_MARKER)
    except ValueError as exception:
        raise ValueError("generated block markers are missing") from exception
    if begin >= end or lines.count(BEGIN_MARKER) != 1 or lines.count(END_MARKER) != 1:
        raise ValueError("generated block markers must appear exactly once and in order")
    return lines[: begin + 1], lines[begin + 1 : end], lines[end:]


def manual_vulgar_terms(prefix: list[str], suffix: list[str]) -> set[str]:
    terms = [
        canonical(line.split("|", 1)[1])
        for line in (*prefix, *suffix)
        if line.strip().upper().startswith("VULGAR|")
    ]
    if len(terms) != len(set(terms)):
        raise ValueError("manual VULGAR entries contain canonical duplicates")
    if len(terms) != EXPECTED_MANUAL_VULGAR:
        raise ValueError(
            f"manual corpus drift: expected {EXPECTED_MANUAL_VULGAR} terms, "
            f"found {len(terms)}"
        )
    return set(terms)


def manual_ascii_surfaces(manual_terms: set[str], raw_terms: set[str]) -> set[str]:
    """Return only useful, reviewed ASCII surfaces for manual policy entries.

    The generated matrices already contain most folded forms. Keeping this as a
    set difference avoids duplicate/count padding, while the explicit exclusion
    prevents Azerbaijani ``göt`` from becoming ordinary English ``got``.
    """
    folded = {
        canonical(ascii_fold(term))
        for term in manual_terms
        if term not in MANUAL_FOLD_EXCLUSIONS
        # This fold is an Azerbaijani/Latin-script policy transform. Applying NFKD mark
        # removal to Cyrillic changes letters such as й into и and invents unsafe surfaces.
        and not any("\u0400" <= character <= "\u052f" for character in term)
    }
    folded.difference_update(manual_terms)
    folded.difference_update(raw_terms)
    if len(folded) != EXPECTED_MANUAL_FOLDS:
        raise ValueError(
            f"manual ASCII-fold drift: expected {EXPECTED_MANUAL_FOLDS} surfaces, "
            f"found {len(folded)}"
        )
    return folded


def generated_terms(manual_terms: set[str]) -> tuple[list[str], set[str]]:
    raw = raw_generated_terms()
    manual_folds = manual_ascii_surfaces(manual_terms, raw)
    active = set(manual_terms)
    emitted: list[str] = []
    skipped: set[str] = set()
    for term in sorted(raw | manual_folds, key=lambda value: (len(value.split()), value)):
        tokens = term.split()
        subsumed = any(
            " ".join(tokens[start:end]) in active
            for start in range(len(tokens))
            for end in range(start + 1, len(tokens) + 1)
        )
        if subsumed:
            skipped.add(term)
        else:
            active.add(term)
            emitted.append(term)
    if len(skipped) != EXPECTED_SUBSUMED_GENERATED:
        raise ValueError(
            "subsumption drift: expected "
            f"{EXPECTED_SUBSUMED_GENERATED} skipped terms, found {len(skipped)}"
        )
    if len(emitted) != EXPECTED_EMITTED_GENERATED:
        raise ValueError(
            f"generation drift: expected {EXPECTED_EMITTED_GENERATED} emitted terms, "
            f"found {len(emitted)}"
        )

    combined = sorted(manual_terms | set(emitted), key=lambda term: (len(term.split()), term))
    validated: set[str] = set()
    for term in combined:
        tokens = term.split()
        subsumer = next(
            (
                " ".join(tokens[start:end])
                for start in range(len(tokens))
                for end in range(start + 1, len(tokens) + 1)
                if " ".join(tokens[start:end]) in validated
            ),
            None,
        )
        if subsumer is not None:
            raise ValueError(f"nonminimal policy: {subsumer!r} subsumes {term!r}")
        validated.add(term)
    if len(combined) != EXPECTED_CANONICAL_VULGAR:
        raise ValueError(
            f"corpus drift: expected {EXPECTED_CANONICAL_VULGAR} canonical VULGAR "
            f"terms, found {len(combined)}"
        )
    return emitted, skipped


def expected_source(source: str) -> tuple[str, int]:
    prefix, _old_generated, suffix = split_policy(source)
    emitted, skipped = generated_terms(manual_vulgar_terms(prefix, suffix))
    generated_block = [
        "# Generated by config/generate_blocked_terms.py; do not edit this block.",
        "# Five reviewed phrase matrices emit both word orders and Azerbaijani/ASCII surfaces.",
        "# It also emits explicit noun and insulting-predicate inflections, compact phrases,",
        "# and contextual letter spacing.",
        f"# raw={EXPECTED_RAW_GENERATED}, manual-folds={EXPECTED_MANUAL_FOLDS}, "
        f"subsumed={EXPECTED_SUBSUMED_GENERATED}, emitted={EXPECTED_EMITTED_GENERATED}, "
        f"total-canonical-vulgar={EXPECTED_CANONICAL_VULGAR}",
        *(f"VULGAR|{term}" for term in emitted),
    ]
    expected_lines = [*prefix, *generated_block, *suffix]
    return "\n".join(expected_lines) + "\n", len(skipped)


def policy_manifest(encoded: bytes) -> dict[str, object]:
    """Compile the governed source metadata exactly like ReloadingBlockedTerms."""
    source = encoded.decode("utf-8", errors="strict")
    terms: dict[str, str] = {}
    active_entries = 0
    for index, source_line in enumerate(source.splitlines()):
        line = source_line.strip()
        if index == 0 and line.startswith("\ufeff"):
            line = line[1:].strip()
        if not line or line.startswith("#"):
            continue
        active_entries += 1
        if active_entries > 10_000:
            raise ValueError("blocked terms policy has too many entries")
        if line.count("|") == 0:
            category = "OTHER"
            value = line
        elif line.count("|") == 1:
            category, value = (part.strip() for part in line.split("|", 1))
            category = category.upper()
            if category not in TERM_CATEGORY_PRIORITY or category == "OTHER":
                raise ValueError(f"blocked terms line {index + 1} has an unknown category")
        else:
            raise ValueError(
                f"blocked terms line {index + 1} must contain CATEGORY|term"
            )
        if any(ord(character) <= 0x1F or 0x7F <= ord(character) <= 0x9F for character in value):
            raise ValueError(f"blocked terms line {index + 1} contains a control character")
        term = canonical(value).strip()
        if not term or len(term) > 256:
            raise ValueError(f"blocked terms line {index + 1} is empty or too long")
        first_category = unicodedata.category(term[0])
        last_category = unicodedata.category(term[-1])
        if not (
            (first_category.startswith("L") or first_category == "Nd")
            and (last_category.startswith("L") or last_category == "Nd")
        ):
            raise ValueError(
                f"blocked terms line {index + 1} must start and end with a letter or digit"
            )
        previous = terms.get(term)
        if previous is None or TERM_CATEGORY_PRIORITY[category] > TERM_CATEGORY_PRIORITY[previous]:
            terms[term] = category

    digest = hashlib.sha256()

    def update(value: str) -> None:
        value_bytes = value.encode("utf-8")
        digest.update(struct.pack(">I", len(value_bytes)))
        digest.update(value_bytes)

    update(SEMANTIC_FORMAT_VERSION)
    update(HANDLE_FOLD_PROFILE_VERSION)
    update(HANDLE_FOLD_PROFILE_SHA256)
    update("folded-text-category=VULGAR,HATE")
    update("folded-handle-category=HANDLE_VULGAR,VULGAR,HATE")
    update("handle-fragments:start-anchored;exact-min=4;prefix-min=5;interior-min=6")
    for term in sorted(
        terms,
        key=lambda value: value.encode("utf-16-be", errors="surrogatepass"),
    ):
        update(terms[term])
        update(term)

    category_counts = {
        category: sum(1 for value in terms.values() if value == category)
        for category in TERM_CATEGORY_PRIORITY
        if any(value == category for value in terms.values())
    }
    return {
        "formatVersion": SEMANTIC_FORMAT_VERSION,
        "handleFoldProfileVersion": HANDLE_FOLD_PROFILE_VERSION,
        "handleFoldProfileSha256": HANDLE_FOLD_PROFILE_SHA256,
        "sourceSha256": hashlib.sha256(encoded).hexdigest(),
        "semanticSha256": digest.hexdigest(),
        "termCount": len(terms),
        "categoryCounts": category_counts,
    }


def encoded_manifest(manifest: dict[str, object]) -> str:
    return json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"


def atomic_write(path: Path, value: str) -> None:
    mode = path.stat().st_mode if path.exists() else 0o644
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=path.parent, delete=False
    ) as temporary:
        temporary.write(value)
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_path = Path(temporary.name)
    try:
        temporary_path.chmod(mode)
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="fail if the block is stale")
    mode.add_argument("--write", action="store_true", help="atomically rewrite the block")
    mode.add_argument(
        "--manifest", action="store_true", help="print the compiled release manifest"
    )
    parser.add_argument(
        "--policy",
        type=Path,
        default=Path(__file__).with_name("blocked_terms.txt"),
        help="policy file to check or update",
    )
    parser.add_argument(
        "--manifest-file",
        type=Path,
        help="manifest to check or update (defaults beside the policy file)",
    )
    arguments = parser.parse_args()

    policy = arguments.policy.resolve()
    manifest_file = (
        arguments.manifest_file.resolve()
        if arguments.manifest_file
        else policy.with_name("blocked_terms_manifest.json")
    )
    source = policy.read_text(encoding="utf-8")
    expected, skipped_count = expected_source(source)
    expected_manifest = encoded_manifest(policy_manifest(expected.encode("utf-8")))
    if arguments.manifest:
        print(expected_manifest, end="")
        return 0
    if arguments.write:
        if source != expected:
            atomic_write(policy, expected)
        if not manifest_file.exists() or manifest_file.read_text(encoding="utf-8") != expected_manifest:
            atomic_write(manifest_file, expected_manifest)
        print(
            f"wrote {policy}: {EXPECTED_CANONICAL_VULGAR} canonical VULGAR terms "
            f"({EXPECTED_EMITTED_GENERATED} generated, {skipped_count} subsumed)"
        )
        return 0
    if source != expected:
        print(
            "generated vulgar block is stale; run "
            "python3 config/generate_blocked_terms.py --write",
            file=sys.stderr,
        )
        return 1
    if not manifest_file.exists() or manifest_file.read_text(encoding="utf-8") != expected_manifest:
        print(
            "blocked terms manifest is stale; run "
            "python3 config/generate_blocked_terms.py --write",
            file=sys.stderr,
        )
        return 1
    print(
        f"checked {policy}: {EXPECTED_CANONICAL_VULGAR} canonical VULGAR terms "
        f"({EXPECTED_EMITTED_GENERATED} generated, {skipped_count} subsumed)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
