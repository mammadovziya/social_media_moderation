# Performance and fault harness

This harness exercises the public multipart moderation endpoint with unique
text, repeated text, a concurrent cold key, and repeated image workloads. The
fake provider implements the two OpenAI endpoints used by the AI service, so
load and failure tests do not spend API credits.

Start the application against the deterministic provider:

```bash
docker compose -f compose.yaml -f tests/performance/compose.yaml \
  --profile performance up --build -d
```

The override supplies configuration-bound fake-provider profile hashes and uses
an isolated Compose project and database volume. It cannot reuse or contaminate
the production-provider verdict cache.

Run the default mixed workload:

```bash
docker compose -f compose.yaml -f tests/performance/compose.yaml \
  --profile performance --profile load run --rm \
  -e WORKLOAD=mixed -e RATE=10 -e DURATION=2m k6
```

Supported `WORKLOAD` values are `mixed`, `unique-text`, `repeated-text`,
`stampede-text`, `unique-image`, `repeated-image`, and `candidate-image`.
`unique-image` adds a valid PNG metadata chunk on every request so the
raw-byte/media cache is genuinely cold while decoded pixels stay controlled.
The checked-in 800x600 fixture has multiple text lines, exercising OCR, PDQ,
and visual retrieval rather than a trivial decoder path. Adjust `RATE`,
`DURATION`, `PRE_ALLOCATED_VUS`, `MAX_VUS`, `REQUEST_TIMEOUT`, and
`P95_THRESHOLD` without editing the script. `stampede-text` rotates to a new
shared cold key every `STAMPEDE_WINDOW_MS` (default: 1000 ms), producing
repeated single-flight races rather than one warm-cache run.
The default 50 preallocated VUs absorb JVM/DB cold-start latency while retaining
the strict zero-dropped-iterations threshold; lower it only after a warm-up run.

To exercise candidate retrieval and the sequential adjudication call, seed the
fixture's observed PDQ hash into the isolated performance database, then run
the candidate workload:

```bash
./tests/performance/seed-reference.sh

docker compose -f compose.yaml -f tests/performance/compose.yaml \
  --profile performance --profile load run --rm \
  -e WORKLOAD=candidate-image -e RATE=2 -e DURATION=1m k6
```

Reset all performance state, including its isolated verdict/reference cache,
with `docker compose -f compose.yaml -f tests/performance/compose.yaml down -v`.

Fault injection is deterministic. For example, make every tenth provider call
return HTTP 429 after 250 ms:

```bash
FAKE_OPENAI_DELAY_MS=250 FAKE_OPENAI_ERROR_EVERY=10 \
FAKE_OPENAI_ERROR_STATUS=429 \
  docker compose -f compose.yaml -f tests/performance/compose.yaml \
  --profile performance up --build -d
```

Record p50/p95/p99, maximum sustainable request rate, dropped iterations,
`UNKNOWN` decisions, cache hit/owner/wait counts, provider calls per request,
DB-pool wait, OCR/PDQ/visual queue time, heap/RSS, and audit latency. Compare
decisions against the accuracy suite whenever a model, prompt, image detail, or
reasoning setting changes.
