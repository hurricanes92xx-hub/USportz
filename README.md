# USportz

Fast live-sports addon for Nuvio/Stremio using an Xtream IPTV source plus cached public sports metadata.

## Architecture

- ESPN scoreboard metadata is cached briefly so catalogs do not block on Xtream.
- Xtream live categories and streams are indexed in memory and refreshed in the background.
- Event-to-channel matching uses normalized team names, league names and quality scoring.
- Stream requests use the cached index rather than querying Xtream for every event.
- Render Blueprint and Docker deployment are included.

## Render setup

Deploy this repository as a Render Web Service using the included `render.yaml` or choose Docker as the runtime. Render web services must listen on `0.0.0.0` and the service is designed around Render's `PORT` variable.

Set these secrets in Render **Environment**:

- `XTREAM_BASE_URL` — your Xtream server URL, for example `https://provider.example.com`
- `XTREAM_USERNAME` — your Xtream username
- `XTREAM_PASSWORD` — your Xtream password

Optional tuning:

- `CACHE_TTL_SECONDS` (default `300`)
- `SCOREBOARD_TTL_SECONDS` (default `60`)
- `REQUEST_TIMEOUT_MS` (default `7000`)

Never commit Xtream credentials to GitHub or `render.yaml`.

## Endpoints

- `/manifest.json`
- `/catalog/tv/{league}.json`
- `/meta/tv/{league}:{eventId}.json`
- `/stream/tv/{league}:{eventId}.json`
- `/health`
- `/api/xtream/status`
- `/api/cache/refresh`

## Included leagues

NFL, NCAA Football, NBA, WNBA, NCAA Basketball, MLB, NHL, MLS, Premier League, UEFA Champions League, LaLiga, Serie A, Bundesliga, Ligue 1, UFC and Boxing.

## Notes

USportz is an independent addon implementation. It takes architectural inspiration from the three public projects supplied for this build, rather than copying their private credentials or deployment configuration.
