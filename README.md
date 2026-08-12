## Services

- `gateway` - public API and final decision
- `ai-service` - OpenAI checks
- `media-service` - image validation, OCR, exact identity and candidate fusion
- `visual-retrieval` - candidate-only ORB/LSH retrieval with geometric verification
- `moderation-db` - PostgreSQL for reference metadata, descriptors and append-only audits

## Run

```bash
cp .env.example .env
```

Set `OPENAI_API_KEY` and `POSTGRES_PASSWORD`. Review the tracked
`config/moderation_terms.txt` policy list; do not place secrets in it. The
tracked file contains only the small reserved impersonation-name policy. You
can add private deployment-specific rules without committing them by setting
`MODERATION_TERMS_FILE` to an untracked host file before starting Compose.
Multilingual abuse, slang,
transliteration, obfuscation, and text extracted from images are classified
semantically by the AI policy. Then start:

```bash
docker compose up --build -d
docker compose ps
```

The env file is intentionally minimal. Compose owns the reviewed local defaults
for models, policy-profile hashes, thresholds, image limits, OCR and timeouts;
override them through deployment configuration only when intentionally changing
policy or capacity. Production must also set
`VISUAL_RETRIEVAL_ALLOW_UNAUTHENTICATED=false` and provide the same strong
`VISUAL_RETRIEVAL_INTERNAL_TOKEN` to the media and visual-retrieval services.

Swagger UI: <http://localhost:8080/swagger-ui.html>

## API

`POST /v1/moderate` uses `multipart/form-data`.

```bash
curl http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=This post is about ETF investment.'
```

`contentType`: `POST`, `COMMENT` or `USERNAME`.
Images: JPEG, PNG or GIF, only for posts.

Comments can carry bounded conversation context:

```bash
curl http://localhost:8080/v1/moderate \
  -F 'contentId=comment-1002' \
  -F 'contentType=COMMENT' \
  -F 'text=I disagree; its valuation is too high.' \
  -F 'parentPostText=What do you think about NVIDIA after earnings?' \
  -F 'authorUsername=value_investor_az' \
  -F 'quotedText=This stock cannot lose money.'
```

`parentPostText` (20,000 characters), `quotedText` (10,000 characters),
and `authorUsername` (128 characters) are accepted only for comments. These
context fields resolve
references and domain relevance; none is attributed to the comment author
unless the comment endorses it. Sensitive data visibly republished in
`quotedText` is nevertheless treated as current exposure, regardless of
ownership or endorsement. A reply such as “I disagree” can therefore
inherit an investment discussion's context, while “my dog is cute” remains
off-topic.

### Moderation contract

The public policy is `investment-community-policy-v2`. Domain and safety are
independent: an investment scam can be both `INVESTMENT_RELATED` and
`SPAM_SCAM`, while a benign restaurant post can be `OFF_TOPIC` with
`safety=NONE`.

A representative safe response is:

```json
{
  "contentId": "post-1001",
  "contentType": "POST",
  "decision": "ALLOW",
  "violation": "NONE",
  "investment": "RELATED",
  "politics": "NOT_RELATED",
  "reason": "NONE",
  "domain": "INVESTMENT_RELATED",
  "safetyAction": "ALLOW",
  "safety": "NONE",
  "financialClaim": "ANALYSIS",
  "financialRisk": "NONE",
  "financialPrivacy": "NONE",
  "impersonation": "NONE",
  "politicalContext": "NONE",
  "aiUsage": {},
  "policyVersion": "investment-community-policy-v2"
}
```

The response enums are:

| Field | Values |
|---|---|
| `decision` | `ALLOW`, `BLOCK`, `UNKNOWN` |
| `violation` | `NONE`, the safety values below, `IMPERSONATION`, `OFF_TOPIC`, `FINANCIAL_PRIVACY`, `FINANCIAL_RISK`, `KNOWN_IMAGE`, `EVIDENCE_UNAVAILABLE`, `ANALYZER_ERROR`, `OTHER` (deprecated compatibility value: `NOT_INVESTMENT`) |
| `reason` | `NONE`, `KNOWN_IMAGE`, `SAFETY`, `FINANCIAL_PRIVACY`, `FINANCIAL_RISK`, `IMPERSONATION`, `OFF_TOPIC`, `EVIDENCE_UNAVAILABLE`, `ANALYZER_ERROR` |
| `domain` | `INVESTMENT_RELATED`, `INVESTMENT_ADJACENT`, `OFF_TOPIC`, `UNCERTAIN` |
| `safetyAction` | `ALLOW`, `BLOCK`, `UNKNOWN`; omitted when safety was not evaluated |
| `safety` | `NONE`, `HARASSMENT`, `HATE`, `THREAT`, `SELF_HARM`, `SEXUAL`, `SEXUAL_MINORS`, `GRAPHIC_VIOLENCE`, `VIOLENCE`, `ILLICIT`, `SPAM_SCAM`, `VULGAR`, `OTHER` |
| `financialClaim` | `NONE`, `OPINION`, `ANALYSIS`, `FACTUAL_CLAIM`, `UNCERTAIN` |
| `financialRisk` | `NONE`, `POTENTIALLY_MISLEADING`, `GUARANTEED_RETURN`, `INVESTMENT_SCAM`, `PUMP_AND_DUMP`, `MARKET_MANIPULATION`, `PHISHING`, `PAID_PROMOTION`, `UNCERTAIN` |
| `financialPrivacy` | `NONE`, `POSSIBLE`, `CLEAR` |
| `impersonation` | `NONE`, `POSSIBLE`, `CLEAR` |
| `politicalContext` | `NONE`, `INVESTMENT_RELEVANT`, `GENERAL_POLITICS`, `UNCERTAIN` |
| legacy `investment` | `RELATED`, `ADJACENT`, `NOT_RELATED`, `UNCERTAIN` |
| legacy `politics` | `NOT_RELATED`, `NEUTRAL_OR_SUPPORTIVE`, `CRITICAL_OR_NEGATIVE`, `HIGH_RISK`, `UNCERTAIN`; the v2 reducer emits only `NOT_RELATED` or `UNCERTAIN` |

`domain`, `financialClaim`, and `politicalContext` are not applicable to
`USERNAME`. Those fields and the legacy relevance fields are also omitted
when a deterministic terminal rule resolves content before domain analysis,
such as clear text-only financial privacy. Omission means “not evaluated,” not
`UNCERTAIN`. The deprecated `investment` and `politics` fields remain for
rolling clients. When evaluated, their governed mapping is
`INVESTMENT_RELATED → RELATED`, `INVESTMENT_ADJACENT → ADJACENT`,
`OFF_TOPIC → NOT_RELATED`, and `UNCERTAIN → UNCERTAIN`; only
`politicalContext=NONE` maps to `politics=NOT_RELATED`, while every other
political-context value maps to legacy `UNCERTAIN`. New clients should use
`domain` and `politicalContext`. `violation=NOT_INVESTMENT` is deprecated;
new off-topic decisions use `OFF_TOPIC`.

`safetyAction` is the independent safety-axis outcome, not an alias for the
overall `decision`. For example, benign off-topic content is
`decision=BLOCK`, `reason=OFF_TOPIC`, `safetyAction=ALLOW`, and
`safety=NONE`; a potentially misleading investment claim is
`decision=UNKNOWN`, `reason=FINANCIAL_RISK`, while its
`safetyAction` can still be `ALLOW`. `safetyAction=BLOCK` or `UNKNOWN`
requires a non-`NONE` safety category. The field is omitted—not set to
`ALLOW`—when a deterministic non-safety rule resolves the request before
safety is evaluated, including authoritative exact-image and local
privacy/impersonation short circuits. Callers must treat omission as “not
evaluated,” not as evidence of safety.

The deterministic reducer records all axes, then selects one final outcome in
this precedence: authoritative exact asset, safety/local rule, clear financial
privacy, decisive financial risk, clear impersonation, then off-topic domain.
`POTENTIALLY_MISLEADING` and `PAID_PROMOTION` return `UNKNOWN`, as do
possible privacy/impersonation and uncertain domain/risk. `GUARANTEED_RETURN`,
`INVESTMENT_SCAM`, `PUMP_AND_DUMP`, `MARKET_MANIPULATION`, and
`PHISHING` block. Safe `INVESTMENT_RELATED` and
`INVESTMENT_ADJACENT` content is allowed.

Financial-privacy detection covers usable or plainly sensitive cards, CVV/PIN,
credentials, one-time codes, recovery phrases/private keys, bank or brokerage
account identifiers, IBANs, tax IDs, access tokens, and identifying financial
screenshots. Clear exposure blocks; partial, masked, or ambiguous exposure is
`UNKNOWN`. Text and image OCR are scanned independently, and OCR text is
omitted from the public response whenever sensitive financial data is possible
or clear.

Every successful response includes `aiUsage`. Token counts come from the
provider response rather than local estimation. `estimatedCostUsd` applies the
actual reported service tier to the dated `pricingVersion`. Metered Responses
requests explicitly select the `default` tier, but cost evidence still requires
the provider to report that tier; `estimatedCostUsd` is `null` and
`costComplete=false` if a model/tier is not in that snapshot or usage was not
fully observable. `reasoningTokens` is already included in `outputTokens` and
is never charged twice. The free moderation call is reported separately from
metered Responses API calls. Requests short-circuited by a local rule or an
authoritative exact-image match report a complete zero-cost usage object.
Each `modelCalls` entry includes `resultStatus=OK|ERROR` and a governed
`failureCode`. Successful calls use `failureCode=NONE`; failed calls use a
safe non-`NONE` code without exposing model output or user content. Failed
calls with provider-reported usage remain in token and cost totals instead of
appearing as successful classifications. `CONFIGURATION_MISMATCH` is treated
as a runtime-integrity failure rather than a model-quality result.
Refresh the in-code rate card when the official
[OpenAI pricing](https://developers.openai.com/api/docs/pricing) changes.

Errors include a stable code, a short message and the request ID:
`{"error":"INVALID_INPUT","message":"contentType is required.","requestId":"..."}`

The tracked moderation policy list is `config/moderation_terms.txt`.
Optional entries use `VIOLATION|term` and match normalized whole tokens or
phrases. The list may be empty. The OpenAI moderation model and strict-schema
custom classifier apply the general content policy to
Azerbaijani, English, Russian, and Turkish by meaning rather than by a fixed
keyword list. The classifier is instructed to understand dialect, slang,
phonetic spelling, mixed scripts, suffixes, code-switching, and common
obfuscation. Image OCR is supplied as a separately bounded field for the same
semantic policy, together with its status, confidence-accepted, and truncation
flags. Those flags calibrate model confidence but never erase an exposure that
is confirmed by pixels or consistent current evidence. Eligible retrieved
candidates and proposed image-classifier
blocks are resolved by the automated adjudication model; exact-asset, hard
moderation, analyzer-error, incomplete-evidence, and candidate-only off-topic
paths short-circuit as documented. A simultaneous classifier safety or
financial-policy proposal is adjudicated before the off-topic rule. The image
adjudicator independently returns domain, safety, financial claim/risk/privacy,
impersonation, and political-context signals for the current upload.
Unavailable or inconclusive required evidence returns `UNKNOWN`. Clear vulgar
content is prohibited for posts, comments, usernames, and image text.
Policy-list changes update the audited digest. Restart the gateway after
changing the list.

Image decisions are currently written as
`image-decision-provenance-v3`/`image-decision-config-v2`. The media audit
endpoint temporarily also accepts the preceding v2/v1 pair so a rolling
deployment can drain older gateway instances and retain a bounded rollback
path. That acceptance will be removed after all v2 producers and the agreed
rollback/replay window are gone; existing v2 audit rows remain immutable,
readable history.

For the reviewed GPT-5.4-mini, GPT-5.6-luna, and GPT-5.6-terra classifier
profiles, Responses requests explicitly use `reasoning.effort=none` so the
strict moderation JSON is not starved by the bounded 320-token output budget.
The non-reasoning gpt-4o-mini request omits that field. This behavior is bound
into `OPENAI_CLASSIFICATION_PROFILE_SHA256`.

The free safety layer defaults to the dated
`omni-moderation-2024-09-26` snapshot. Updating it is a governed model/profile
change; production comparisons do not use the floating `latest` alias.

## Test

```bash
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh
```

The validation-only run checks exactly 100 unique cases, every public response
enum, governed legacy-field mappings, required banking-risk coverage, and
comment-context shape without making paid API calls. It also distinguishes a
present `safetyAction` enum from deliberate omission when safety was not
evaluated, and requires non-safety `BLOCK` and `UNKNOWN` examples whose
safety action remains `ALLOW`. A live run additionally scores every expected
signal field:

```bash
CONFIRM_LIVE_API=1 MODERATION_BASE_URL=http://localhost:8080 \
  ./tests/run-accuracy-tests.sh
```


## TODO

- analytics and distributed tracing (easy)
- run the approved local multi-model benchmark and preserve its evidence (easy)
- implement a fallback model provider (easy)
- build/train a custom classifier (hard)
- add object and face recognition (hard)
- benchmark accuracy, including false positives and false negatives (easy–medium)
- maintain a private held-out multilingual semantic evaluation set (medium)
