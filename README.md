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

Set `OPENAI_API_KEY` and `POSTGRES_PASSWORD`.

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

Comments:
```bash
curl http://localhost:8080/v1/moderate \
  -F 'contentId=comment-1002' \
  -F 'contentType=COMMENT' \
  -F 'text=I disagree; its valuation is too high.' \
  -F 'parentPostText=What do you think about NVIDIA after earnings?' \
  -F 'authorUsername=investor_az' \
  -F 'quotedText=This stock cannot lose money.'
```

`parentPostText` (20,000 characters), `quotedText` (10,000 characters),
and `authorUsername` (128 characters)

### Moderation contract

A representative safe response is:

```json
{
  "contentId": "post-1001",
  "contentType": "POST",
  "decision": "ALLOW",
  "violation": "NONE",
  "investment": "RELATED",
  "politics": "NOT_RELATED",
  "reason": "NONE",
  "domain": "INVESTMENT_RELATED",
  "safetyAction": "ALLOW",
  "safety": "NONE",
  "financialClaim": "ANALYSIS",
  "financialRisk": "NONE",
  "financialPrivacy": "NONE",
  "impersonation": "NONE",
  "politicalContext": "NONE",
  "aiUsage": {},
  "policyVersion": "investment-community-policy-v2"
}
```

## Test

```bash
CONFIRM_LIVE_API=1 MODERATION_BASE_URL=http://localhost:8080 \
  ./tests/run-accuracy-tests.sh
```


## TODO

- analytics and distributed tracing (easy)
- run the approved local multi-model benchmark and preserve its evidence (easy)
- implement a fallback model provider (easy)
- build/train a custom classifier (hard)
- add object and face recognition (hard)
- benchmark accuracy, including false positives and false negatives (easy–medium)
- maintain a private held-out multilingual semantic evaluation set (medium)
