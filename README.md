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

Successful post/comment and image AI results are reused for 24 hours when the
exact analyzer inputs and AI configuration match. Cache keys contain only
SHA-256 digests, not raw content; tracking IDs and image filenames do not affect
reuse. Concurrent replicas share a PostgreSQL lease so the same work normally
reaches OpenAI once. A cache hit reports zero fresh AI usage, while current local
policy is reduced again on every request. Image media analysis and decision
auditing also still run on every request. Cache or coordination failures fall
back to live moderation.

The gateway treats any valid Omni Moderation category score strictly above
`MODERATION_SCORE_BLOCK_THRESHOLD` as a safety block. The default operating
point is `0.15`; a score exactly equal to `0.15` does not cross the threshold.

Local exact rules live in `config/blocked_terms.txt`. Use one UTF-8 entry per
line as `VULGAR|term` or `POLITICAL_CONTENT|term`; legacy bare terms still map
to `OTHER`. Exact political entries return `BLOCK/POLITICAL_CONTENT`; exact
vulgar entries return `BLOCK/VULGAR`. Changes hot-reload between requests.
Matching is whole-term and NFKC/case normalized, so this file is only a reviewed
high-precision supplement, not an exhaustive slang dictionary or a substitute
for semantic classification. The shipped vulgar policy has 7,689 distinct
canonical rules: 38 manually curated rules plus 7,651 deterministic forms from
five reviewed Azerbaijani phrase families. Generated coverage includes both
word orders, Azerbaijani and full-ASCII spellings, explicit noun inflections,
compact phrases, and contextual letter spacing. Ambiguous standalone homographs
remain excluded; fuzzy spellings, mixed-script forms, and unseen morphology go
through semantic classification.
Validate or regenerate the checked-in block with:

```bash
python3 config/generate_blocked_terms.py --check
python3 config/generate_blocked_terms.py --write
```

The semantic policy also blocks material references to national/state presidents,
government ministers, and Azerbaijan's YAP/New Azerbaijan Party; ambiguous
references return `UNKNOWN`. Corporate or sports-club presidents, religious
ministers, and the ordinary lowercase Turkish verb `yap` are excluded.

## Test

```bash
mvn test
VALIDATE_ONLY=1 ./tests/run-accuracy-tests.sh
```

## Before production

- Set one shared `AI_WORK_IDEMPOTENCY_INTERNAL_TOKEN` on the gateway and media
  service (generate it with `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`),
  and set `AI_WORK_IDEMPOTENCY_ALLOW_UNAUTHENTICATED=false`. The empty-token
  opt-in in `.env.example` is for local Compose only.
- Extend `OWN_PRODUCT` in
  `services/media/src/main/resources/handle/protected_names.tsv` with any
  further ABB product, campaign, or sub-brand name.
- Disable unauthenticated visual retrieval and set a shared internal token.
- Authenticate the gateway; it ships with none.
