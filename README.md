# Minimal social-media moderation

A Java 21 / Spring Boot moderation service:

- exact SHA-256 blocking for governed image bytes
- reviewed moderation terms for submitted text
- `omni-moderation-latest` and `gpt-4o-mini` as parallel analyzers
- `gpt-5.6-terra` as the final decision model

There is no OCR, perceptual hash, database, or human review queue. Model or configuration failures return `UNKNOWN`.

## Run

```bash
cp .env.example .env
```

AI requests are disabled by default. Enable them only after configuring:

- `OPENAI_ENABLED=true`
- `OPENAI_API_KEY`

Start:

```bash
docker compose up --build -d
docker compose ps
```

Swagger UI:

<http://localhost:8080/swagger-ui.html>

## API

`POST /v1/moderate` uses `multipart/form-data`.

```bash
curl http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=Example text'
```

`contentType`:

- `POST` — text, image, or both
- `COMMENT` — text only
- `USERNAME` — text only

Supported images:

- JPEG
- PNG
- static GIF

Responses contain:

- `decision` (`ALLOW`, `BLOCK`, `UNKNOWN`)
- `category`
- `confidence`
- `language`
- `imageMatch` (image requests only)
- `imageSha256` (image requests only)
- `visibleText` (optional)
- `policyFingerprint`
- `policyVersion`

## Configuration

`config/exact_sha256_references.txt`

```text
REFERENCE_ID|64_lowercase_hex_sha256|CATEGORY|LANGUAGE
```

`config/moderation_terms.txt`

```text
CATEGORY|LANGUAGE|TERM
```
