#!/usr/bin/env bash

set -uo pipefail
# Never allow an inherited or command-line xtrace setting to print credentials.
set +x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASET="${1:-$SCRIPT_DIR/accuracy/text-cases.jsonl}"
BASE_URL="${2:-${MODERATION_BASE_URL:-http://localhost:18080}}"
BASE_URL="${BASE_URL%/}"
EXPECTED_CASE_COUNT="${EXPECTED_CASE_COUNT:-100}"
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-90}"
TEST_DELAY_SECONDS="${TEST_DELAY_SECONDS:-0}"
MIN_EXACT_ACCURACY="${MIN_EXACT_ACCURACY:-0}"
INTERNAL_METADATA_FIELDS_JSON='[
  "contentId", "contentType", "imageMatch", "imageMatchScore",
  "ocrText", "aiUsage", "policyVersion"
]'

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
  CONFIRM_LIVE_API=1
  MODERATION_INTERNAL_RESPONSE_TOKEN=<same strong token configured by gateway>
  NO_COLOR=1

Without VALIDATE_ONLY=1, the script refuses to send requests unless
CONFIRM_LIVE_API=1 is explicitly supplied after API-spend approval. A live run
sends one authenticated multipart request per case. It requires the same
MODERATION_INTERNAL_RESPONSE_TOKEN configured by the gateway, prints the text,
expected enums, internal response, and mismatches, and reports aggregate
accuracy. The token itself is never printed.
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
        and ((.parentPostText // "") | type == "string")
        and ((.authorUsername // "") | type == "string")
        and ((.quotedText // "") | type == "string")
        and (.language as $value
            | ["AZ", "EN", "RU", "TR"] | index($value) != null)
        and (.contentType as $value
            | ["POST", "COMMENT", "USERNAME"] | index($value) != null)
        and (
            if .contentType == "COMMENT" then true
            elif .contentType == "POST" then
                ((.parentPostText // "") == ""
                    and (.quotedText // "") == ""
                    and (.authorUsername // "") == "")
            else
                ((.parentPostText // "") == ""
                    and (.quotedText // "") == ""
                    and (.authorUsername // "") == "")
            end
        )
        and (.expected.decision as $value
            | ["ALLOW", "BLOCK", "UNKNOWN"] | index($value) != null)
        and (.expected.violation as $value
            | [
                "NONE", "HARASSMENT", "HATE", "THREAT", "SELF_HARM",
                "SEXUAL", "SEXUAL_MINORS", "GRAPHIC_VIOLENCE", "VIOLENCE",
                "ILLICIT", "SPAM_SCAM", "VULGAR", "IMPERSONATION",
                "POLITICAL_CONTENT", "OFF_TOPIC", "FINANCIAL_PRIVACY", "FINANCIAL_RISK",
                "NOT_INVESTMENT", "KNOWN_IMAGE", "ANALYZER_ERROR",
                "EVIDENCE_UNAVAILABLE", "OTHER"
              ] | index($value) != null)
        and (.expected.reason as $value
            | [
                "NONE", "KNOWN_IMAGE", "SAFETY", "FINANCIAL_PRIVACY",
                "FINANCIAL_RISK", "IMPERSONATION", "POLITICAL_CONTENT", "OFF_TOPIC",
                "EVIDENCE_UNAVAILABLE", "ANALYZER_ERROR"
              ] | index($value) != null)
        and (
            .expected.safetyAction == null
            or (.expected.safetyAction as $value
                | ["ALLOW", "BLOCK", "UNKNOWN"] | index($value) != null)
        )
        and (.expected.safety as $value
            | [
                "NONE", "HARASSMENT", "HATE", "THREAT", "SELF_HARM",
                "SEXUAL", "SEXUAL_MINORS", "GRAPHIC_VIOLENCE", "VIOLENCE",
                "ILLICIT", "SPAM_SCAM", "VULGAR", "OTHER"
              ] | index($value) != null)
        and (.expected.financialRisk as $value
            | [
                "NONE", "POTENTIALLY_MISLEADING", "GUARANTEED_RETURN",
                "INVESTMENT_SCAM", "PUMP_AND_DUMP", "MARKET_MANIPULATION",
                "PHISHING", "PAID_PROMOTION", "UNCERTAIN"
              ] | index($value) != null)
        and (.expected.financialPrivacy as $value
            | ["NONE", "POSSIBLE", "CLEAR"] | index($value) != null)
        and (.expected.impersonation as $value
            | ["NONE", "POSSIBLE", "CLEAR"] | index($value) != null)
        and (
            .expected.restrictedPoliticalEntity == null
            or (.expected.restrictedPoliticalEntity as $value
                | ["NONE", "PRESIDENT", "MINISTER", "YAP", "MULTIPLE", "POSSIBLE"]
                | index($value) != null)
        )
        and (
            if .contentType == "POST" or .contentType == "COMMENT" then
                (.expected | keys | sort)
                    == [
                        "decision", "domain", "financialClaim", "financialPrivacy",
                        "financialRisk", "impersonation", "investment",
                        "politicalContext", "politics", "reason",
                        "restrictedPoliticalEntity", "safety", "safetyAction",
                        "violation"
                      ]
                and (
                    .expected.investment == null
                    or (.expected.investment as $value
                        | ["RELATED", "ADJACENT", "NOT_RELATED", "UNCERTAIN"]
                        | index($value) != null)
                )
                and (
                    .expected.politics == null
                    or (.expected.politics as $value
                        | [
                            "NOT_RELATED", "NEUTRAL_OR_SUPPORTIVE",
                            "CRITICAL_OR_NEGATIVE", "HIGH_RISK", "UNCERTAIN"
                          ] | index($value) != null)
                )
                and (
                    .expected.domain == null
                    or (.expected.domain as $value
                        | [
                            "INVESTMENT_RELATED", "INVESTMENT_ADJACENT",
                            "OFF_TOPIC", "UNCERTAIN"
                          ] | index($value) != null)
                )
                and (
                    .expected.financialClaim == null
                    or (.expected.financialClaim as $value
                        | ["NONE", "OPINION", "ANALYSIS", "FACTUAL_CLAIM", "UNCERTAIN"]
                        | index($value) != null)
                )
                and (
                    .expected.politicalContext == null
                    or (.expected.politicalContext as $value
                        | [
                            "NONE", "INVESTMENT_RELEVANT", "GENERAL_POLITICS",
                            "UNCERTAIN"
                          ] | index($value) != null)
                )
            else
                (.expected | keys | sort)
                    == [
                        "decision", "financialPrivacy", "financialRisk",
                        "impersonation", "reason", "restrictedPoliticalEntity",
                        "safety", "safetyAction", "violation"
                      ]
            end
        )
    )
    and all(.[] | select(.contentType != "USERNAME");
        if .expected.domain == null then
            .expected.investment == null
            and .expected.politics == null
            and .expected.financialClaim == null
            and .expected.politicalContext == null
            and (
                .expected.restrictedPoliticalEntity == null
                or (
                    .expected.restrictedPoliticalEntity as $entity
                    | (["PRESIDENT", "MINISTER", "YAP", "MULTIPLE"]
                        | index($entity)) != null
                      and .expected.decision == "BLOCK"
                      and .expected.violation == "POLITICAL_CONTENT"
                      and .expected.reason == "POLITICAL_CONTENT"
                      and .expected.safetyAction == null
                )
            )
        else
            (
                {
                  "INVESTMENT_RELATED": "RELATED",
                  "INVESTMENT_ADJACENT": "ADJACENT",
                  "OFF_TOPIC": "NOT_RELATED",
                  "UNCERTAIN": "UNCERTAIN"
                }[.expected.domain] == .expected.investment
            )
            and (
                if .expected.politicalContext == "NONE" then
                    .expected.politics == "NOT_RELATED"
                else
                    .expected.politics == "UNCERTAIN"
                end
            )
            and .expected.financialClaim != null
            and .expected.politicalContext != null
            and .expected.restrictedPoliticalEntity != null
        end
    )
    and all(.[] | select(.contentType == "USERNAME");
        if .expected.safetyAction == null then
            .expected.restrictedPoliticalEntity == null
        else
            .expected.restrictedPoliticalEntity != null
        end
    )
    and all(.[];
        if .expected.safetyAction == null then
            .expected.safety == "NONE"
        elif .expected.safetyAction == "ALLOW" then
            .expected.safety == "NONE"
        else
            .expected.safety != "NONE"
        end
    )
    and (
        [
          "POTENTIALLY_MISLEADING", "GUARANTEED_RETURN", "INVESTMENT_SCAM",
          "PUMP_AND_DUMP", "MARKET_MANIPULATION", "PHISHING", "PAID_PROMOTION"
        ]
        - [.[].expected.financialRisk]
        | length == 0
    )
    and (
        ["INVESTMENT_ADJACENT", "OFF_TOPIC"] - [.[].expected.domain]
        | length == 0
    )
    and (
        ["POSSIBLE", "CLEAR"] - [.[].expected.financialPrivacy]
        | length == 0
    )
    and any(.[];
        .contentType == "COMMENT"
        and ((.parentPostText // "") | length > 0)
    )
    and any(.[];
        .expected.impersonation == "CLEAR"
    )
    and any(.[];
        .expected.decision == "BLOCK"
        and .expected.safetyAction == "ALLOW"
    )
    and any(.[];
        .expected.decision == "UNKNOWN"
        and .expected.safetyAction == "ALLOW"
    )
    and any(.[];
        .expected.safetyAction == "UNKNOWN"
    )
    and any(.[];
        .expected.safetyAction == null
    )
    and any(.[];
        .expected.restrictedPoliticalEntity == "PRESIDENT"
    )
    and any(.[];
        .expected.restrictedPoliticalEntity == "MINISTER"
    )
    and any(.[];
        .expected.restrictedPoliticalEntity == "YAP"
    )
    and any(.[];
        .expected.restrictedPoliticalEntity == "MULTIPLE"
    )
    and any(.[];
        .expected.restrictedPoliticalEntity == "POSSIBLE"
        and .expected.decision == "UNKNOWN"
        and .expected.violation == "POLITICAL_CONTENT"
    )
    and (
        ([.[] | select(.id == "post-en-001")] | length) == 0
        or any(.[];
            .id == "post-en-001"
            and .expected.restrictedPoliticalEntity == "NONE"
            and .expected.violation == "OFF_TOPIC"
        )
    )
    and (
        ([.[] | select(.id == "comment-en-003")] | length) == 0
        or any(.[];
            .id == "comment-en-003"
            and .expected.restrictedPoliticalEntity == "NONE"
            and .expected.violation == "OFF_TOPIC"
        )
    )
    and (
        ([.[] | select(.id == "post-tr-003")] | length) == 0
        or any(.[];
            .id == "post-tr-003"
            and (.text | contains("yap"))
            and .expected.restrictedPoliticalEntity == "NONE"
            and .expected.decision == "ALLOW"
        )
    )
    and any(.[];
        .expected.violation == "VULGAR"
    )
    and any(.[];
        .expected.violation == "POLITICAL_CONTENT"
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
    jq -r -s '
        group_by(.expected.safetyAction)[]
        | "  safetyAction=\(.[0].expected.safetyAction // "OMITTED"): \(length)"
    ' "$DATASET"
    jq -r -s '
        group_by(.expected.restrictedPoliticalEntity)[]
        | "  restrictedPoliticalEntity=\(.[0].expected.restrictedPoliticalEntity // "OMITTED"): \(length)"
    ' "$DATASET"
    jq -r -s '
        . as $cases
        | ["BLOCK", "UNKNOWN"][]
        | . as $decision
        | [
            $cases[]
            | select(
                .expected.decision == $decision
                and .expected.safetyAction == "ALLOW"
              )
          ]
        | "  non-safety \($decision) with safetyAction=ALLOW: \(length)"
    ' "$DATASET"
    exit 0
fi

if [[ "${CONFIRM_LIVE_API:-0}" != "1" ]]; then
    printf '%s\n' \
        'Refusing live accuracy requests: obtain API-spend approval, then set CONFIRM_LIVE_API=1.' >&2
    exit 2
fi

if [[ -z "${MODERATION_INTERNAL_RESPONSE_TOKEN:-}" ]]; then
    printf '%s\n' \
        'Refusing live accuracy requests: MODERATION_INTERNAL_RESPONSE_TOKEN is required.' >&2
    exit 2
fi
internal_token_length="${#MODERATION_INTERNAL_RESPONSE_TOKEN}"
if [[ "$internal_token_length" -lt 43 \
        || "$internal_token_length" -gt 256 \
        || "$MODERATION_INTERNAL_RESPONSE_TOKEN" =~ [^A-Za-z0-9_-] ]]; then
    printf '%s\n' \
        'Refusing live accuracy requests: MODERATION_INTERNAL_RESPONSE_TOKEN has an invalid format.' >&2
    exit 2
fi

# Copy the secret into a non-exported shell variable, then remove the inherited
# environment variable before curl or any other child process starts.
internal_response_token="$MODERATION_INTERNAL_RESPONSE_TOKEN"
unset MODERATION_INTERNAL_RESPONSE_TOKEN

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
    unset internal_response_token
    if [[ -n "${work_dir:-}" && -d "$work_dir" ]]; then
        rm -rf -- "$work_dir"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

internal_header_file="$work_dir/internal-response-header"
if ! (umask 077 && printf 'X-Moderation-Internal-Token: %s\n' \
        "$internal_response_token" >"$internal_header_file"); then
    printf '%s\n' 'Unable to prepare the internal response credential.' >&2
    exit 1
fi

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
reason_correct=0
reason_total=0
domain_correct=0
domain_total=0
safety_correct=0
safety_total=0
safety_action_correct=0
safety_action_total=0
financial_claim_correct=0
financial_claim_total=0
financial_risk_correct=0
financial_risk_total=0
financial_privacy_correct=0
financial_privacy_total=0
impersonation_correct=0
impersonation_total=0
political_context_correct=0
political_context_total=0
restricted_political_entity_correct=0
restricted_political_entity_total=0

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
    parent_post_text="$(jq -r '.parentPostText // ""' <<<"$test_case")"
    author_username="$(jq -r '.authorUsername // ""' <<<"$test_case")"
    quoted_text="$(jq -r '.quotedText // ""' <<<"$test_case")"
    expected_json="$(jq -cS '.expected' <<<"$test_case")"
    expected_fields_json="$(jq -c '.expected | keys' <<<"$test_case")"

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
    curl_args=(
        -sS
        --max-time "$REQUEST_TIMEOUT_SECONDS"
        -o "$body_file"
        -w '%{http_code}'
        --header "@$internal_header_file"
        "$BASE_URL/v1/moderate"
        --form-string "contentId=$case_id"
        --form-string "contentType=$content_type"
        --form-string "text=$text_value"
    )
    if [[ -n "$parent_post_text" ]]; then
        curl_args+=(--form-string "parentPostText=$parent_post_text")
    fi
    if [[ -n "$author_username" ]]; then
        curl_args+=(--form-string "authorUsername=$author_username")
    fi
    if [[ -n "$quoted_text" ]]; then
        curl_args+=(--form-string "quotedText=$quoted_text")
    fi
    http_code="$(
        curl "${curl_args[@]}" 2>"$error_file"
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
    response_text="${response_text//$internal_response_token/[REDACTED]}"

    printf '\n%s[%03d/%03d] %s | %s | %s%s\n' \
        "$COLOR_BOLD" "$index" "$case_count" "$case_id" \
        "$content_type" "$language" "$COLOR_RESET"
    printf 'Text:     %s\n' "$text_value"
    if [[ -n "$parent_post_text" ]]; then
        printf 'Parent:   %s\n' "$parent_post_text"
    fi
    if [[ -n "$author_username" ]]; then
        printf 'Author:   %s\n' "$author_username"
    fi
    if [[ -n "$quoted_text" ]]; then
        printf 'Quoted:   %s\n' "$quoted_text"
    fi
    printf 'Expected: %s\n' "$expected_json"
    printf 'Response: %s\n' "$response_text"

    case_pass=true
    if [[ "$curl_exit" -eq 0 && "$http_code" == "200" && "$response_is_json" == true ]]; then
        api_success=$((api_success + 1))
    else
        case_pass=false
        curl_error="$(tr '\r\n' '  ' <"$error_file")"
        curl_error="${curl_error//$internal_response_token/[REDACTED]}"
        printf '%sAPI error:%s curl=%s http=%s %s\n' \
            "$COLOR_RED" "$COLOR_RESET" "$curl_exit" \
            "${http_code:-000}" "$curl_error"
    fi

    contract_total=$((contract_total + 1))
    if [[ "$response_is_json" == true ]] \
        && jq -e \
            --arg expected_content_id "$case_id" \
            --arg expected_content_type "$content_type" \
            --argjson expected_fields "$expected_fields_json" \
            --argjson metadata_fields "$INTERNAL_METADATA_FIELDS_JSON" '
                type == "object"
                and ((keys - ($expected_fields + $metadata_fields)) | length == 0)
                and .contentId == $expected_content_id
                and .contentType == $expected_content_type
                and (.aiUsage | type == "object")
                and (.policyVersion | type == "string" and length > 0)
                and (
                    if has("imageMatch") then
                        .imageMatch as $value
                        | [
                            "EXACT_MATCH", "SIMILAR_CANDIDATE", "MATCHED",
                            "NOT_MATCHED", "LOW_QUALITY", "UNAVAILABLE"
                          ]
                        | index($value) != null
                    else true end
                )
                and (
                    if has("imageMatchScore") then
                        (.imageMatchScore | type == "number"
                            and floor == . and . >= 0 and . <= 100)
                    else true end
                )
                and (
                    if has("ocrText") then (.ocrText | type == "string")
                    else true end
                )
            ' \
            "$body_file" >/dev/null 2>&1; then
        contract_correct=$((contract_correct + 1))
    else
        case_pass=false
        printf '%sMismatch:%s invalid authenticated internal response contract\n' \
            "$COLOR_YELLOW" "$COLOR_RESET"
    fi

    while IFS= read -r field_name; do
        expected_value="$(
            jq -r --arg field "$field_name" \
                'if (.expected | has($field)) then
                    if .expected[$field] == null then
                        "__EXPECTED_OMITTED__"
                    else
                        .expected[$field]
                    end
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
            reason) reason_total=$((reason_total + 1)) ;;
            domain) domain_total=$((domain_total + 1)) ;;
            safetyAction) safety_action_total=$((safety_action_total + 1)) ;;
            safety) safety_total=$((safety_total + 1)) ;;
            financialClaim) financial_claim_total=$((financial_claim_total + 1)) ;;
            financialRisk) financial_risk_total=$((financial_risk_total + 1)) ;;
            financialPrivacy) financial_privacy_total=$((financial_privacy_total + 1)) ;;
            impersonation) impersonation_total=$((impersonation_total + 1)) ;;
            politicalContext) political_context_total=$((political_context_total + 1)) ;;
            restrictedPoliticalEntity) restricted_political_entity_total=$((restricted_political_entity_total + 1)) ;;
            investment) investment_total=$((investment_total + 1)) ;;
            politics) politics_total=$((politics_total + 1)) ;;
        esac

        if [[ "$response_is_json" == true && "$expected_value" == "__EXPECTED_OMITTED__" ]]; then
            actual_value="$(
                jq -r --arg field "$field_name" 'if has($field) then "__UNEXPECTED_PRESENT__:" + (.[$field] | tostring) else "__EXPECTED_OMITTED__" end' "$body_file"
            )"
        elif [[ "$response_is_json" == true ]]; then
            actual_value="$(
                jq -r --arg field "$field_name" \
                    'if has($field) then .[$field] else "__MISSING__" end' \
                    "$body_file"
            )"
        else
            actual_value="__MISSING__"
        fi
        actual_value="${actual_value//$internal_response_token/[REDACTED]}"

        if [[ "$actual_value" == "$expected_value" ]]; then
            label_correct=$((label_correct + 1))
            case "$field_name" in
                decision) decision_correct=$((decision_correct + 1)) ;;
                violation) violation_correct=$((violation_correct + 1)) ;;
                reason) reason_correct=$((reason_correct + 1)) ;;
                domain) domain_correct=$((domain_correct + 1)) ;;
                safetyAction) safety_action_correct=$((safety_action_correct + 1)) ;;
                safety) safety_correct=$((safety_correct + 1)) ;;
                financialClaim) financial_claim_correct=$((financial_claim_correct + 1)) ;;
                financialRisk) financial_risk_correct=$((financial_risk_correct + 1)) ;;
                financialPrivacy) financial_privacy_correct=$((financial_privacy_correct + 1)) ;;
                impersonation) impersonation_correct=$((impersonation_correct + 1)) ;;
                politicalContext) political_context_correct=$((political_context_correct + 1)) ;;
                restrictedPoliticalEntity) restricted_political_entity_correct=$((restricted_political_entity_correct + 1)) ;;
                investment) investment_correct=$((investment_correct + 1)) ;;
                politics) politics_correct=$((politics_correct + 1)) ;;
            esac
        else
            case_pass=false
            printf '%sMismatch:%s %s expected=%s actual=%s\n' \
                "$COLOR_YELLOW" "$COLOR_RESET" "$field_name" \
                "$expected_value" "$actual_value"
        fi
    done < <(jq -r '.expected | keys[]' <<<"$test_case")

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
print_metric "reason" "$reason_correct" "$reason_total"
print_metric "domain" "$domain_correct" "$domain_total"
print_metric "safetyAction" "$safety_action_correct" "$safety_action_total"
print_metric "safety" "$safety_correct" "$safety_total"
print_metric "financialClaim" "$financial_claim_correct" "$financial_claim_total"
print_metric "financialRisk" "$financial_risk_correct" "$financial_risk_total"
print_metric "financialPrivacy" "$financial_privacy_correct" "$financial_privacy_total"
print_metric "impersonation" "$impersonation_correct" "$impersonation_total"
print_metric "politicalContext" "$political_context_correct" "$political_context_total"
print_metric "restrictedPoliticalEntity" \
    "$restricted_political_entity_correct" "$restricted_political_entity_total"
print_metric "investment" "$investment_correct" "$investment_total"
print_metric "politics" "$politics_correct" "$politics_total"

exact_accuracy="$(percentage "$exact_correct" "$case_count")"
api_failures=$((case_count - api_success))
if [[ "$api_failures" -gt 0 ]]; then
    printf '\n%sCompleted with %d API failures.%s\n' \
        "$COLOR_RED" "$api_failures" "$COLOR_RESET" >&2
    exit 2
fi

contract_failures=$((contract_total - contract_correct))
if [[ "$contract_failures" -gt 0 ]]; then
    printf '\n%sCompleted with %d internal response contract failures.%s\n' \
        "$COLOR_RED" "$contract_failures" "$COLOR_RESET" >&2
    exit 4
fi

if ! awk -v actual="$exact_accuracy" -v minimum="$MIN_EXACT_ACCURACY" \
    'BEGIN { exit !(actual + 0 >= minimum + 0) }'; then
    printf '\n%sExact accuracy %s%% is below required %s%%.%s\n' \
        "$COLOR_RED" "$exact_accuracy" "$MIN_EXACT_ACCURACY" \
        "$COLOR_RESET" >&2
    exit 3
fi
