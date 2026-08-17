#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="$SCRIPT_DIR/accuracy/realistic-500"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" || "$#" -gt 1 ]]; then
    cat <<'USAGE'
Usage:
  ./tests/run-realistic-500-accuracy.sh [base-url]

The four reviewed shards are validated and combined into a temporary JSONL
file, then passed to run-accuracy-tests.sh with EXPECTED_CASE_COUNT=500.

Use VALIDATE_ONLY=1 for an offline integrity check. A live run retains the
parent runner's CONFIRM_LIVE_API=1 and internal-token safeguards.
USAGE
    exit 0
fi

"$SCRIPT_DIR/validate-realistic-500.sh"

combined_dir="$(mktemp -d "${TMPDIR:-/tmp}/realistic-500.XXXXXX")"
combined_dataset="$combined_dir/cases.jsonl"
trap 'rm -f "$combined_dataset"; rmdir "$combined_dir"' EXIT
jq -c . "$DATA_DIR"/az-125.jsonl "$DATA_DIR"/en-125.jsonl \
    "$DATA_DIR"/ru-125.jsonl "$DATA_DIR"/tr-125.jsonl >"$combined_dataset"

if [[ "$#" -eq 1 ]]; then
    EXPECTED_CASE_COUNT=500 "$SCRIPT_DIR/run-accuracy-tests.sh" \
        "$combined_dataset" "$1"
else
    EXPECTED_CASE_COUNT=500 "$SCRIPT_DIR/run-accuracy-tests.sh" \
        "$combined_dataset"
fi
