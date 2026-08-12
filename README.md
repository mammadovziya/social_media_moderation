# Social Media Moderation

Policy-aware moderation API for an investment community. Supports Azerbaijani,
English, Russian, and Turkish text, plus JPEG, PNG, and GIF images for posts.
The local stack is Spring Boot services, OCR and visual matching, PostgreSQL
evidence storage, and OpenAI moderation/classification.

## Start

Requires Docker with Compose v2 and an OpenAI API key.

```bash
cp .env.example .env          # set OPENAI_API_KEY and POSTGRES_PASSWORD
docker compose up --build -d
curl -fsS http://localhost:8080/readyz
```

Swagger UI: <http://localhost:8080/swagger-ui.html>

## Moderate content

`POST /v1/moderate` accepts `multipart/form-data`. `contentType` is `POST`,
`COMMENT`, or `USERNAME`.

```bash
# post, optionally with an image (JPEG, PNG, or GIF; posts only)
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=ETF market update' \
  -F 'image=@/absolute/path/image.png;type=image/png'

# comment, with conversation context
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=comment-1002' \
  -F 'contentType=COMMENT' \
  -F 'text=I disagree; its valuation is too high.' \
  -F 'parentPostText=What do you think about NVIDIA after earnings?' \
  -F 'authorUsername=investor_az' \
  -F 'quotedText=This stock cannot lose money.'

# username, with the account it belongs to
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=user-1001' \
  -F 'contentType=USERNAME' \
  -F 'text=value_investor' \
  -F 'subjectId=account-77'
```

Limits: `parentPostText` 20,000 characters, `quotedText` 10,000,
`authorUsername` and `subjectId` 128.

The public response contains only:

- `decision`: `ALLOW`, `BLOCK`, or `UNKNOWN` — **never treat `UNKNOWN` as allow**
- `violation`: the selected violation category, or `NONE`

Policy signals, image evidence, and model-usage provenance stay internal. They
are returned only to holders of `MODERATION_INTERNAL_RESPONSE_TOKEN`, which is a
high-value bearer secret because the internal shape includes content-derived
evidence.

Calling `/v1/moderate` can consume paid API quota. Health and readiness
endpoints never invoke a model.

## Usernames

A username is a machine identity and runs its own pipeline: structural contract,
then local blocklist and privacy scanner, then protected-name registry, then
collision against allocated handles, and only then the model. Deterministic
layers decide first and cost nothing.

Handles are `a-z0-9._`, 3 to 30 characters, with no leading, trailing, or
repeated separator. Anything else is a `400`, not a moderation decision.

Allocation is separate from moderation: after accepting an `ALLOW`, bind the
handle with `POST /internal/v1/handles/allocate`, which owns uniqueness and the
change-rate limit. Decisions are audited and appealable through
`/internal/v1/appeals/username`.

## Local blocklist

[`config/blocked_terms.txt`](config/blocked_terms.txt) is required but may hold
only comments. One UTF-8 term or phrase per line, matched as whole tokens and
never as substrings. Replace the file atomically; a valid version hot-reloads
into the next request with no restart. A hit returns
`{"decision":"BLOCK","violation":"OTHER"}` without calling the AI service.

## Test

```bash
mvn test                                    # requires Java 21
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh   # needs jq, makes no API calls
```

The live accuracy suite consumes paid quota and requires explicit approval:

```bash
CONFIRM_LIVE_API=1 MODERATION_BASE_URL=http://localhost:8080 \
  ./tests/run-accuracy-tests.sh
```

## Before production

- Replace the `OWN_BRAND` and `OWN_PRODUCT` placeholders in
  `services/media/src/main/resources/handle/protected_names.tsv` with the
  operating bank's own brand and products.
- Disable unauthenticated visual retrieval and set the same strong internal
  token on the media and visual-retrieval services.

See [Image moderation architecture](docs/image-moderation-architecture.md) for
policy precedence, image matching, audit evidence, and deployment details.
