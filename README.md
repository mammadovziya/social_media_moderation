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
`config/moderation_terms.txt` policy list; do not place secrets in it. Each line
must use `VIOLATION|term` format. Then start:

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

Errors include a stable code, a short message and the request ID:
`{"error":"INVALID_INPUT","message":"contentType is required.","requestId":"..."}`

The tracked moderation policy list is `config/moderation_terms.txt`.
Restart the gateway after changing it.

## Test

```bash
CONFIRM_LIVE_API=1 MODERATION_BASE_URL=http://localhost:8080 \
  ./tests/run-accuracy-tests.sh
```


## TODO

- analytics, token cost tracking, tracing, and logging (easy)
- benchmark multiple models (easy)
- implement a fallback model provider (easy)
- build/train a custom classifier (hard)
- add object and face recognition (hard)
- benchmark accuracy, including false positives and false negatives (easy–medium)
- curate and maintain a list of banned words (medium)
