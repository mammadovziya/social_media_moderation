# Minimal social-media moderation

A single Java 21 / Spring Boot service that combines two deterministic controls
with three fixed OpenAI models:

- exact SHA-256 blocking for explicitly governed image bytes;
- reviewed, high-precision moderation terms for submitted text;
- `omni-moderation-latest` and `gpt-4o-mini` as parallel current-content
  analyzers;
- `gpt-5.6-terra` as the automated final adjudicator.

There is no OCR service, perceptual hash, visual-retrieval service, database, or
human-review queue. A model/configuration failure returns `UNKNOWN`; callers
must not convert `UNKNOWN` to `ALLOW`.

## Why exact SHA-256

SHA-256 answers one narrow question: “Are these the exact same upload bytes as
a governed blocked reference?” Equality can block directly and uses no model
API. A changed pixel, metadata edit, recompression, resize, or new text overlay
changes the digest, so the file does not inherit the old decision. The current
content then goes through all three AI checks.

This avoids the false-block failure where a perceptual hash treats two images
with the same background but different words as the same policy object. It also
means SHA-256 does not catch transformations; the AI path owns that judgment.

## Decision flow

1. Validate request shape and image byte, format, dimension, pixel, and static
   GIF limits.
2. Hash the untouched original upload bytes once.
3. On an exact governed digest match, return `BLOCK` with `confidence: 1.0`
   without calling a model.
4. Match reviewed moderation terms against submitted text. A match returns a
   deterministic `BLOCK` with `confidence: 1.0`, also without a model call.
5. Otherwise call `omni-moderation-latest` and `gpt-4o-mini` in parallel on the
   current request.
6. Give the current content and both validated signals to `gpt-5.6-terra` for a
   final decision. Invalid, missing, mismatched, or conflicting evidence
   returns `UNKNOWN`.

The service never adds an upload to the exact-hash catalogue. That file is an
operator-controlled policy input loaded at startup.

## Run locally

Copy the safe template:

```bash
cp .env.example .env
```

Provider access is disabled by default. Exact-hash and local-term requests
remain testable; a request that needs AI returns `UNKNOWN` unless both
`OPENAI_ENABLED=true` and `OPENAI_API_KEY` are deliberately configured.

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/healthz
```

Swagger UI is available at <http://localhost:8080/swagger-ui.html>.

Do not set a funded API key or send AI-path requests until API-spend approval
has been granted. The repository's automated tests use fakes and do not call a
provider.

## Manual curl checks

The endpoint is `POST /v1/moderate` with `multipart/form-data`.

The production term file is empty by default. To test a deterministic term,
add a reviewed temporary row such as `CATEGORY|LANGUAGE|YOUR_TEST_PHRASE` to
`config/moderation_terms.txt`, restart the gateway, and submit that exact phrase.
The request makes zero provider calls. Do not ship demo phrases as production
policy.

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=local-term-test' \
  -F 'contentType=COMMENT' \
  -F 'text=YOUR_TEST_PHRASE'
```

To test exact image identity locally, first calculate the digest:

```bash
shasum -a 256 /absolute/path/to/image.png
```

Add the printed lowercase digest to `config/exact_sha256_references.txt` using
the documented four-field format, restart the service, then submit those same
bytes:

```bash
docker compose restart gateway
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=exact-image-test' \
  -F 'contentType=POST' \
  -F 'image=@/absolute/path/to/image.png;type=image/png'
```

The response has `imageMatch: "EXACT_MATCH"` and uses zero provider calls.
Editing or re-encoding that image produces `NOT_MATCHED` and therefore needs
the AI path. After explicit API-spend approval, set `OPENAI_ENABLED=true` and
`OPENAI_API_KEY` in `.env`, recreate the service, and submit the changed image:

```bash
docker compose up -d --force-recreate gateway
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=changed-image-test' \
  -F 'contentType=POST' \
  -F 'image=@/absolute/path/to/changed-image.png;type=image/png'
```

`POST` accepts text, an image, or both. `COMMENT` and `USERNAME` require text
and reject images. Supported image formats are JPEG, PNG, and static GIF.

## Response contract

```json
{
  "contentId": "changed-image-test",
  "contentType": "POST",
  "decision": "BLOCK",
  "category": "SEXUAL",
  "confidence": 0.98,
  "language": "en",
  "imageMatch": "NOT_MATCHED",
  "visibleText": "PORN",
  "imageSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "policyFingerprint": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  "policyVersion": "minimal-sha-ai-v1"
}
```

- `decision`: `ALLOW`, `BLOCK`, or `UNKNOWN`.
- `category`: the final policy category; `ALLOW` uses `NONE`, while `UNKNOWN`
  always uses `UNDETERMINED`.
- `confidence`: bounded `0.0..1.0`. Deterministic identity/rule matches use
  `1.0`; AI confidence is evidence supplied by the governed adjudication
  contract, not a universally calibrated probability. Conclusive AI outcomes
  below the versioned `0.80` release threshold become `UNKNOWN`.
- `language`: `az`, `en`, `ru`, `tr`, `mixed`, `other`, or `und`. Deterministic
  term blocks return `und` because rule metadata is not whole-content language
  detection.
- `imageMatch` and `imageSha256`: present only for image requests.
- `visibleText`: optional text reported from the current image by the AI. It is
  not local OCR and must not be treated as a complete transcript.
- `policyFingerprint`: content-derived SHA-256 over policy catalogues, prompts,
  fixed model IDs, reducer version, and the operator policy version.

Errors use a stable code, message, and request ID.

## Governed configuration

`config/exact_sha256_references.txt`:

```text
REFERENCE_ID|64_lowercase_hex_sha256|CATEGORY|LANGUAGE
```

`config/moderation_terms.txt`:

```text
CATEGORY|LANGUAGE|TERM
```

Languages use `az`, `en`, `ru`, `tr`, `mixed`, `other`, or `und`. Categories must be a
blockable response category. Both parsers reject malformed, duplicate, unsafe,
or oversized input at startup; empty governed files are valid. Changes take
effect after restart. Keep the
catalogues reviewed and immutable through the deployment pipeline; never write
them from the request path.

The only provider models are hard-coded as `omni-moderation-latest`,
`gpt-4o-mini`, and `gpt-5.6-terra`. Provider requests use `store: false` and
strict structured outputs where supported. `OPENAI_ENABLED=false` is the
default spend kill switch, the Compose port binds only to loopback, and a
bounded concurrency gate prevents unbounded simultaneous model pipelines.

## Offline verification

```bash
mvn test
docker compose config --quiet
```

The tests mock provider HTTP and cover exact-byte identity, changed bytes,
deterministic short circuits, strict catalogue parsing, image validation,
model binding, malformed AI output, and fail-closed decisions. No OpenAI call
is made by these commands.

## Production boundary

This branch is intentionally small enough for architectural review, but a
public deployment still needs authenticated tenant identity, rate and spend
limits, idempotency/result caching, secrets management, regional/privacy rules,
audit telemetry, SLOs, and held-out multilingual calibration. See
`docs/image-moderation-architecture.md` for invariants and release gates.
