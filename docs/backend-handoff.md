# Backend handoff: strict moderation API

This document is the implementation and operations handoff for the synchronous
moderation backend. It describes the contract that backend callers must build
against and the invariants maintainers must preserve. Commands below are
runbook examples; this document does not assert that a particular environment
has been tested or deployed.

## Contract at a glance

- The only public moderation route is `POST /v1/moderate` with
  `multipart/form-data`.
- A public `200 OK` body contains exactly `decision` and `violation`.
- A public success decision is always `ALLOW` or `BLOCK`. `UNKNOWN` is an
  internal reduction state and must never be serialized as a public `200`.
- If required evidence cannot produce a binary decision, the gateway returns a
  safe HTTP error. It never converts an analyzer, timeout, configuration, media,
  or audit failure into `ALLOW`.
- Every response carries `X-Request-ID`. Error bodies repeat the same value as
  `requestId`.
- Successful public responses are `Cache-Control: no-store, private` and vary
  on `X-Moderation-Internal-Token`.
- A required decision audit must be persisted before success is returned.

The OpenAPI document at `/v3/api-docs` and Swagger UI at
`/swagger-ui.html` are the generated wire-contract references. Keep both the
annotations and their contract tests aligned with this handoff.

## Architecture

| Component | Responsibility |
| --- | --- |
| Gateway | Public validation, request deadline, local policy, orchestration, configuration validation, final policy reduction, audit-before-success, and safe HTTP errors. |
| AI service | OpenAI moderation, first-pass structured classification, and stronger adjudication when policy requires it. Internal provider failures remain typed failures. |
| Media service | Image decoding, exact/PDQ evidence, OCR, protected-name evaluation, durable AI-work coordination, Flyway migrations, and decision-audit persistence. |
| Visual retrieval | Internal image descriptor and geometric similarity analysis used by the media service. |
| PostgreSQL | Governed reference data, durable configuration-bound AI-work state, and append-only decision evidence. |

### Gateway code ownership

The gateway is deliberately split by responsibility. New behavior should be
added to the owning component instead of growing the HTTP controller again.

| Component | Code responsibility |
| --- | --- |
| `ModerationApi` | Multipart/OpenAPI/Bean Validation wire contract. |
| `ModerationController` | Thin HTTP adapter: request ID, validated routing, and legacy direct-call compatibility only. |
| `GatewayHealthController` | Liveness and dependency-aware readiness endpoints. |
| `ModerationRequestValidator` | Content-type, cross-field, image MIME/size, and upload-to-application input validation. |
| `ContentModerationService` | POST/COMMENT orchestration and final response/audit ordering. |
| `UsernameModerationService` | USERNAME structure, protected-name, local-policy, cache, model, and audit orchestration. |
| `ModerationAnalysisService` | AI/media calls, configuration-bound work identity, cache replay validation, and analyzer fallbacks. |
| `MediaEvidenceValidator` | OCR, image metadata, candidate, exact-match, and media-provenance contract validation. |
| `ModerationDecisionSupport` | Pure signal merging and final response projections. |
| `ModerationDependencyValidator` | Required AI/OCR contract validation, usage evidence, and typed dependency-failure translation. |
| `DecisionAuditService` and audit factories | Category-specific immutable audit payload construction and fail-closed publishing. |
| `AiConfigurationProvenance` | Expected/observed model, prompt, profile, configuration digest, and snapshot evidence. |

The HTTP adapter validates request shape, image size, and media type before
entering an application service. Therefore an invalid upload remains a caller
`4xx` even if a moderation dependency is simultaneously unhealthy. Application
services still verify policy health before producing or auditing a decision.

The high-level request path is:

1. The gateway validates the multipart shape and establishes a bounded request
   deadline and request ID.
2. Current local term, financial-privacy, handle, and political-registry policy
   is evaluated. A terminal deterministic block may avoid model calls.
3. A post image is decoded and analyzed by the media service. Non-image text
   goes directly to the AI path when local policy has not already decided it.
4. The AI service produces governed moderation and classification signals. A
   configured stronger adjudicator resolves the semantic or image cases that
   require a second pass.
5. The gateway validates the observed model, prompt, profile, timeout, and
   media provenance against its configured expectations, then applies the
   current decision policy.
6. The gateway asks the media service to append the canonical decision audit;
   image posts also append modality-specific image evidence.
7. Only after the required audit succeeds does the gateway return a binary
   public success response.

Internal service routes are not public APIs. Keep the AI, media, visual
retrieval, idempotency, and audit endpoints on a private network and enforce the
available service-to-service tokens.

## Public endpoint

### Request headers

| Header | Contract |
| --- | --- |
| `Content-Type` | Required: `multipart/form-data` with a generated boundary. Do not send a JSON body. |
| `Accept` | Use `application/json`. An unsupported response type can return `406`. |
| `X-Request-ID` | Optional. Must match `[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}`. The server generates a UUID when absent and returns the resolved value on every response. |
| `X-Moderation-Internal-Token` | Not part of the public client contract. A correctly configured token exposes additional internal evaluation fields and must be protected as a credential. An absent or incorrect token receives the two-field public representation. |

### Multipart fields

| Field | Required | Constraints and scope |
| --- | --- | --- |
| `contentId` | Always | 1–128 characters; same safe identifier pattern as `X-Request-ID`. This identifies caller content but is not an HTTP idempotency key. |
| `contentType` | Always | `POST`, `COMMENT`, or `USERNAME`; parsing is case-insensitive, but emit the uppercase canonical values. |
| `text` | Conditional | Maximum 20,000 characters. Required and nonblank for `COMMENT` and `USERNAME`. A `POST` must contain nonblank text, an image, or both. |
| `parentPostText` | No | `COMMENT` only; maximum 20,000 characters. Used as context, never attributed to the comment author. |
| `authorUsername` | No | `COMMENT` only; maximum 128 characters. |
| `quotedText` | No | `COMMENT` only; maximum 10,000 characters. Represents text visibly quoted by the comment. |
| `image` | No | `POST` only; JPEG, PNG, or GIF, nonempty, and within the configured limit. The deployment may lower the limit but cannot configure more than 8 MiB for the image or 9 MiB for the multipart request. |

Machine usernames are 3–30 ASCII letters, digits, dots, or underscores. They
must start and end with a letter or digit and cannot contain adjacent
separators. Uppercase is normalized to lowercase. This is a handle contract,
not a display-name contract.

Example:

```bash
curl --fail-with-body --silent --show-error \
  --dump-header - \
  -H 'Accept: application/json' \
  -H 'X-Request-ID: create-post-1001' \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=ETF market update' \
  -F 'image=@/absolute/path/image.png;type=image/png' \
  http://localhost:8080/v1/moderate
```

### Public success

An allow response is:

```http
HTTP/1.1 200 OK
X-Request-ID: create-post-1001
Cache-Control: no-store, private
Vary: X-Moderation-Internal-Token
Content-Type: application/json

{"decision":"ALLOW","violation":"NONE"}
```

A block response is the same shape with `decision=BLOCK` and the winning
violation, for example:

```json
{"decision":"BLOCK","violation":"SPAM_SCAM"}
```

Clients must treat the pair as an enum contract rather than infer behavior from
free text. Consume the generated OpenAPI violation enum. Do not depend on any
internal evidence fields, and do not accept `UNKNOWN` as a successful decision.

## Error contract and retries

Every error is safe JSON with no upstream response body, model payload, raw
content, stack trace, hostname, or secret:

```json
{
  "error": "SERVICE_UNAVAILABLE",
  "message": "A required moderation service is not available.",
  "requestId": "create-post-1001"
}
```

`error` is the stable machine-readable field. `message` is a bounded human
description and must not be parsed. `requestId` must equal the `X-Request-ID`
response header.

| Status | Error code | Meaning | Client action |
| --- | --- | --- | --- |
| `400` | `INVALID_INPUT` | Missing, malformed, or cross-type request fields. | Fix the request; do not retry unchanged. |
| `406` | `NOT_ACCEPTABLE` | Unsupported response media type. | Request JSON; do not retry unchanged. |
| `413` | `PAYLOAD_TOO_LARGE` | Configured image or multipart limit exceeded. | Resize or reject; do not retry unchanged. |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Request is not multipart or image MIME type is unsupported. | Fix the request; do not retry unchanged. |
| `422` | `UNPROCESSABLE_IMAGE` | The image cannot be decoded or cannot provide the required image evidence. | Replace/re-encode the image or route to review; do not blind-retry identical bytes. |
| `502` | `UPSTREAM_FAILURE` | A required service returned invalid, contradictory, or configuration-mismatched evidence. | Retry only within the bounded policy below; alert on repetition because configuration drift will not heal through retries. |
| `503` | `SERVICE_UNAVAILABLE` | A required analyzer, policy source, media capacity, database, or decision-audit write is unavailable. | Retry with jitter while the caller deadline permits. Never publish the content on error. |
| `504` | `UPSTREAM_TIMEOUT` | Required moderation work exceeded the propagated deadline. | Retry with jitter only if the end-to-end operation still has time; otherwise keep the content pending/rejected. |
| `500` | `INTERNAL_ERROR` | Unclassified gateway failure. | Treat as no moderation decision, alert, and use the same bounded retry policy if appropriate. |

Recommended retry policy for `502`, `503`, and `504`:

1. Preserve the exact request body and `contentId`.
2. Reuse the same `X-Request-ID` for attempts in one logical operation so logs
   and audit evidence can be correlated. Capture the generated ID from the
   first response if the caller did not supply one.
3. Use capped exponential backoff with full jitter, for example a base of
   250 ms and at most two or three retries inside the caller's total deadline.
4. Stop immediately on a `4xx` request error. A `422` requires changed image
   evidence or human review, not an identical retry loop.
5. After exhaustion, leave the business operation pending or rejected. Never
   interpret an HTTP error, missing response, or client timeout as `ALLOW`.

No `Retry-After` header is currently guaranteed. `X-Request-ID` is correlation,
not deduplication: each caller retry can append another decision event. If the
moderation call is part of content creation, the surrounding application must
provide its own idempotency for the content mutation.

## Optional AI-work cache and fallback

The AI-work coordinator is an optimization, not an authority for the final
decision:

- Complete, successful AI envelopes are keyed by digests of the effective
  analyzer inputs and the complete AI configuration. Raw text, images, OCR,
  filenames, tracking IDs, and content IDs are not stored in the cache key or
  result.
- The completed entry lifetime is 24 hours. Process-local single-flight and a
  PostgreSQL lease normally prevent duplicate paid work across replicas.
- Cached envelopes omit original usage. A cache replay reports zero fresh model
  usage while preserving the governed model and prompt provenance.
- Current local policy is evaluated again and the canonical audit is appended
  for every request, including cache hits and caller retries.
- A stale, malformed, or configuration-mismatched cached envelope is ignored in
  favor of live analysis. Model, prompt, profile, reasoning, timeout, detail, or
  relevant policy changes rotate the identity rather than crossing revisions.
- Cache claim, wait, completion, or storage failures fall back to live analysis
  when deadline remains. This is a cache-layer fail-open only; if live required
  moderation then fails, the public API still returns `502`, `503`, or `504`.

Do not add a fallback that serves an expired result, reuses a result across
configuration hashes, restores stripped usage as if it were fresh, or converts
a cached/provider failure into a binary allow.

## Audits, privacy, and database ownership

The canonical append-only tables are:

| Surface | Table |
| --- | --- |
| Post | `moderation_post_decision_audit_events` |
| Comment | `moderation_comment_decision_audit_events` |
| Username | `moderation_username_decision_audit_events` |
| Post image modality | `moderation_image_decision_audit_events` in addition to the canonical post event |

Use `moderation_decision_audit_summary` for the common operational projection.
`request_id` is indexed for correlation but deliberately is not unique.

```sql
SELECT content_type, final_decision, violation, deciding_layer, created_at
FROM moderation_decision_audit_summary
WHERE request_id = :request_id
ORDER BY created_at DESC;
```

The tables retain the final decision, deciding layer, public policy axes, local
policy evidence, live/cache/not-invoked source, bounded model usage/cost, and
versioned configuration provenance. Database constraints reject incoherent
evidence. `UPDATE`, `DELETE`, and `TRUNCATE` are rejected for decision-event
tables; do not build application retention around mutating individual events.

Failure-path rows currently describe the internal reducer outcome as
`final_decision=UNKNOWN`, but the schema does not persist the terminal public
HTTP outcome or `systemFailureKind`. Consequently a `502`, `503`, and `504`
attempt are indistinguishable when inspecting the audit tables or summary in
DataGrip. Correlate `request_id` with the gateway response, logs, and HTTP
metrics until a forward migration adds bounded terminal-failure provenance. Do
not infer the public status from `UNKNOWN` alone.

Image-post persistence is also not one transaction: the canonical POST event
and the image-evidence event are separate service calls and database writes. If
the second write fails, the gateway returns an error but the first row can
remain. Treat this as partial audit evidence, not a successful public decision;
the intended remediation is one transactional, idempotent decision-bundle
append rather than compensating deletion from append-only tables.

The gateway reserves time for finalization, but the propagated request deadline
is not yet enforced throughout JDBC connection acquisition, transactions, and
statements used by audit, idempotency, and handle database paths. A database
operation can therefore finish or commit after the caller deadline. Bound the
datasource and PostgreSQL timeouts below the finalization reserve and use the
request ID to reconcile late work.

Privacy boundaries:

- Post and comment audit rows do not store raw post text, comment text, parent
  context, contextual usernames, quoted text, model payloads, or OCR text. They
  store bounded lengths, redaction flags, and SHA-256 fingerprints.
- The AI-work table stores only digest-bound, bounded, usage-stripped structured
  results and explicitly rejects raw request content.
- Username audits intentionally store the normalized handle and skeleton so an
  identity decision can be appealed. Treat them as personal data.
- SHA-256 input and image digests are pseudonymous and linkable, not anonymous.
  Apply access control, retention governance, and incident handling accordingly.
- Never put request IDs, content IDs, handles, text, or image digests in metric
  tags. Keep sensitive evidence out of ordinary logs.

Production should separate a migration-owner role, an insert-limited runtime
role, and a read-limited auditor role. The single PostgreSQL user in local
Compose is a development convenience, not a production security boundary.

## Flyway migration immutability

An applied numbered migration is immutable. Once any environment may have
applied `Vn__description.sql`, never edit, reformat, rename, or regenerate its
bytes.

For every schema change:

1. Inspect the checked-in migration list and choose the next unused version.
2. Put all new DDL, constraints, backfills, view replacements, or trigger
   changes in that forward migration.
3. Keep changes compatible with the old and new application versions during a
   rolling deployment. Prefer expand/backfill/switch/contract across separate
   releases when removal is necessary.
4. Run the media migration tests, including the applied-checksum compatibility
   test, against both a clean database and a database upgraded from retained
   production migration bytes.
5. If Flyway reports a checksum mismatch, stop the rollout. Compare the
   artifact and `flyway_schema_history`, restore the original migration bytes,
   and move the intended change forward. Do not use `flyway repair` to hide
   source drift.

Changing an audit constraint or append-only trigger does not authorize rewriting
old audit rows. Express compatibility explicitly in the forward migration.

## Configuration and secrets

The source-of-truth defaults are `compose.yaml` and each service's
`application.yml`; `.env.example` is a local template. Review these groups as an
atomic release:

- Provider: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, model names, service tier,
  reasoning effort, image detail, connection/admission limits, and
  `OPENAI_TIMEOUT_SECONDS`.
- Provenance: moderation, classification, aggregate adjudication, and image
  adjudication prompt/profile SHA-256 pins. Gateway expectations must exactly
  match the AI service `/readyz` details. Update AI prompt bytes, version labels,
  hashes, gateway pins, cache identities, audit expectations, and tests together.
- Deadlines: `UPSTREAM_TIMEOUT_SECONDS` and
  `MODERATION_FINALIZATION_RESERVE_MS`. Preserve time for audit finalization;
  do not independently raise nested timeouts beyond the end-to-end budget.
- Media: image byte/request/pixel limits, OCR settings, PDQ thresholds, media
  concurrency, and visual-retrieval limits.
- Policy: `BLOCKED_TERMS_FILE` remains the bootstrap/fallback artifact. Governed
  deployments promote `BLOCKED_TERMS_SOURCE_MODE` from `file` to `shadow` and
  then `database`; keep refresh/fetch/staleness bounds healthy and set the same
  `POLICY_DISTRIBUTION_INTERNAL_TOKEN` on gateway and media. Remote policy modes
  require an HTTPS `MEDIA_SERVICE_URL`; keep unauthenticated and insecure-HTTP
  overrides disabled outside local development. Keep
  `RESTRICTED_POLITICAL_ENTITIES_FILE` mounted read-only.
- Database: `POSTGRES_DB`, `POSTGRES_USER`, and a non-default
  `POSTGRES_PASSWORD`, preferably supplied by the deployment secret store.
- Internal auth: set one 43+ character base64url
  `AI_WORK_IDEMPOTENCY_INTERNAL_TOKEN` on gateway and media and keep
  `AI_WORK_IDEMPOTENCY_ALLOW_UNAUTHENTICATED=false` in production. Set a shared
  `VISUAL_RETRIEVAL_INTERNAL_TOKEN` and disable unauthenticated visual retrieval.
- Internal response: leave `MODERATION_INTERNAL_RESPONSE_TOKEN` unset unless a
  governed internal client needs the expanded response. Never expose it to a
  browser or untrusted caller.

Do not deploy with the local default database password, local unauthenticated
flags, or an empty provider key. Place public authentication and authorization
in front of the gateway; the repository does not currently supply it.

## Build, test, and deployment checklist

Local Compose setup is intentionally development-only:

```bash
cp .env.example .env
docker compose up --build -d
curl --fail-with-body http://localhost:8080/readyz
```

Suggested verification commands:

```bash
mvn -pl services/gateway test
mvn -pl services/ai test
mvn -pl services/media test
mvn test

python3 config/generate_blocked_terms.py --check
python3 -m pytest services/visual-retrieval/tests
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh
```

Do not release while the checked-in blocked-term corpus differs from the
governed generator or its corpus tests. A manual term addition is a product
policy change: review its false-positive surface, encode it in the generator,
update the documented counts, and regenerate the file instead of weakening the
drift guard.

Run live accuracy evaluation only in an approved environment because it can
call paid providers. The deterministic performance profile uses the fake
OpenAI-compatible service; see `tests/performance/README.md`.

Suggested rollout sequence:

1. Review the diff, generated OpenAPI contract, prompt/profile pins, migration
   history, and rollback/forward-fix plan.
2. Back up the database and verify the existing Flyway history before starting
   any new service version.
3. Apply an expand-compatible forward migration through the media service or a
   dedicated migration job before deploying code that requires it.
4. Deploy private dependencies and verify their `/healthz` and `/readyz`
   endpoints. Then deploy the gateway.
5. Verify gateway `/healthz`, then `/readyz`. Gateway readiness requires healthy
   local policy plus ready media and AI services.
6. Send ALLOW and BLOCK smoke requests and verify status, two-field public body,
   `X-Request-ID`, `Cache-Control`, and the corresponding audit summary rows.
7. Observe errors, latency, capacity, cache coordination, audit writes, and
   database health through at least the intended rollback window.

Do not use `200` health alone as rollout acceptance; readiness and the
audit-before-success path are the meaningful gates.

## Observability

- `/healthz` is liveness. Gateway `/readyz` also probes current local policy,
  media, and AI readiness; media readiness covers OCR, visual retrieval, and
  PostgreSQL.
- Prometheus-format metrics are exposed through `/actuator/prometheus` on the
  Java services. Keep actuator endpoints private except for the approved
  scraper.
- Gateway latency uses `moderation.gateway.request.duration` and
  `moderation.gateway.stage.duration`. AI work exposes the
  `moderation.gateway.ai_work.*` cache, single-flight, durable-claim, fail-open,
  and completion metrics.
- AI latency uses `moderation.ai.stage.duration`,
  `moderation.ai.provider.request.duration`, and
  `moderation.ai.admission.duration`. Media work uses
  `moderation.media.stage` plus bounded counters and summaries.
- Audit operations use fixed gateway stage values `media.audit.post`,
  `media.audit.comment`, `media.audit.username`, and `media.audit.image` with a
  low-cardinality success/error outcome.
- Alert on readiness loss, public `502`/`503`/`504` rate, audit-stage errors,
  provider/admission saturation, database pool pressure, AI-work fail-open and
  completion failures, and sustained latency near the request deadline.
- Correlate detailed logs and audit rows by `X-Request-ID`; do not add request
  IDs, content IDs, usernames, text, or digests as metric labels.
- An audit row with `final_decision=UNKNOWN` does not identify whether the
  caller received `502`, `503`, or `504`. Use the correlated gateway response,
  logs, and HTTP metrics; DataGrip alone cannot recover that distinction from
  the current schema.

## Operational runbook

Start with the response `X-Request-ID` and correlate it across gateway, AI,
media, and audit logs. Do not ask operators to paste raw content into tickets.

### Repeated `502 UPSTREAM_FAILURE`

- Compare gateway expected model/prompt/profile/timeout pins with the private AI
  `/readyz` details from the same running revision.
- Check for mixed application revisions, stale containers, malformed provider
  envelopes, or an incompatible media response.
- If retries consistently fail, stop retrying and correct the configuration or
  contract. Never relax validation to accept unknown fields or uncertain axes.

### `503 SERVICE_UNAVAILABLE`

- Check gateway `/readyz`, AI `/readyz`, media `/readyz`, PostgreSQL health,
  local policy reload status, provider admission/capacity, and visual retrieval.
- Inspect `moderation.gateway.stage.duration` for error outcomes, especially
  `media.audit.post`, `media.audit.comment`, `media.audit.username`, and
  `media.audit.image`.
- An audit failure means no public success should have been emitted. Restore the
  insert path or database capacity rather than bypass the audit requirement.

### `504 UPSTREAM_TIMEOUT`

- Compare end-to-end latency with the OpenAI, media, OCR, and visual-retrieval
  budgets and the gateway finalization reserve.
- Inspect admission queues, connection pools, media concurrency, database pool
  waits, and provider latency before raising a timeout.
- Preserve the finalization reserve and caller deadline propagation.

### Cache or single-flight degradation

- Inspect `moderation.gateway.ai_work.cache.lookups`, durable claim status,
  `moderation.gateway.ai_work.fail_open`, completion failures, active local
  flights, and cache-entry gauges.
- Confirm the gateway is falling back to live analysis within its deadline.
- Do not clear or rewrite append-only audit events while repairing idempotency
  state. Cache state is not the decision audit.

### Flyway checksum failure

- Stop the media rollout before allowing automatic repair.
- Record the migration version and database checksum, compare with the exact
  migration bytes from the previously deployed artifact, and restore those
  historical bytes in source.
- Put the desired DDL into the next forward migration, then exercise both clean
  install and upgrade-path tests.

## Known limitations

- Failure audit rows do not persist terminal HTTP outcome or
  `systemFailureKind`; their internal `UNKNOWN` value cannot distinguish
  `502`, `503`, and `504` in database inspection.
- Image-post auditing currently uses separate canonical-post and image-evidence
  calls. If the second append fails, the caller receives an error but the first
  row can remain. Replace this with one transactional, idempotent decision-bundle
  endpoint before claiming atomic cross-table audit persistence.
- Internal audit/idempotency/handle database routes do not yet enforce the
  propagated request deadline inside JDBC. Production datasource, transaction,
  connection-acquisition, and statement timeouts must be bounded below the
  gateway finalization reserve so work cannot commit after the caller deadline.
- The gateway is synchronous and depends on external model latency. Callers need
  explicit deadlines, bounded retry behavior, and a pending/review state.
- Provider behavior can vary even with strict structured output. Prompt/profile
  provenance and accuracy evaluation reduce risk but do not make the model
  deterministic.
- The product targets Azerbaijani, English, Russian, and Turkish posts,
  comments, and machine usernames. Coverage outside that scope is not promised.
- Images are accepted only for posts and only as JPEG, PNG, or GIF within the
  configured byte, request, and decoded-pixel bounds.
- The 24-hour cache reduces duplicate paid work; it is not caller-visible
  idempotency and does not suppress per-request audit events.
- Username audit evidence contains the handle for appeals. Content and image
  digests remain linkable pseudonymous data even where raw text is excluded.
- Append-only audit enforcement requires a separately governed archival and
  retention design; ordinary runtime deletion is intentionally unavailable.
- Local Compose uses relaxed credentials and network assumptions. It is not a
  production topology.
- Public ingress authentication, authorization, abuse throttling, and product
  content-creation idempotency must be supplied by the surrounding platform.
