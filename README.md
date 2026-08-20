# Social Media Moderation

## Start

```bash
cp .env.example .env          # set OPENAI_API_KEY and POSTGRES_PASSWORD
docker compose up --build -d
curl -fsS http://localhost:8080/readyz
```

API reference: <http://localhost:8080/swagger-ui.html>

Backend contract and operations handoff:
[`docs/backend-handoff.md`](docs/backend-handoff.md)

## Moderate

```bash
curl -sS http://localhost:8080/v1/moderate \
  -F 'contentId=post-1001' \
  -F 'contentType=POST' \
  -F 'text=ETF market update' \
  -F 'image=@/absolute/path/image.png;type=image/png'
```
