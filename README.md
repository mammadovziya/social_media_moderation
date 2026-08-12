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

## Moderate

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=ETF market update' \
  -F 'image=@/absolute/path/image.png;type=image/png'
```

Returns `decision` (`ALLOW`, `BLOCK`, `UNKNOWN`) and `violation`.
Never treat `UNKNOWN` as allow.

## Test

```bash
mvn test
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh
```

## Before production

- Extend `OWN_PRODUCT` in
  `services/media/src/main/resources/handle/protected_names.tsv` with any
  further ABB product, campaign, or sub-brand name.
- Disable unauthenticated visual retrieval and set a shared internal token.
- Authenticate the gateway; it ships with none.
