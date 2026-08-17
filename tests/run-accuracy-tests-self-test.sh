#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/run-accuracy-tests.sh"
DATASET="$SCRIPT_DIR/accuracy/text-cases.jsonl"
FAKE_CURL="$SCRIPT_DIR/fixtures/fake-accuracy-curl.sh"
SELF_TEST_TOKEN="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/moderation-accuracy-self-test.XXXXXX")"
cleanup() {
    rm -rf -- "$work_dir"
}
trap cleanup EXIT

mkdir -p "$work_dir/bin"
ln -s "$FAKE_CURL" "$work_dir/bin/curl"
marker="$work_dir/curl-called"

common_env=(
    "PATH=$work_dir/bin:$PATH"
    "ACCURACY_SELF_TEST_DATASET=$DATASET"
    "ACCURACY_SELF_TEST_EXPECTED_HEADER=X-Moderation-Internal-Token: $SELF_TEST_TOKEN"
    "ACCURACY_SELF_TEST_MARKER=$marker"
    "NO_COLOR=1"
)

env "${common_env[@]}" VALIDATE_ONLY=1 \
    "$RUNNER" >"$work_dir/validate.out" 2>"$work_dir/validate.err"
if [[ -e "$marker" ]]; then
    printf '%s\n' 'validate-only unexpectedly invoked curl' >&2
    exit 1
fi

set +e
(
    unset MODERATION_INTERNAL_RESPONSE_TOKEN
    env "${common_env[@]}" CONFIRM_LIVE_API=1 \
        "$RUNNER" >"$work_dir/missing-token.out" 2>"$work_dir/missing-token.err"
)
missing_token_status=$?
set -e
if [[ "$missing_token_status" -ne 2 || -e "$marker" ]]; then
    printf '%s\n' 'missing-token gate failed' >&2
    exit 1
fi

env "${common_env[@]}" \
    CONFIRM_LIVE_API=1 \
    MODERATION_INTERNAL_RESPONSE_TOKEN="$SELF_TEST_TOKEN" \
    MIN_EXACT_ACCURACY=100 \
    "$RUNNER" >"$work_dir/positive.out" 2>"$work_dir/positive.err"

grep -Eq 'Exact case accuracy +100/100 +100\.00%' "$work_dir/positive.out"
grep -Eq 'Label-field accuracy +1300/1300 +100\.00%' "$work_dir/positive.out"
grep -Eq 'Response contract accuracy +100/100 +100\.00%' "$work_dir/positive.out"
for label in \
    decision violation reason domain safetyAction safety financialClaim \
    financialRisk financialPrivacy impersonation politicalContext \
    restrictedPoliticalEntity investment politics; do
    grep -Eq "^  ${label} +[0-9]+/[0-9]+ +100\\.00%" "$work_dir/positive.out"
done

set +e
env "${common_env[@]}" \
    ACCURACY_SELF_TEST_BAD_CONTRACT=1 \
    CONFIRM_LIVE_API=1 \
    MODERATION_INTERNAL_RESPONSE_TOKEN="$SELF_TEST_TOKEN" \
    MIN_EXACT_ACCURACY=100 \
    "$RUNNER" >"$work_dir/bad-contract.out" 2>"$work_dir/bad-contract.err"
bad_contract_status=$?
set -e
if [[ "$bad_contract_status" -ne 4 ]]; then
    printf 'bad-contract fixture returned %s instead of 4\n' \
        "$bad_contract_status" >&2
    exit 1
fi
grep -Eq 'Response contract accuracy +0/100 +0\.00%' "$work_dir/bad-contract.out"

if grep -Fq "$SELF_TEST_TOKEN" \
        "$work_dir/validate.out" "$work_dir/validate.err" \
        "$work_dir/missing-token.out" "$work_dir/missing-token.err" \
        "$work_dir/positive.out" "$work_dir/positive.err" \
        "$work_dir/bad-contract.out" "$work_dir/bad-contract.err"; then
    printf '%s\n' 'internal response token leaked into test output' >&2
    exit 1
fi

printf '%s\n' 'Accuracy runner offline self-test passed.'
