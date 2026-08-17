#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="$SCRIPT_DIR/accuracy/realistic-500"
FILES=(
  "AZ:$DATA_DIR/az-125.jsonl"
  "EN:$DATA_DIR/en-125.jsonl"
  "RU:$DATA_DIR/ru-125.jsonl"
  "TR:$DATA_DIR/tr-125.jsonl"
)

for entry in "${FILES[@]}"; do
  language="${entry%%:*}"
  file="${entry#*:}"
  if [[ ! -f "$file" ]]; then
    printf 'Missing realistic benchmark shard: %s\n' "$file" >&2
    exit 1
  fi
  if ! jq -e -c . "$file" >/dev/null; then
    printf 'Invalid JSONL in %s\n' "$file" >&2
    exit 1
  fi
  if ! jq -e -s --arg language "$language" '
    def includes_all($required; $actual):
      ($required - $actual | length) == 0;

    length == 125
    and ([.[].id] | unique | length) == 125
    and ([.[].text] | unique | length) == 125
    and all(.[]; .language == $language)
    and ([.[] | select(.contentType == "POST")] | length) == 65
    and ([.[] | select(.contentType == "COMMENT")] | length) == 40
    and ([.[] | select(.contentType == "USERNAME")] | length) == 20
    and ([.[] | select(.expected.decision == "ALLOW")] | length) >= 40
    and ([.[] | select(.expected.decision == "BLOCK")] | length) >= 35
    and ([.[] | select(.expected.decision == "UNKNOWN")] | length) >= 8
    and includes_all(
      [
        "NONE", "POTENTIALLY_MISLEADING", "GUARANTEED_RETURN",
        "INVESTMENT_SCAM", "PUMP_AND_DUMP", "MARKET_MANIPULATION",
        "PHISHING", "PAID_PROMOTION", "UNCERTAIN"
      ];
      [.[].expected.financialRisk] | unique
    )
    and includes_all(
      ["POSSIBLE", "CLEAR"];
      [.[].expected.financialPrivacy] | unique
    )
  ' "$file" >/dev/null; then
    printf 'Shard count, distribution, uniqueness, or coverage failed: %s\n' "$file" >&2
    exit 1
  fi
done

file_paths=()
for entry in "${FILES[@]}"; do
  file_paths+=("${entry#*:}")
done

if ! jq -e -s '
  def member($values): . as $value | ($values | index($value)) != null;
  def includes_all($required; $actual):
    ($required - $actual | length) == 0;
  def absent_or_string($name):
    (has($name) | not) or (.[$name] | type == "string");
  def absent_or_empty($name):
    (has($name) | not) or .[$name] == "";
  def safety_violation:
    {
      "HARASSMENT": "HARASSMENT",
      "HATE": "HATE",
      "THREAT": "THREAT",
      "SELF_HARM": "SELF_HARM",
      "SEXUAL": "SEXUAL",
      "SEXUAL_MINORS": "SEXUAL_MINORS",
      "GRAPHIC_VIOLENCE": "GRAPHIC_VIOLENCE",
      "VIOLENCE": "VIOLENCE",
      "ILLICIT": "ILLICIT",
      "SPAM_SCAM": "SPAM_SCAM",
      "VULGAR": "VULGAR",
      "OTHER": "OTHER"
    }[.expected.safety] == .expected.violation;
  def legacy_domain_coherent:
    if .expected.domain == null then
      .expected.investment == null
      and .expected.politics == null
      and .expected.financialClaim == null
      and .expected.politicalContext == null
    else
      ({
        "INVESTMENT_RELATED": "RELATED",
        "INVESTMENT_ADJACENT": "ADJACENT",
        "OFF_TOPIC": "NOT_RELATED",
        "UNCERTAIN": "UNCERTAIN"
      }[.expected.domain] == .expected.investment)
      and (
        if .expected.politicalContext == "NONE" then
          .expected.politics == "NOT_RELATED"
        else
          .expected.politics == "UNCERTAIN"
        end
      )
    end;
  def reducer_coherent:
    if .expected.decision == "ALLOW" then
      .expected.violation == "NONE"
      and .expected.reason == "NONE"
      and .expected.safetyAction == "ALLOW"
      and .expected.safety == "NONE"
      and .expected.financialRisk == "NONE"
      and .expected.financialPrivacy == "NONE"
      and .expected.impersonation == "NONE"
      and .expected.restrictedPoliticalEntity == "NONE"
    elif .expected.reason == "SAFETY" then
      (.expected.decision | member(["BLOCK", "UNKNOWN"]))
      and .expected.safetyAction == .expected.decision
      and .expected.safety != "NONE"
      and safety_violation
    elif .expected.reason == "FINANCIAL_PRIVACY" then
      .expected.safety == "NONE"
      and (
        (.expected.decision == "BLOCK" and .expected.financialPrivacy == "CLEAR")
        or (.expected.decision == "UNKNOWN" and .expected.financialPrivacy == "POSSIBLE")
      )
    elif .expected.reason == "FINANCIAL_RISK" then
      .expected.safety == "NONE"
      and .expected.financialPrivacy != "CLEAR"
      and (
        (
          .expected.decision == "BLOCK"
          and (.expected.financialRisk | member([
            "GUARANTEED_RETURN", "INVESTMENT_SCAM", "PUMP_AND_DUMP",
            "MARKET_MANIPULATION", "PHISHING"
          ]))
          and (
            if (.expected.financialRisk | member(["GUARANTEED_RETURN", "INVESTMENT_SCAM", "PHISHING"]))
            then .expected.violation == "SPAM_SCAM"
            else .expected.violation == "FINANCIAL_RISK"
            end
          )
        )
        or (
          .expected.decision == "UNKNOWN"
          and .expected.violation == "FINANCIAL_RISK"
          and (.expected.financialRisk | member([
            "POTENTIALLY_MISLEADING", "PAID_PROMOTION", "UNCERTAIN"
          ]))
        )
      )
    elif .expected.reason == "IMPERSONATION" then
      .expected.safety == "NONE"
      and .expected.financialPrivacy != "CLEAR"
      and (.expected.financialRisk | member(["NONE", "POTENTIALLY_MISLEADING", "PAID_PROMOTION", "UNCERTAIN"]))
      and .expected.violation == "IMPERSONATION"
      and (
        (.expected.decision == "BLOCK" and .expected.impersonation == "CLEAR")
        or (.expected.decision == "UNKNOWN" and .expected.impersonation == "POSSIBLE")
      )
    elif .expected.reason == "POLITICAL_CONTENT" then
      .expected.safety == "NONE"
      and .expected.financialPrivacy != "CLEAR"
      and (.expected.financialRisk | member(["NONE", "POTENTIALLY_MISLEADING", "PAID_PROMOTION", "UNCERTAIN"]))
      and .expected.impersonation != "CLEAR"
      and .expected.violation == "POLITICAL_CONTENT"
      and (
        (
          .expected.decision == "BLOCK"
          and (.expected.restrictedPoliticalEntity | member([
            "PRESIDENT", "MINISTER", "YAP", "MULTIPLE"
          ]))
        )
        or (
          .expected.decision == "UNKNOWN"
          and .expected.restrictedPoliticalEntity == "POSSIBLE"
        )
      )
    elif .expected.reason == "OFF_TOPIC" then
      .expected.safety == "NONE"
      and .expected.financialRisk == "NONE"
      and .expected.financialPrivacy == "NONE"
      and .expected.impersonation == "NONE"
      and .expected.restrictedPoliticalEntity == "NONE"
      and .expected.violation == "OFF_TOPIC"
      and (
        (.expected.decision == "BLOCK" and .expected.domain == "OFF_TOPIC")
        or (.expected.decision == "UNKNOWN" and .expected.domain == "UNCERTAIN")
      )
    else false end;

  length == 500
  and ([.[].id] | unique | length) == 500
  and ([.[].text] | unique | length) == 500
  and includes_all(
    [
      "NONE", "HARASSMENT", "HATE", "THREAT", "SELF_HARM", "SEXUAL",
      "SEXUAL_MINORS", "GRAPHIC_VIOLENCE", "VIOLENCE", "ILLICIT",
      "SPAM_SCAM", "VULGAR", "OTHER"
    ];
    [.[].expected.safety] | unique
  )
  and includes_all(
    [
      "NONE", "SAFETY", "FINANCIAL_PRIVACY", "FINANCIAL_RISK",
      "IMPERSONATION", "POLITICAL_CONTENT", "OFF_TOPIC"
    ];
    [.[].expected.reason] | unique
  )
  and includes_all(
    ["NONE", "PRESIDENT", "MINISTER", "YAP", "MULTIPLE", "POSSIBLE"];
    [.[].expected.restrictedPoliticalEntity | select(. != null)] | unique
  )
  and all(.[];
    (. | keys - [
      "id", "contentType", "language", "text", "parentPostText",
      "authorUsername", "quotedText", "expected"
    ] | length) == 0
    and (.id | type == "string" and test("^real-(az|en|ru|tr)-(post|comment|username)-[0-9]{3}$"))
    and (.contentType | member(["POST", "COMMENT", "USERNAME"]))
    and (.language | member(["AZ", "EN", "RU", "TR"]))
    and (.text | type == "string" and length > 0 and length <= 20000)
    and (.text | explode | all(
      . != 8203 and . != 8204 and . != 8205 and . != 8288 and . != 65279
    ))
    and absent_or_string("parentPostText")
    and absent_or_string("authorUsername")
    and absent_or_string("quotedText")
    and (
      if .contentType == "COMMENT" then true
      else absent_or_empty("parentPostText")
        and absent_or_empty("authorUsername")
        and absent_or_empty("quotedText")
      end
    )
    and (
      if .contentType == "USERNAME" then
        (.expected | keys | sort) == [
          "decision", "financialPrivacy", "financialRisk", "impersonation",
          "reason", "restrictedPoliticalEntity", "safety", "safetyAction",
          "violation"
        ]
      else
        (.expected | keys | sort) == [
          "decision", "domain", "financialClaim", "financialPrivacy",
          "financialRisk", "impersonation", "investment", "politicalContext",
          "politics", "reason", "restrictedPoliticalEntity", "safety",
          "safetyAction", "violation"
        ]
        and legacy_domain_coherent
      end
    )
    and (.expected.decision | member(["ALLOW", "BLOCK", "UNKNOWN"]))
    and (.expected.violation | member([
      "NONE", "HARASSMENT", "HATE", "THREAT", "SELF_HARM", "SEXUAL",
      "SEXUAL_MINORS", "GRAPHIC_VIOLENCE", "VIOLENCE", "ILLICIT",
      "SPAM_SCAM", "VULGAR", "IMPERSONATION", "POLITICAL_CONTENT",
      "OFF_TOPIC", "FINANCIAL_PRIVACY", "FINANCIAL_RISK", "OTHER"
    ]))
    and (.expected.reason | member([
      "NONE", "SAFETY", "FINANCIAL_PRIVACY", "FINANCIAL_RISK",
      "IMPERSONATION", "POLITICAL_CONTENT", "OFF_TOPIC"
    ]))
    and ((.expected.safetyAction == null) or (.expected.safetyAction | member(["ALLOW", "BLOCK", "UNKNOWN"])))
    and (.expected.safety | member([
      "NONE", "HARASSMENT", "HATE", "THREAT", "SELF_HARM", "SEXUAL",
      "SEXUAL_MINORS", "GRAPHIC_VIOLENCE", "VIOLENCE", "ILLICIT",
      "SPAM_SCAM", "VULGAR", "OTHER"
    ]))
    and (.expected.financialRisk | member([
      "NONE", "POTENTIALLY_MISLEADING", "GUARANTEED_RETURN",
      "INVESTMENT_SCAM", "PUMP_AND_DUMP", "MARKET_MANIPULATION",
      "PHISHING", "PAID_PROMOTION", "UNCERTAIN"
    ]))
    and (.expected.financialPrivacy | member(["NONE", "POSSIBLE", "CLEAR"]))
    and (.expected.impersonation | member(["NONE", "POSSIBLE", "CLEAR"]))
    and ((.expected.restrictedPoliticalEntity == null) or (.expected.restrictedPoliticalEntity | member([
      "NONE", "PRESIDENT", "MINISTER", "YAP", "MULTIPLE", "POSSIBLE"
    ])))
    and reducer_coherent
  )
' "${file_paths[@]}" >/dev/null; then
  printf '%s\n' 'Aggregate schema, enum, uniqueness, or reducer-coherence validation failed.' >&2
  exit 1
fi

printf 'Realistic benchmark valid: 500 cases across 4 language shards.\n'
for entry in "${FILES[@]}"; do
  language="${entry%%:*}"
  file="${entry#*:}"
  jq -r -s --arg language "$language" '
    group_by(.expected.decision)
    | map("\(.[0].expected.decision)=\(length)")
    | "  \($language): 125 cases; " + join(", ")
  ' "$file"
done
