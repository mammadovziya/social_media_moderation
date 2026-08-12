# Social Media Moderation

Policy-aware moderation API for an investment community. It supports
Azerbaijani, English, Russian, and Turkish text, plus JPEG, PNG, and GIF images
for posts. The local stack includes Spring Boot services, OCR and visual
matching, PostgreSQL evidence storage, and OpenAI moderation/classification.

## Start

Requirements: Docker with Compose v2 and an OpenAI API key.

```bash
cp .env.example .env
```

Set `OPENAI_API_KEY` and a strong `POSTGRES_PASSWORD` in `.env`. The classifier
is selected with `OPENAI_CUSTOM_MODEL`; change that value whenever you want to
switch models.

The local blocklist is [`config/blocked_terms.txt`](config/blocked_terms.txt).
The file is required, but it may contain only comments when no local terms are
active. Add one UTF-8 term or phrase per line; blank lines and lines beginning
with `#` are ignored. Entries match whole tokens or whole phrases, never
substrings. Save changes atomically (replace the complete file rather than
rewriting it in place). A valid saved version is hot-reloaded and applies to
the next request; the gateway does not need a restart.

Then run:

```bash
docker compose up --build -d
docker compose ps
curl -fsS http://localhost:8080/readyz
```

After changing only `OPENAI_CUSTOM_MODEL`, apply it with:

```bash
docker compose up -d --no-deps --force-recreate --wait ai-service gateway
```
Swagger UI: <http://localhost:8080/swagger-ui.html>

## Moderate content

`POST /v1/moderate` accepts `multipart/form-data`.

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=This post discusses a long-term ETF investment.'
```

For an image post:

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=post-1002' \
  -F 'contentType=POST' \
  -F 'text=ETF market update' \
  -F 'image=@/absolute/path/image.png;type=image/png'
```

`contentType` is `POST`, `COMMENT`, or `USERNAME`. Comments may also
include `parentPostText`, `authorUsername`, and `quotedText`.

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=comment-1002' \
  -F 'contentType=COMMENT' \
  -F 'text=I disagree; its valuation is too high.' \
  -F 'parentPostText=What do you think about NVIDIA after earnings?' \
  -F 'authorUsername=investor_az' \
  -F 'quotedText=This stock cannot lose money.'
```

`parentPostText` accepts up to 20,000 characters, `quotedText` up to 10,000,
and `authorUsername` up to 128. Images are JPEG, PNG, or GIF and are accepted
only for posts.

## Moderate a username

A username is a machine identity, so it runs a different pipeline from authored
text. Deterministic layers decide first and cost nothing; the model only sees
handles that survive them.

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=user-1001' \
  -F 'contentType=USERNAME' \
  -F 'text=value_investor' \
  -F 'subjectId=account-77'
```

The handle contract is `a-z0-9._`, 3 to 30 characters, no leading, trailing, or
repeated separator. Anything else is a `400`, not a moderation decision: a
confusable, bidirectional, or zero-width character is refused at the boundary
rather than reasoned about later.

Order of evaluation:

1. structural contract
2. local blocklist and the deterministic financial-privacy scanner
3. protected-name registry, matched on a folded skeleton so `adm1n`,
   `a.d.m.i.n`, and `admin` compare equal
4. skeleton collision against handles already allocated to other members
5. model classification, cached by skeleton so a retry is free and identical

`subjectId` is optional. Supplying it lets the service exclude the account's own
current handle from the collision check and report its change-rate state.

A registry or collision hit blocks as `IMPERSONATION`. An unresolved registry
similarity returns `UNKNOWN` only when nothing else decides; it never allows and
never overrides a stronger current-content conclusion.

Every handle decision is written to an append-only audit table before the
response is returned, and can be appealed:

```bash
# open an appeal against an audited decision
curl -sS http://media-service:8000/internal/v1/appeals/username \
  -H 'Content-Type: application/json' \
  -d '{"auditEventId": 42, "appellantStatement": "This is my own name."}'

# review queue
curl -sS 'http://media-service:8000/internal/v1/appeals/username?status=OPEN'
```

Allocation is separate from moderation. After the caller accepts an `ALLOW`, it
binds the handle with `POST /internal/v1/handles/allocate`, which enforces the
change-rate limit (`HANDLE_CHANGE_LIMIT`, `HANDLE_CHANGE_WINDOW_DAYS`) and owns
the uniqueness guarantee.

The protected-name registry seeds from
`services/media/src/main/resources/handle/protected_names.tsv`. **Replace the
`OWN_BRAND` and `OWN_PRODUCT` placeholders with the operating bank's own brand
and products before production**; a registry without them protects every
competitor and leaves the operator's own identity open.

The public response contains only:

- `decision`: `ALLOW`, `BLOCK`, or `UNKNOWN`; never treat `UNKNOWN` as allow
- `violation`: the selected violation category, or `NONE`

A local blocklist hit returns `{"decision":"BLOCK","violation":"OTHER"}` and
does not call the AI service. Text and quoted text are checked immediately. An
image post still completes image validation and audit; accepted, non-truncated
OCR is checked too.

Detailed policy signals, image evidence, and model-usage provenance remain
internal processing, telemetry, and image-audit data and are not returned
publicly.
Governed local evaluation tools can request the legacy evidence shape only with
the private `MODERATION_INTERNAL_RESPONSE_TOKEN`. Treat it as a high-value
bearer secret: the internal shape includes OCR- and content-derived evidence.
When enabled, the token must contain 43 to 256 base64url characters.

Calling the moderation endpoint can consume paid API quota. Health and
readiness endpoints do not invoke a model.

## Test

```bash
mvn test
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh
```

After API-spend approval, run the live accuracy suite with:

```bash
CONFIRM_LIVE_API=1 MODERATION_BASE_URL=http://localhost:8080 \
  ./tests/run-accuracy-tests.sh
```

Java tests require Java 21 and Maven. The validation-only accuracy command
requires `jq` and makes no API calls.

## Documentation

See [Image moderation architecture](docs/image-moderation-architecture.md) for
policy precedence, image matching, audit evidence, security, and deployment
details.

For production, disable unauthenticated visual retrieval and configure the same
strong internal token for the media and visual-retrieval services.
