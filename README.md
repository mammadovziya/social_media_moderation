
## Services

- `gateway` - public API and final decision
- `ai-service` - OpenAI checks
- `media-service` - image checks, OCR and exact PDQ matching
- `moderation-db` - PostgreSQL for PDQ hashes

## Run

```bash
cp .env.example .env
```

Set `OPENAI_API_KEY` and `POSTGRES_PASSWORD`. Create
`config/moderation_terms.txt` locally; this private file is ignored by Git. Each
line must use `VIOLATION|term` format. Then start:

```bash
docker compose up --build -d
docker compose ps
```
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

The private moderation list is `config/moderation_terms.txt`.
Restart the gateway after changing it.

## Test

```bash
# Uses live calls
MODERATION_BASE_URL=http://localhost:8080 ./tests/run-accuracy-tests.sh
```
