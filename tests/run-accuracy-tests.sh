#!/usr/bin/env bash

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASET="${1:-$SCRIPT_DIR/accuracy/text-cases.jsonl}"
BASE_URL="${2:-${MODERATION_BASE_URL:-http://localhost:18080}}"
BASE_URL="${BASE_URL%/}"
EXPECTED_CASE_COUNT="${EXPECTED_CASE_COUNT:-100}"
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-90}"
TEST_DELAY_SECONDS="${TEST_DELAY_SECONDS:-0}"
MIN_EXACT_ACCURACY="${MIN_EXACT_ACCURACY:-0}"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    cat <<'USAGE'
Usage:
  ./tests/run-accuracy-tests.sh [dataset.jsonl] [base-url]

Defaults:
  dataset: tests/accuracy/text-cases.jsonl
  base URL: MODERATION_BASE_URL or http://localhost:18080

Optional environment variables:
  REQUEST_TIMEOUT_SECONDS=90
  TEST_DELAY_SECONDS=0
  MIN_EXACT_ACCURACY=0
  EXPECTED_CASE_COUNT=100
  VALIDATE_ONLY=1
  NO_COLOR=1

The script sends one live multipart request per case, prints the text,
expected enums, response, and mismatches, and reports aggregate accuracy.
USAGE
    exit 0
fi

for command_name in curl jq awk mktemp; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Missing required command: %s\n' "$command_name" >&2
        exit 1
    fi
done

if [[ ! -f "$DATASET" ]]; then
    printf 'Dataset not found: %s\n' "$DATASET" >&2
    exit 1
fi

case_count="$(jq -s 'length' "$DATASET" 2>/dev/null)" || {
    printf 'Dataset is not valid JSONL: %s\n' "$DATASET" >&2
    exit 1
}

if [[ "$case_count" -ne "$EXPECTED_CASE_COUNT" ]]; then
    printf 'Expected %s cases, found %s in %s\n' \
        "$EXPECTED_CASE_COUNT" "$case_count" "$DATASET" >&2
    exit 1
fi

if ! jq -e -s --argjson expected_count "$EXPECTED_CASE_COUNT" '
    length == $expected_count
    and ([.[].id] | unique | length) == $expected_count
    and all(.[];
        (.id | type == "string" and length > 0)
        and (.text | type == "string" and length > 0)
        and (.language as $value
            | ["AZ", "EN", "RU", "TR"] | index($value) != null)
        and (.contentType as $value
            | ["POST", "COMMENT", "USERNAME"] | index($value) != null)
        and (.expected.decision as $value
            | ["ALLOW", "BLOCK", "UNKNOWN"] | index($value) != null)
        and (.expected.violation as $value
            | [
                "NONE", "HARASSMENT", "HATE", "THREAT", "SELF_HARM",
                "SEXUAL", "SEXUAL_MINORS", "GRAPHIC_VIOLENCE", "VIOLENCE",
                "ILLICIT", "SPAM_SCAM", "VULGAR", "IMPERSONATION", "NOT_INVESTMENT",
                "KNOWN_IMAGE", "ANALYZER_ERROR", "OTHER"
              ] | index($value) != null)
        and (
            if .contentType == "POST" then
                (.expected | keys | sort)
                    == ["decision", "investment", "politics", "violation"]
                and (.expected.investment as $value
                    | ["RELATED", "NOT_RELATED", "UNCERTAIN"]
                    | index($value) != null)
                and (.expected.politics as $value
                    | [
                        "NOT_RELATED", "NEUTRAL_OR_SUPPORTIVE",
                        "CRITICAL_OR_NEGATIVE", "HIGH_RISK", "UNCERTAIN"
                      ] | index($value) != null)
            elif .contentType == "COMMENT" then
                (.expected | keys | sort)
                    == ["decision", "politics", "violation"]
                and (.expected.politics as $value
                    | [
                        "NOT_RELATED", "NEUTRAL_OR_SUPPORTIVE",
                        "CRITICAL_OR_NEGATIVE", "HIGH_RISK", "UNCERTAIN"
                      ] | index($value) != null)
            else
                (.expected | keys | sort) == ["decision", "violation"]
            end
        )
    )
' "$DATASET" >/dev/null; then
    printf 'Dataset schema or enum validation failed: %s\n' "$DATASET" >&2
    exit 1
fi

if [[ "${VALIDATE_ONLY:-0}" == "1" ]]; then
    printf 'Dataset valid: %s cases in %s\n' "$case_count" "$DATASET"
    jq -r -s '
        group_by(.contentType)[]
        | "  \(.[0].contentType): \(length)"
    ' "$DATASET"
    jq -r -s '
        group_by(.language)[]
        | "  \(.[0].language): \(length)"
    ' "$DATASET"
    exit 0
fi

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
    COLOR_GREEN=$'\033[32m'
    COLOR_RED=$'\033[31m'
    COLOR_YELLOW=$'\033[33m'
    COLOR_BOLD=$'\033[1m'
    COLOR_RESET=$'\033[0m'
else
    COLOR_GREEN=""
    COLOR_RED=""
    COLOR_YELLOW=""
    COLOR_BOLD=""
    COLOR_RESET=""
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/moderation-accuracy.XXXXXX")"
cleanup() {
    if [[ -n "${work_dir:-}" && -d "$work_dir" ]]; then
        rm -rf -- "$work_dir"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

api_success=0
exact_correct=0
label_correct=0
label_total=0
contract_correct=0
contract_total=0

decision_correct=0
decision_total=0
violation_correct=0
violation_total=0
investment_correct=0
investment_total=0
politics_correct=0
politics_total=0

post_correct=0
post_total=0
comment_correct=0
comment_total=0
username_correct=0
username_total=0

az_correct=0
az_total=0
en_correct=0
en_total=0
ru_correct=0
ru_total=0
tr_correct=0
tr_total=0

percentage() {
    local numerator="$1"
    local denominator="$2"
    if [[ "$denominator" -eq 0 ]]; then
        printf 'n/a'
    else
        awk -v numerator="$numerator" -v denominator="$denominator" \
            'BEGIN { printf "%.2f", (numerator * 100) / denominator }'
    fi
}

print_metric() {
    local label="$1"
    local numerator="$2"
    local denominator="$3"
    printf '  %-29s %3d/%-3d  %6s%%\n' \
        "$label" "$numerator" "$denominator" \
        "$(percentage "$numerator" "$denominator")"
}

printf '%sModeration accuracy run%s\n' "$COLOR_BOLD" "$COLOR_RESET"
printf 'Endpoint: %s/v1/moderate\n' "$BASE_URL"
printf 'Dataset:  %s\n' "$DATASET"
printf 'Cases:    %s text-only live requests\n' "$case_count"
printf 'Note: each request invokes the configured moderation and custom models.\n'

index=0
while IFS= read -r test_case || [[ -n "$test_case" ]]; do
    index=$((index + 1))
    case_id="$(jq -r '.id' <<<"$test_case")"
    content_type="$(jq -r '.contentType' <<<"$test_case")"
    language="$(jq -r '.language' <<<"$test_case")"
    text_value="$(jq -r '.text' <<<"$test_case")"
    expected_json="$(jq -cS '.expected' <<<"$test_case")"

    case "$content_type" in
        POST) post_total=$((post_total + 1)) ;;
        COMMENT) comment_total=$((comment_total + 1)) ;;
        USERNAME) username_total=$((username_total + 1)) ;;
    esac
    case "$language" in
        AZ) az_total=$((az_total + 1)) ;;
        EN) en_total=$((en_total + 1)) ;;
        RU) ru_total=$((ru_total + 1)) ;;
        TR) tr_total=$((tr_total + 1)) ;;
    esac

    body_file="$work_dir/response-$index.json"
    error_file="$work_dir/curl-$index.log"
    curl_exit=0
    http_code="$(
        curl -sS \
            --max-time "$REQUEST_TIMEOUT_SECONDS" \
            -o "$body_file" \
            -w '%{http_code}' \
            "$BASE_URL/v1/moderate" \
            --form-string "contentId=$case_id" \
            --form-string "contentType=$content_type" \
            --form-string "text=$text_value" \
            2>"$error_file"
    )" || curl_exit=$?

    response_is_json=false
    if [[ -s "$body_file" ]] && jq -e 'type == "object"' "$body_file" >/dev/null 2>&1; then
        response_is_json=true
        response_text="$(jq -cS . "$body_file")"
    elif [[ -s "$body_file" ]]; then
        response_text="$(tr '\r\n' '  ' <"$body_file")"
    else
        response_text="<empty>"
    fi

    printf '\n%s[%03d/%03d] %s | %s | %s%s\n' \
        "$COLOR_BOLD" "$index" "$case_count" "$case_id" \
        "$content_type" "$language" "$COLOR_RESET"
    printf 'Text:     %s\n' "$text_value"
    printf 'Expected: %s\n' "$expected_json"
    printf 'Response: %s\n' "$response_text"

    case_pass=true
    if [[ "$curl_exit" -eq 0 && "$http_code" == "200" && "$response_is_json" == true ]]; then
        api_success=$((api_success + 1))
    else
        case_pass=false
        curl_error="$(tr '\r\n' '  ' <"$error_file")"
        printf '%sAPI error:%s curl=%s http=%s %s\n' \
            "$COLOR_RED" "$COLOR_RESET" "$curl_exit" \
            "${http_code:-000}" "$curl_error"
    fi

    for contract_field in contentId contentType; do
        contract_total=$((contract_total + 1))
        if [[ "$contract_field" == "contentId" ]]; then
            contract_expected="$case_id"
        else
            contract_expected="$content_type"
        fi
        if [[ "$response_is_json" == true ]]; then
            contract_actual="$(
                jq -r --arg field "$contract_field" \
                    'if has($field) then .[$field] else "__MISSING__" end' \
                    "$body_file"
            )"
        else
            contract_actual="__MISSING__"
        fi
        if [[ "$contract_actual" == "$contract_expected" ]]; then
            contract_correct=$((contract_correct + 1))
        else
            case_pass=false
            printf '%sMismatch:%s %s expected=%s actual=%s\n' \
                "$COLOR_YELLOW" "$COLOR_RESET" "$contract_field" \
                "$contract_expected" "$contract_actual"
        fi
    done

    for field_name in decision violation investment politics; do
        expected_value="$(
            jq -r --arg field "$field_name" \
                'if .expected | has($field) then
                    .expected[$field]
                 else
                    "__NOT_EXPECTED__"
                 end' <<<"$test_case"
        )"
        if [[ "$expected_value" == "__NOT_EXPECTED__" ]]; then
            continue
        fi

        label_total=$((label_total + 1))
        case "$field_name" in
            decision) decision_total=$((decision_total + 1)) ;;
            violation) violation_total=$((violation_total + 1)) ;;
            investment) investment_total=$((investment_total + 1)) ;;
            politics) politics_total=$((politics_total + 1)) ;;
        esac

        if [[ "$response_is_json" == true ]]; then
            actual_value="$(
                jq -r --arg field "$field_name" \
                    'if has($field) then .[$field] else "__MISSING__" end' \
                    "$body_file"
            )"
        else
            actual_value="__MISSING__"
        fi

        if [[ "$actual_value" == "$expected_value" ]]; then
            label_correct=$((label_correct + 1))
            case "$field_name" in
                decision) decision_correct=$((decision_correct + 1)) ;;
                violation) violation_correct=$((violation_correct + 1)) ;;
                investment) investment_correct=$((investment_correct + 1)) ;;
                politics) politics_correct=$((politics_correct + 1)) ;;
            esac
        else
            case_pass=false
            printf '%sMismatch:%s %s expected=%s actual=%s\n' \
                "$COLOR_YELLOW" "$COLOR_RESET" "$field_name" \
                "$expected_value" "$actual_value"
        fi
    done

    if [[ "$case_pass" == true ]]; then
        exact_correct=$((exact_correct + 1))
        case "$content_type" in
            POST) post_correct=$((post_correct + 1)) ;;
            COMMENT) comment_correct=$((comment_correct + 1)) ;;
            USERNAME) username_correct=$((username_correct + 1)) ;;
        esac
        case "$language" in
            AZ) az_correct=$((az_correct + 1)) ;;
            EN) en_correct=$((en_correct + 1)) ;;
            RU) ru_correct=$((ru_correct + 1)) ;;
            TR) tr_correct=$((tr_correct + 1)) ;;
        esac
        printf '%sResult:   PASS%s\n' "$COLOR_GREEN" "$COLOR_RESET"
    else
        printf '%sResult:   FAIL%s\n' "$COLOR_RED" "$COLOR_RESET"
    fi

    if [[ "$TEST_DELAY_SECONDS" != "0" && "$index" -lt "$case_count" ]]; then
        sleep "$TEST_DELAY_SECONDS"
    fi
done < <(jq -c . "$DATASET")

printf '\n%sAccuracy summary%s\n' "$COLOR_BOLD" "$COLOR_RESET"
print_metric "Exact case accuracy" "$exact_correct" "$case_count"
print_metric "Label-field accuracy" "$label_correct" "$label_total"
print_metric "Response contract accuracy" "$contract_correct" "$contract_total"
print_metric "API success rate" "$api_success" "$case_count"

printf '\nBy content type\n'
print_metric "POST exact accuracy" "$post_correct" "$post_total"
print_metric "COMMENT exact accuracy" "$comment_correct" "$comment_total"
print_metric "USERNAME exact accuracy" "$username_correct" "$username_total"

printf '\nBy language\n'
print_metric "Azerbaijani exact accuracy" "$az_correct" "$az_total"
print_metric "English exact accuracy" "$en_correct" "$en_total"
print_metric "Russian exact accuracy" "$ru_correct" "$ru_total"
print_metric "Turkish exact accuracy" "$tr_correct" "$tr_total"

printf '\nBy label\n'
print_metric "decision" "$decision_correct" "$decision_total"
print_metric "violation" "$violation_correct" "$violation_total"
print_metric "investment" "$investment_correct" "$investment_total"
print_metric "politics" "$politics_correct" "$politics_total"

exact_accuracy="$(percentage "$exact_correct" "$case_count")"
api_failures=$((case_count - api_success))
if [[ "$api_failures" -gt 0 ]]; then
    printf '\n%sCompleted with %d API failures.%s\n' \
        "$COLOR_RED" "$api_failures" "$COLOR_RESET" >&2
    exit 2
fi

if ! awk -v actual="$exact_accuracy" -v minimum="$MIN_EXACT_ACCURACY" \
    'BEGIN { exit !(actual + 0 >= minimum + 0) }'; then
    printf '\n%sExact accuracy %s%% is below required %s%%.%s\n' \
        "$COLOR_RED" "$exact_accuracy" "$MIN_EXACT_ACCURACY" \
        "$COLOR_RESET" >&2
    exit 3
fi
