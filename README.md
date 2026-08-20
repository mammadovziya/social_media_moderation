# Social Media Moderation

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
