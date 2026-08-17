#!/usr/bin/env python3
"""Deterministically maintain the generated Azerbaijani vulgar-phrase block.

Five reviewed matrices cover direct curses, genitive/body constructions,
insertion curses, scatological phrases, and obscene imperatives. Each phrase is
emitted in both natural word orders and as Azerbaijani plus deterministic ASCII
surfaces. The generator intentionally avoids standalone homographs, fuzzy
spellings, leetspeak, and arbitrary suffix synthesis; those require semantic
context rather than an unconditional local block. Run with ``--check`` in
validation and ``--write`` after changing this reviewed specification.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
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
    # "cındır" also carries the literal sense "rag". It is blocked as a reviewed product decision;
    # the literal reading is the cost of that decision, not an oversight.
    ("cındır", "cındırı", "cındırın", "cındıra", "cındırda", "cındırdan", "cındırlar",
     "cındırları"),
    ("göt", "götü", "götün", "götə", "götdə", "götdən", "götlər", "götləri"),
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

EXPECTED_MANUAL_VULGAR = 32
EXPECTED_MANUAL_FOLDS = 4
EXPECTED_RAW_GENERATED = 8_878
EXPECTED_SUBSUMED_GENERATED = 1_495
EXPECTED_EMITTED_GENERATED = 7_387
EXPECTED_CANONICAL_VULGAR = 7_419


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
        "# Five reviewed matrices emit both word orders and Azerbaijani/ASCII surfaces.",
        "# It also emits explicit noun inflections, compact phrases, and contextual letter spacing.",
        "# raw=8878, manual-folds=4, subsumed=1495, emitted=7387, "
        "total-canonical-vulgar=7419",
        *(f"VULGAR|{term}" for term in emitted),
    ]
    expected_lines = [*prefix, *generated_block, *suffix]
    return "\n".join(expected_lines) + "\n", len(skipped)


def atomic_write(path: Path, value: str) -> None:
    mode = path.stat().st_mode
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
    parser.add_argument(
        "--policy",
        type=Path,
        default=Path(__file__).with_name("blocked_terms.txt"),
        help="policy file to check or update",
    )
    arguments = parser.parse_args()

    policy = arguments.policy.resolve()
    source = policy.read_text(encoding="utf-8")
    expected, skipped_count = expected_source(source)
    if arguments.write:
        if source != expected:
            atomic_write(policy, expected)
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
    print(
        f"checked {policy}: {EXPECTED_CANONICAL_VULGAR} canonical VULGAR terms "
        f"({EXPECTED_EMITTED_GENERATED} generated, {skipped_count} subsumed)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
