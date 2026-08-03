# Social Media Moderation

Java 21 / Spring Boot service for checking posts, comments and usernames.
It returns `ALLOW`, `BLOCK` or `UNKNOWN`.

## Services

- `gateway` - public API and final decision
- `ai-service` - OpenAI checks
- `media-service` - image checks and PDQ matching
- `moderation-db` - PostgreSQL for PDQ hashes

Only the gateway is public. Posts accept text, an image or both. Comments and
usernames accept text only. Posts must be about investment.

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

OCR is disabled by default. To read text from post images, set
`OCR_ENABLED=true` in `.env` and rebuild once with `docker compose up --build -d`.
The media image already includes Tesseract and Azerbaijani, English, Russian and
Turkish language packs. Change `OCR_LANGUAGES` if fewer languages are needed.

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

`UNKNOWN` means the service could not make a reliable final decision, for
example because an analyzer was unavailable or the result was ambiguous. Posts
that are clearly not about investment return `BLOCK / NOT_INVESTMENT`; an
uncertain investment classification returns `UNKNOWN / NOT_INVESTMENT`.

The private moderation list is `config/moderation_terms.txt`.
Restart the gateway after changing it.

## Test

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  maven:3.9.9-eclipse-temurin-21 mvn test

MODERATION_BASE_URL=http://localhost:8080 ./tests/run-accuracy-tests.sh
```

Accuracy tests use live OpenAI calls and need the Compose stack.

## TODO

- finalize moderation rules and create a multilingual test set.
- better search -> [ANN](https://en.wikipedia.org/wiki/Nearest_neighbor_search), [VP-tree]()https://en.wikipedia.org/wiki/Vantage-point_tree, [BK-tree](https://en.wikipedia.org/wiki/BK-tree)
- *multiple hashing, rotations
- cache, async moderation
- add logging, tracing, metrics observiabilaty
- measure OCR accuracy with real image fixtures before adding direct OCR rules.
- add authentication, rate limits and audit logs.
- test every model or prompt update.
