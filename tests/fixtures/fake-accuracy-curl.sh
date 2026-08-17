#!/usr/bin/env bash

set -euo pipefail

: "${ACCURACY_SELF_TEST_DATASET:?}"
: "${ACCURACY_SELF_TEST_EXPECTED_HEADER:?}"
: "${ACCURACY_SELF_TEST_MARKER:?}"

if [[ -n "${MODERATION_INTERNAL_RESPONSE_TOKEN+x}" \
        || -n "${internal_response_token+x}" ]]; then
    exit 96
fi

body_file=""
header_source=""
case_id=""
content_type=""

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        -sS)
            shift
            ;;
        --max-time|-o|-w|--header|--form-string)
            option="$1"
            value="${2:-}"
            case "$option" in
                -o) body_file="$value" ;;
                --header) header_source="$value" ;;
                --form-string)
                    case "$value" in
                        contentId=*) case_id="${value#contentId=}" ;;
                        contentType=*) content_type="${value#contentType=}" ;;
                    esac
                    ;;
            esac
            shift 2
            ;;
        http://*|https://*)
            shift
            ;;
        *)
            exit 95
            ;;
    esac
done

if [[ -z "$body_file" || -z "$case_id" || -z "$content_type" \
        || "$header_source" != @* ]]; then
    exit 94
fi

header_file="${header_source#@}"
IFS= read -r supplied_header <"$header_file"
if [[ "$supplied_header" != "$ACCURACY_SELF_TEST_EXPECTED_HEADER" ]]; then
    exit 93
fi

jq -c \
    --arg case_id "$case_id" \
    --arg content_type "$content_type" \
    --arg bad_contract "${ACCURACY_SELF_TEST_BAD_CONTRACT:-0}" '
        select(.id == $case_id)
        | (.expected | with_entries(select(.value != null)))
            + {
                contentId: $case_id,
                contentType: $content_type,
                aiUsage: {
                    meteredCalls: 0,
                    freeModerationCalls: 0,
                    inputTokens: 0,
                    cachedInputTokens: 0,
                    cacheWriteTokens: 0,
                    outputTokens: 0,
                    reasoningTokens: 0,
                    totalTokens: 0,
                    estimatedCostUsd: 0,
                    currency: "USD",
                    pricingVersion: "self-test",
                    usageComplete: true,
                    costComplete: true,
                    modelCalls: []
                  },
                policyVersion: "accuracy-self-test"
              }
        | if $bad_contract == "1" then .unexpectedField = true else . end
    ' "$ACCURACY_SELF_TEST_DATASET" >"$body_file"

if [[ ! -s "$body_file" ]]; then
    exit 92
fi

printf 'request\n' >>"$ACCURACY_SELF_TEST_MARKER"
printf '200'
