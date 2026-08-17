#!/usr/bin/env sh
set -eu

compose_files="-f compose.yaml -f tests/performance/compose.yaml"

docker compose ${compose_files} --profile performance --profile load run --rm \
  -e WORKLOAD=seed-reference k6

docker compose ${compose_files} --profile performance exec -T moderation-db \
  psql \
    -U "${POSTGRES_USER:-moderation}" \
    -d "${POSTGRES_DB:-moderation}" \
    -v ON_ERROR_STOP=1 \
  < tests/performance/seed-reference.sql
