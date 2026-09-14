# USPortz NCAA API

USPortz can use a self-hosted `henrygd/ncaa-api` instance for NCAA sports intelligence. The app still falls back to the public endpoint when no custom build property is supplied.

## Docker

```bash
docker compose -f deploy/ncaa-api/docker-compose.yml up -d
```

The API listens on port 3000.

## Render

Use `deploy/ncaa-api/render.yaml` as a Render Blueprint. It pulls the published NCAA API image and generates an `NCAA_HEADER_KEY` secret.

After deployment, configure the USPortz Android build with:

```text
-PNCAA_API_BASE_URL=https://YOUR-SERVICE.onrender.com
-PNCAA_API_KEY=YOUR_GENERATED_HEADER_KEY
```

The API key is optional if `NCAA_HEADER_KEY` is not configured, but using the key is recommended for a public service.

The app caches NCAA scoreboard responses for 45 seconds and never waits on NCAA data before Xtream catalogue startup/playback.
