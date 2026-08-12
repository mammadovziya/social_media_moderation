# Social Media Moderation

Policy-aware moderation API for an investment community inside a banking app.
Handles posts, comments, and usernames in Azerbaijani, English, Russian, and
Turkish, plus JPEG, PNG, and GIF images on posts.

Spring Boot gateway, media service (OCR, PDQ, and ORB visual retrieval),
PostgreSQL evidence storage, and an OpenAI adapter.

## Start

Requires Docker with Compose v2 and an OpenAI API key.

```bash
cp .env.example .env          # set OPENAI_API_KEY and POSTGRES_PASSWORD
docker compose up --build -d
curl -fsS http://localhost:8080/readyz
```

API reference: <http://localhost:8080/swagger-ui.html>

## Moderate

`POST /v1/moderate`, `multipart/form-data`:

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=ETF market update' \
  -F 'image=@/absolute/path/image.png;type=image/png'
```

| Field | Applies to | Limit |
|---|---|---|
| `contentId` | all | 128 |
| `contentType` | all | `POST`, `COMMENT`, `USERNAME` |
| `text` | all | 20,000 |
| `image` | `POST` | 8 MiB, JPEG/PNG/GIF |
| `parentPostText` | `COMMENT` | 20,000 |
| `quotedText` | `COMMENT` | 10,000 |
| `authorUsername` | `COMMENT` | 128 |
| `subjectId` | `USERNAME` | 128 |

The response carries `decision` (`ALLOW`, `BLOCK`, `UNKNOWN`) and `violation`.
**Never treat `UNKNOWN` as allow.** Detailed policy signals stay internal and
are returned only to holders of `MODERATION_INTERNAL_RESPONSE_TOKEN`.

Usernames run a separate pipeline: structural contract, blocklist and privacy
scanner, protected-name registry, collision check, then the model. Handles are
`a-z0-9._`, 3 to 30 characters; anything else is a `400`. Bind an accepted
handle with `POST /internal/v1/handles/allocate`, which owns uniqueness and the
change-rate limit.

Calling `/v1/moderate` consumes paid API quota. Health and readiness endpoints
never invoke a model.

## Test

```bash
mvn test                                        # Java 21
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh   # needs jq, no API calls
```

## Before production

- Replace the `OWN_BRAND` and `OWN_PRODUCT` placeholders in
  `services/media/src/main/resources/handle/protected_names.tsv`.
- Disable unauthenticated visual retrieval and set a strong shared internal
  token on the media and visual-retrieval services.
- Authenticate the gateway; it ships with no authentication.
