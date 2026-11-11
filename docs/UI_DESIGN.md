# USPortz — Product UI Direction

## Goal
Make USPortz feel like a premium sports-first streaming product: confident, cinematic, fast to scan, and obvious to use on both touchscreens and a 10-foot TV. The UI should lead with *what is live and what is next*, not raw provider inventory.

## Visual system
- **Canvas:** near-black navy (#070B12), elevated slate panels (#111A26), subtle borders (#263446).
- **Accent:** electric cyan (#39D7FF) for primary actions and focus; live red for live state; restrained amber for upcoming.
- **Typography:** large, short headlines; compact metadata; no all-caps paragraphs.
- **Shape:** 14–22dp corners, consistent 8dp spacing rhythm, generous card padding.
- **Artwork:** league/team marks and event artwork where available; gradient fallbacks with clear league initials. Never block rendering on artwork.
- **Motion:** short 120–180ms transitions, focus scale/outline on TV, no heavy animated backgrounds.

## Information architecture
1. **Home** — Live Now hero, Starting Soon countdown rail, Today schedule, followed teams, Continue Watching / recently played.
2. **Sports** — sport chips → league → schedule; Live / Upcoming / Results filters; team and competition follow controls.
3. **Discover** — search across events, teams, leagues, channels; suggested searches; recent searches.
4. **Channels** — indexed sports networks and provider channels, filters, compact list/grid, favorites, EPG when available.
5. **My Stuff** — followed teams, saved events, favorite channels, watch history.
6. **Sources & Settings** — connect/test source, refresh status, diagnostics, preferences.

## Home screen hierarchy
- Header: wordmark, current date/time, search, refresh/status.
- Hero: one high-confidence live event (or next marquee event), score/status, sport badge, prominent Watch button; never a blank hero when no games are live—show next event or editorial “Your sports, one place” fallback.
- Live Now: horizontal event cards with live dot, matchup/score, league badge, matched-source count and Watch.
- Starting Soon: countdown and local time.
- Browse Sports: recognizable sport tiles, not a long text-only chip strip.
- Your Teams: followed-team events.
- Sports TV: favorite networks and recently watched channels.
- Empty states: explain what is missing and provide a clear next action (Connect source / Change sport / Refresh).

## Event card contract
- League/sport label and Live/Upcoming/Final state.
- Team/competitor names and logos with accessible text fallbacks.
- Score if supplied; local start time/countdown otherwise.
- One primary Watch action and one secondary Sources action.
- Favorite toggle with persistent state.
- No fake score, availability, or certainty; show “No matched channel” honestly.

## TV / remote behavior
- Dedicated 10-foot layout: left rail, focusable large cards, visible focus ring and scale, predictable D-pad movement.
- Search, filters, favorites, and source setup reachable without touch.
- Back behavior returns to prior screen, not launcher.
- Keep focus stable when list data refreshes; never auto-steal focus.
- Use larger text and hit targets; avoid dense phone-style bottom navigation.

## Search and channel catalog
- Search is debounced and indexed; query results page from SQLite.
- Search suggestions: teams, leagues, networks, recent queries.
- Channel rows load logos asynchronously and keep placeholders stable.
- Category changes should not download or materialize the entire 50k+ catalog.
- Loading indicators are local to the affected rail/list; cached data remains visible.

## Accessibility and polish
- Contrast at least WCAG AA where practical; live status is not color-only.
- Content descriptions for team logos, actions, and live indicators.
- Respect font scaling and system reduced-motion preferences.
- Add skeleton placeholders for first load, error/retry states, offline/stale badges, and source-health detail.
- Avoid excessive borders, gradients, giant empty banners, and repeated “Sports Command Center” labels.

## Implementation order
- **P0 — UX foundation:** unify colors/typography/components; persistent favorites; loading/error/empty states; focus and back navigation; remove duplicate/obsolete entry points after parity checks.
- **P1 — Home redesign:** live hero, Live Now, Starting Soon, Today, Followed Teams; all backed by current normalized SportsEvent data.
- **P1 — Discover:** one global search surface over events and paged SQLite channel search.
- **P2 — Event details:** event artwork, status/score/time, source confidence, source list, favorite/follow actions.
- **P2 — TV parity:** remote-first navigation, large layout, stable focus, TV-specific search and settings.
- **P3 — Personalization:** team/league follow, alerts, continue watching, schedule preferences.

## Acceptance checks
- Home first paints from cached data and does not wait for a complete provider import.
- 50k+ channel catalogue never becomes a single UI list; paging remains bounded.
- Favorite state survives process restart.
- All primary actions work with touch, keyboard, and TV D-pad.
- Live/upcoming states use source data and local timezone.
- Every screen has loading, empty, stale/offline, and recoverable-error states.
- Android unit tests/build pass; manual phone + Android TV navigation and playback smoke tests are recorded.
