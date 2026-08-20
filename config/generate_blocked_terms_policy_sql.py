#!/usr/bin/env python3
"""Generate controlled PostgreSQL draft, approval, and activation statements.

The command only writes SQL to stdout. Pipe or redirect it into a reviewed file, then execute that
file with ``psql -v ON_ERROR_STOP=1`` using the role appropriate to the operation.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

from generate_blocked_terms import expected_source, policy_manifest


RELEASE_VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:~/-]{0,127}$")


def sql_literal(value: str, name: str, maximum_length: int) -> str:
    if not value or len(value) > maximum_length or any(ord(char) < 0x20 for char in value):
        raise ValueError(f"{name} must contain 1 to {maximum_length} printable characters")
    return "'" + value.replace("'", "''") + "'"


def validated_release_version(value: str) -> str:
    if not RELEASE_VERSION.fullmatch(value):
        raise ValueError("release version contains unsupported characters")
    return sql_literal(value, "release version", 128)


def checked_manifest(policy: Path, manifest_file: Path) -> tuple[bytes, dict[str, object]]:
    encoded = policy.read_bytes()
    source = encoded.decode("utf-8", errors="strict")
    expected, _ = expected_source(source)
    if source != expected:
        raise ValueError(
            "generated policy block is stale; run generate_blocked_terms.py --write"
        )
    computed = policy_manifest(encoded)
    checked_in = json.loads(manifest_file.read_text(encoding="utf-8"))
    if computed != checked_in:
        raise ValueError(
            "policy manifest is stale; run generate_blocked_terms.py --write"
        )
    return encoded, computed


def draft_sql(arguments: argparse.Namespace) -> str:
    encoded, manifest = checked_manifest(arguments.policy, arguments.manifest_file)
    return f"""\\set ON_ERROR_STOP on
BEGIN;
INSERT INTO public.moderation_blocked_terms_releases (
    release_version,
    format_version,
    handle_fold_profile_version,
    handle_fold_profile_sha256,
    source_sha256,
    semantic_sha256,
    term_count,
    policy_document,
    state
) VALUES (
    {validated_release_version(arguments.release_version)},
    {sql_literal(str(manifest['formatVersion']), 'format version', 64)},
    {sql_literal(str(manifest['handleFoldProfileVersion']), 'handle-fold profile version', 64)},
    {sql_literal(str(manifest['handleFoldProfileSha256']), 'handle-fold profile SHA-256', 64)},
    {sql_literal(str(manifest['sourceSha256']), 'source SHA-256', 64)},
    {sql_literal(str(manifest['semanticSha256']), 'semantic SHA-256', 64)},
    {int(manifest['termCount'])},
    decode('{encoded.hex()}', 'hex'),
    'DRAFT'
);
COMMIT;
"""


def approve_sql(arguments: argparse.Namespace) -> str:
    _, manifest = checked_manifest(arguments.policy, arguments.manifest_file)
    return f"""\\set ON_ERROR_STOP on
BEGIN;
DO $blocked_terms_review$
DECLARE
    affected_rows INTEGER;
BEGIN
    UPDATE public.moderation_blocked_terms_releases
    SET state = 'APPROVED',
        review_note = {sql_literal(arguments.review_note, 'review note', 1000)}
    WHERE release_version = {validated_release_version(arguments.release_version)}
      AND state = 'DRAFT'
      AND format_version = {sql_literal(str(manifest['formatVersion']), 'format version', 64)}
      AND handle_fold_profile_version = {sql_literal(str(manifest['handleFoldProfileVersion']), 'handle-fold profile version', 64)}
      AND handle_fold_profile_sha256 = {sql_literal(str(manifest['handleFoldProfileSha256']), 'handle-fold profile SHA-256', 64)}
      AND source_sha256 = {sql_literal(str(manifest['sourceSha256']), 'source SHA-256', 64)}
      AND semantic_sha256 = {sql_literal(str(manifest['semanticSha256']), 'semantic SHA-256', 64)}
      AND term_count = {int(manifest['termCount'])};
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 1 THEN
        RAISE EXCEPTION 'expected exactly one draft blocked-terms release, updated %',
            affected_rows;
    END IF;
END;
$blocked_terms_review$;
COMMIT;
"""


def activate_sql(arguments: argparse.Namespace) -> str:
    if arguments.first_activation == (arguments.expected_previous_version is not None):
        raise ValueError(
            "select exactly one of --first-activation or --expected-previous-version"
        )
    expected_lookup = "expected_previous_id := NULL;"
    if arguments.expected_previous_version is not None:
        expected_lookup = f"""
    SELECT id INTO expected_previous_id
    FROM public.moderation_blocked_terms_releases
    WHERE release_version = {validated_release_version(arguments.expected_previous_version)};
    IF expected_previous_id IS NULL THEN
        RAISE EXCEPTION 'expected previous blocked-terms release does not exist';
    END IF;"""
    return f"""\\set ON_ERROR_STOP on
BEGIN;
DO $blocked_terms_activation$
DECLARE
    target_release_id BIGINT;
    expected_previous_id BIGINT;
BEGIN
    SELECT id INTO target_release_id
    FROM public.moderation_blocked_terms_releases
    WHERE release_version = {validated_release_version(arguments.release_version)};
    IF target_release_id IS NULL THEN
        RAISE EXCEPTION 'target blocked-terms release does not exist';
    END IF;
    {expected_lookup}
    INSERT INTO public.moderation_blocked_terms_activations (
        release_id,
        previous_release_id,
        change_reference,
        reason
    ) VALUES (
        target_release_id,
        expected_previous_id,
        {sql_literal(arguments.change_reference, 'change reference', 256)},
        {sql_literal(arguments.reason, 'activation reason', 1000)}
    );
END;
$blocked_terms_activation$;
COMMIT;
"""


def parser() -> argparse.ArgumentParser:
    command = argparse.ArgumentParser(description=__doc__)
    subcommands = command.add_subparsers(dest="operation", required=True)

    draft = subcommands.add_parser("draft", help="generate an immutable draft import")
    draft.add_argument("--release-version", required=True)
    draft.add_argument(
        "--policy",
        type=Path,
        default=Path(__file__).with_name("blocked_terms.txt"),
    )
    draft.add_argument(
        "--manifest-file",
        type=Path,
        default=Path(__file__).with_name("blocked_terms_manifest.json"),
    )

    approve = subcommands.add_parser("approve", help="generate checker approval")
    approve.add_argument("--release-version", required=True)
    approve.add_argument("--review-note", required=True)
    approve.add_argument(
        "--policy",
        type=Path,
        default=Path(__file__).with_name("blocked_terms.txt"),
    )
    approve.add_argument(
        "--manifest-file",
        type=Path,
        default=Path(__file__).with_name("blocked_terms_manifest.json"),
    )

    activate = subcommands.add_parser("activate", help="generate a CAS activation or rollback")
    activate.add_argument("--release-version", required=True)
    previous = activate.add_mutually_exclusive_group(required=True)
    previous.add_argument("--first-activation", action="store_true")
    previous.add_argument("--expected-previous-version")
    activate.add_argument("--change-reference", required=True)
    activate.add_argument("--reason", required=True)
    return command


def main() -> int:
    try:
        arguments = parser().parse_args()
        generated = {
            "draft": draft_sql,
            "approve": approve_sql,
            "activate": activate_sql,
        }[arguments.operation](arguments)
        print(generated, end="")
        return 0
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as exception:
        print(f"error: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
