# Social Media Moderation

Moderation API for an investment community inside a banking app. Posts,
comments, and usernames in Azerbaijani, English, Russian, and Turkish, plus
images on posts.

## Start

```bash
cp .env.example .env          # set OPENAI_API_KEY and POSTGRES_PASSWORD
docker compose up --build -d
curl -fsS http://localhost:8080/readyz
```

API reference: <http://localhost:8080/swagger-ui.html>

Backend contract and operations handoff:
[`docs/backend-handoff.md`](docs/backend-handoff.md)

## Moderate

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=ETF market update' \
  -F 'image=@/absolute/path/image.png;type=image/png'
```

Public `200` responses contain only `decision` (`ALLOW` or `BLOCK`) and
`violation`. Internal uncertainty or required-dependency failure is returned as
a safe HTTP error, never as `200 UNKNOWN` and never as an implicit allow. Every
response includes `X-Request-ID`; preserve it when retrying a logical request.

For `POST`, `COMMENT`, and `USERNAME` requests, including image posts, a valid
semantic `UNKNOWN` from the first-pass `gpt-5.4-mini` classifier is rechecked by
the configured `gpt-5.6-terra` adjudicator. A successful recheck is schema-bound
to exactly `ALLOW` or `BLOCK`; successful image adjudication cannot return an
uncertain policy axis. Existing terminal allow/block results do not spend an
adjudication call. Analyzer, timeout, invalid-contract, and audit failures are
fail-closed as safe `502`, `503`, or `504` responses; they are never converted
to allow.

A conditional long-horizon possibility such as “invest $100 each month and you
could become a millionaire in 50 years” is not a guaranteed return by itself.
Absent certainty, deception, fabricated support, or solicitation, Terra records
`financialClaim=OPINION`, `financialRisk=NONE`, and does not block on financial
risk. Wording such as “you will definitely become a millionaire; loss is
impossible” remains a governed guaranteed-return block.

Successful post/comment and image AI results are reused for 24 hours when the
exact analyzer inputs and AI configuration match. Cache keys contain only
SHA-256 digests, not raw content; tracking IDs and image filenames do not affect
reuse. Concurrent replicas share a PostgreSQL lease so the same work normally
reaches OpenAI once. A cache hit reports zero fresh AI usage, while current local
policy is reduced again on every request. Image media analysis and decision
auditing also still run on every request. Cache or coordination failures fall
back to live moderation.

## Decision audit and observability

Every valid moderation decision is persisted before it is returned. If the
required audit write is unavailable, times out, or returns an invalid
acknowledgement, the gateway returns `503`, `504`, or `502` respectively instead
of returning an unaudited decision. Requests rejected before a moderation
decision exists (for example, an invalid category, empty body, or disallowed
comment image) do not create decision events. Cache hits and caller retries do
create new events, so `request_id` is indexed for correlation but deliberately
is not unique.

| Decision surface | Append-only evidence |
| --- | --- |
| `POST` | `moderation_post_decision_audit_events` |
| `COMMENT` | `moderation_comment_decision_audit_events` |
| `USERNAME` | `moderation_username_decision_audit_events` |
| Image attached to a post | One canonical POST event plus modality provenance in `moderation_image_decision_audit_events` |

`moderation_decision_audit_summary` is the common read-only POST, COMMENT, and
USERNAME operational view. The category tables retain the final outcome,
deciding layer, all public policy axes, local-policy evidence, policy and AI
configuration provenance, live/cache/not-invoked source, bounded usage and cost
evidence, and decision latency. Database constraints validate the governed
enums and evidence relationships. Triggers reject `UPDATE`, `DELETE`, and
`TRUNCATE` with SQLSTATE `55000`; ordinary application retention must therefore
not depend on deleting individual rows.

Three production observability limitations remain. Failure-path audit rows keep
the internal `UNKNOWN` decision but do not store the terminal HTTP status/error
or `systemFailureKind`; a `502`, `503`, and `504` therefore look the same in
DataGrip and must currently be distinguished through the correlated gateway
response, logs, and metrics. For image posts, the canonical POST event and image
evidence event are separate, non-atomic writes, so an image-audit failure can
leave the POST event present even though the caller receives an error. Finally,
the propagated moderation deadline is not fully enforced inside JDBC audit and
other internal database calls; production database acquisition, transaction,
and statement timeouts must remain below the gateway finalization budget.

When Terra resolves a first-pass text uncertainty, the canonical category row
uses deciding layer `ADJUDICATOR` and retains the classification/adjudication
status, actual and configured models, prompt/profile digests, and bounded usage
for both metered calls. A cache replay retains the model evidence but reports
zero fresh usage.

POST and COMMENT audit rows never copy raw post text, comment text, quoted or
parent context, contextual usernames, model payloads, or OCR text. They keep
bounded lengths, redaction flags, and a length-framed SHA-256 input-envelope
fingerprint. These digests are still pseudonymous and linkable data, so the
deployment must set access and retention policy. In production, use separate
roles: a migration owner, a runtime role limited to the required inserts, and an
auditor role limited to reads. The single database user in local Compose is not
that production boundary.

Audit-call latency and failures use the existing low-cardinality Micrometer
timer `moderation.gateway.stage.duration` (Prometheus
`moderation_gateway_stage_duration_seconds_*`) with fixed `stage` values
`media.audit.post`, `media.audit.comment`, `media.audit.username`, and
`media.audit.image`, plus `outcome=success|error`. Request IDs, content IDs, and
user data are never metric tags. The existing observability profile already
scrapes the gateway and media service.

The gateway treats any valid Omni Moderation category score strictly above
`MODERATION_SCORE_BLOCK_THRESHOLD` as a safety block. The default operating
point is `0.15`; a score exactly equal to `0.15` does not cross the threshold.

The image-only governed attire policy blocks a materially visible depicted
person wearing a bikini or comparably revealing swimwear as `BLOCK/SEXUAL`,
including beach, pool, sports, celebrity, editorial, advertising, and
nonsexual-pose contexts. This is a product attire rule, not an assertion of
sexual intent. Text-only mentions, apparel shown without a wearer, wetsuits,
rash guards, board shorts, and ordinary non-revealing swimwear are excluded.
Tiny, occluded, or otherwise unresolved first-pass evidence is sent to Terra;
if Terra successfully cannot confirm the attire rule it neutralizes that rule
and returns `ALLOW`, while analyzer or required-evidence failure returns a safe
HTTP error rather than a public decision. The image classifier and Terra recheck
use separately versioned governed prompts, and their pinned prompt/profile
digests are part of the shared cache identity so an older cached allow cannot
cross a policy revision.

Local exact rules live in `config/blocked_terms.txt`. Use one UTF-8 entry per
line as `VULGAR|term`, `HANDLE_VULGAR|term`, `HATE|term`, or
`POLITICAL_CONTENT|term`; legacy bare
terms still map to `OTHER`. Exact political entries return
`BLOCK/POLITICAL_CONTENT`; exact vulgar entries return `BLOCK/VULGAR`; exact hate
entries return `BLOCK/HATE`. `HANDLE_VULGAR` rules apply only to usernames and
return `BLOCK/VULGAR`; they do not block the same words in posts or comments.
Where text matches more than one category the
strongest wins, ordered `HATE` > `VULGAR` > `POLITICAL_CONTENT` > `OTHER`, so a
hate-targeting slur is never audited as mere profanity. Changes hot-reload between
requests. Matching is whole-term and NFKC/case normalized, so this file is only a
reviewed high-precision supplement, not an exhaustive slang dictionary or a
substitute for semantic classification. The shipped vulgar policy has 7,553
distinct canonical rules: 85 manually curated rules plus 7,468 deterministic
forms from five reviewed Azerbaijani phrase families, alongside a small reviewed
set of `HATE` slur rules. Generated coverage includes both
word orders, Azerbaijani and full-ASCII spellings, explicit noun inflections,
compact phrases, and contextual letter spacing. The exact rules are deliberately
context-blind; bare `peysər`, `sikim`, `cındır`, `xuy`, `amk`, `amq`, and `götəş`
are strict terminal product-policy choices even in neutral or quoted usage. The
ASCII `gotes` fold of `götəş` is also deliberately governed. Other ordinary-word
homographs are excluded: `huy` (temperament) stays a semantic decision. Usernames
additionally use a bounded,
versioned vulgar-fold profile for reviewed separator, transliteration, and digit
obfuscations. Other fuzzy spellings, mixed-script forms, and unseen morphology
go through semantic classification.

Username identity uses `handle-skeleton-v2`. Its primary audit/cache key removes
separators but preserves genuine `i`, Azerbaijani `ı`, `l`, and digits, so
ordinary handles do not share model verdicts merely because their letters look
similar. Explicit digit and symbol spoofs such as `adm1n` and `p4sha` are
expanded into a bounded candidate set only while comparing against protected
names. A one-letter difference is `POSSIBLE` evidence for semantic review, not
an automatic local impersonation block.

Validate or regenerate the checked-in block with:

```bash
python3 config/generate_blocked_terms.py --check
python3 config/generate_blocked_terms.py --write
```

### PostgreSQL policy releases

Production can keep the governed list in PostgreSQL while retaining the same
request-time in-memory matcher. `file` mode preserves the existing behavior;
`shadow` downloads and compiles the active database release but keeps the file
authoritative; `database` atomically swaps to an approved database release and
never performs database or network I/O on a moderation request thread. A failed
refresh retains the last valid database snapshot and becomes unhealthy only
after `BLOCKED_TERMS_MAX_STALE_SECONDS`.

The release ledger uses separate immutable draft, maker/checker approval, and
compare-and-swap activation operations. Generate each operation as reviewed SQL
and execute it with the corresponding database identity. The checker must run
the approval command from the independently reviewed policy artifact; approval
is bound to that artifact's format, matcher profile, source digest, semantic
digest, and term count:

```bash
python3 config/generate_blocked_terms_policy_sql.py draft \
  --release-version policy-2026-08-20 \
  > /tmp/blocked-terms-draft.sql
psql -v ON_ERROR_STOP=1 -f /tmp/blocked-terms-draft.sql

python3 config/generate_blocked_terms_policy_sql.py approve \
  --release-version policy-2026-08-20 \
  --review-note 'CHG-1234 independent review complete' \
  > /tmp/blocked-terms-approve.sql
psql -v ON_ERROR_STOP=1 -f /tmp/blocked-terms-approve.sql

python3 config/generate_blocked_terms_policy_sql.py activate \
  --release-version policy-2026-08-20 --first-activation \
  --change-reference CHG-1234 \
  --reason 'initial database policy release' \
  > /tmp/blocked-terms-activate.sql
psql -v ON_ERROR_STOP=1 -f /tmp/blocked-terms-activate.sql
```

For later releases, replace `--first-activation` with
`--expected-previous-version <currently-active-version>`. Rollback uses the same
activation command targeting an older approved release; history is never
rewritten. Roll out with `BLOCKED_TERMS_SOURCE_MODE=shadow`, confirm parity, then
switch replicas to `database`. Production must also set one shared
`POLICY_DISTRIBUTION_INTERNAL_TOKEN`, disable unauthenticated distribution, and
use an HTTPS `MEDIA_SERVICE_URL`; plaintext policy transport is rejected in
`shadow` and `database` modes unless the explicit local-only
`POLICY_DISTRIBUTION_ALLOW_INSECURE_HTTP` override is enabled. Give the
media/gateway runtime identities read-only policy access; draft,
approval, activation, and Flyway ownership belong to separate privileged roles.
The database records maker, checker, and activator from `SESSION_USER`, so run
those three SQL files through distinct login identities; actor names supplied by
the SQL caller are never trusted.

A matcher-profile change requires a staged rollout because its version and hash
are part of the governed semantic digest. Move every replica to `file`, deploy
the new matcher profile, import and activate a release generated by that version,
then repeat `shadow` to `database`. Do not mix matcher profiles while replicas
are in `database` mode.

The semantic policy also blocks material references to national/state presidents,
government ministers, and Azerbaijan's YAP/New Azerbaijan Party; ambiguous
first-pass references require adjudication. If required evidence still cannot
produce a binary outcome, the public API returns a safe HTTP error. Corporate or
sports-club presidents, religious ministers, and the ordinary lowercase Turkish
verb `yap` are excluded.

## Test

```bash
mvn test
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh
```

## Measure performance

Prometheus metrics are available through the optional observability profile:

```bash
docker compose --profile observability up -d prometheus
open http://localhost:9090
```

The deterministic performance profile provides a fake OpenAI-compatible
endpoint plus k6 workloads for unique content, repeat-cache hits, cold-key
stampedes, and images. It does not spend OpenAI credits:

```bash
docker compose -f compose.yaml -f tests/performance/compose.yaml \
  --profile performance up --build -d

docker compose -f compose.yaml -f tests/performance/compose.yaml \
  --profile performance --profile load run --rm \
  -e WORKLOAD=mixed -e RATE=10 -e DURATION=2m k6
```

See [`tests/performance/README.md`](tests/performance/README.md) for workload,
capacity, and deterministic fault-injection controls. Compare model, prompt,
reasoning, or image-detail changes against the accuracy suite before changing
production defaults.

## Before production

- Set one shared `AI_WORK_IDEMPOTENCY_INTERNAL_TOKEN` on the gateway and media
  service (generate it with `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`),
  and set `AI_WORK_IDEMPOTENCY_ALLOW_UNAUTHENTICATED=false`. The empty-token
  opt-in in `.env.example` is for local Compose only.
- Extend `OWN_PRODUCT` in
  `services/media/src/main/resources/handle/protected_names.tsv` with any
  further ABB product, campaign, or sub-brand name.
- Disable unauthenticated visual retrieval and set a shared internal token.
- Authenticate the gateway; it ships with none.
