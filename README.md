# Social Media Moderation

Java 21 / Spring Boot service for checking posts, comments and usernames.
It returns `ALLOW`, `BLOCK` or `UNKNOWN`.

## Services

- `gateway` - public API and final decision
- `ai-service` - OpenAI checks
- `media-service` - image validation, OCR, exact identity and candidate fusion
- `visual-retrieval` - candidate-only ORB/LSH retrieval with geometric verification
- `moderation-db` - PostgreSQL for reference metadata, descriptors and append-only audits

SHA-256 over the original upload bytes is the only identity signal. PDQ,
masked PDQ and local-feature matches retrieve bounded reference candidates;
they never block directly. `TEXT_DEPENDENT` and composition candidates are
re-evaluated against current OCR/pixels by `gpt-5.6-terra`. A fallible image
classifier block is also only a proposal until Terra confirms it. Missing or
inconsistent required evidence returns `UNKNOWN`; there is no human-review
queue.

References and their derived descriptors are immutable/versioned. Final image
decisions are written synchronously to an append-only metadata audit without
raw image, OCR or post text. The audit binds actual and configured models,
complete moderation/classification/adjudication request profiles, expected and
observed AI configuration, OCR/decoder runtimes, visual snapshot identity, and
the canonical decision configuration. Configuration mismatch is preserved for
diagnosis and produces `UNKNOWN` instead of trusting incompatible evidence.

Only the gateway is public. Posts accept text, an image or both. Comments and
usernames accept text only. Posts must be about investment.

## Run

```bash
cp .env.example .env
```

Set `OPENAI_API_KEY` and `POSTGRES_PASSWORD`. Review the tracked
`config/moderation_terms.txt` policy list; do not place secrets in it. Each line
must use `VIOLATION|term` format. Then start:

```bash
docker compose up --build -d
docker compose ps
```

The local Compose profile enables OCR because text-dependent image moderation
cannot be demonstrated safely without it; standalone media configuration remains
disabled unless `OCR_ENABLED=true` is set. The media image includes Tesseract and
Azerbaijani, English, Russian and Turkish language packs. Change `OCR_LANGUAGES`
if fewer languages are needed, then rebuild with `docker compose up --build -d`.

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

Errors include a stable code, a short message and the request ID:
`{"error":"INVALID_INPUT","message":"contentType is required.","requestId":"..."}`

`UNKNOWN` means the service could not make a reliable final decision, for
example because an analyzer was unavailable or the result was ambiguous. Posts
that are clearly not about investment return `BLOCK / NOT_INVESTMENT`; an
uncertain investment classification returns `UNKNOWN / NOT_INVESTMENT`.

The tracked moderation policy list is `config/moderation_terms.txt`.
Restart the gateway after changing it.

## Test

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  maven:3.9.9-eclipse-temurin-21 mvn test

VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh

# Only after explicit approval to spend model API quota:
CONFIRM_LIVE_API=1 MODERATION_BASE_URL=http://localhost:8080 \
  ./tests/run-accuracy-tests.sh
```

Live accuracy tests use OpenAI calls and need the Compose stack. They fail
closed before the first request unless `CONFIRM_LIVE_API=1` is present.

This workspace's architect-focused corpus, evaluators, and reports live under
the git-ignored `local-demo/` directory and are intentionally not distributed
through GitHub. Its live gateway evaluator refuses to send requests unless
`--confirm-live-api` is supplied after explicit API-spend approval.

## Production boundary

The local implementation is an architecture and release-gate package, not an
authorization to expose Compose publicly. Before deployment, provide tenant
identity, service-to-service authentication, rate limits, encrypted retention,
regional processing policy, observability/SLOs, immutable runtime/package
pinning, and held-out production-scale retrieval and model calibration. The
local retrieval gate passes; production release remains NO-GO. See
`docs/image-moderation-architecture.md` for the current evidence.
