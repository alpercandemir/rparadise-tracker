# Radio Paradise Tracker — Frontend

Vue 3 + Vite dashboard for the Main, Mellow, and Rock mixes. See the
[project README](../README.md) for the full Docker stack, screenshots, backend
setup, and deployment instructions.

## Views

- **Dashboard** (`/#/`): live channel cards, statistics, channel distribution,
  a selectable most-played ranking, songs by decade, and links to Songs and Records.
- **Songs** (`/#/songs`): all songs combined across channels by exact artist/title/album,
  with summed plays and earliest/latest observations. Includes search, sorting, and
  pagination, without channel filters. Different albums remain separate.
- **Records — All Tracks** (`/#/records`): searchable, sortable track records
  with artwork, metadata, first/last observed times, and play counts.
- **Records — Playback History** (`/#/records?tab=plays`): individual observed
  plays with channel and date filters. History starts with collection; earlier
  broadcasts are not reconstructed.
- **Song details**: select a song from a card, list, chart track, or Records
  title/artwork to open large artwork, listening statistics (combined in Songs,
  channel-specific elsewhere),
  duration, and lyrics from Radio Paradise with LRCLIB as a fallback and source attribution.

Records supports search by track, artist, or album, channel filtering, column
sorting, and 25/50/100 rows per page. Filters, sorting, and pagination are stored
in the URL so views can be bookmarked and browser Back/Forward works.
Timestamps include seconds and use the browser's timezone; playback date
filters use the same timezone. The API returns UTC instants.

## Opening music apps

The Spotify and YouTube Music icons prefer the installed apps where the browser
supports app links. Android Chrome uses a user-initiated
[intent link with an HTTPS fallback](https://developer.chrome.com/docs/android/intents)
for both matched tracks and artist/title searches. If the app cannot be opened,
Chrome follows the original service URL. Android WebViews retain HTTPS links.

iPhone/iPad links use same-tab HTTPS navigation for the providers' universal-link
handling. Other Android browsers also retain same-tab HTTPS links. Desktop and
unknown browsers continue opening HTTPS links in a new tab. No installation
probing, automatic launch timers, or additional app permissions are used.

The OS, browser, and user link preferences ultimately control app handoff. Check
real iOS and Android devices with and without the apps installed when validating
native behavior; a desktop browser or unit test cannot verify an installed mobile app.

## Local development

Use Node.js 24 to match CI and Docker, and start the backend on port 8081.
From the repository root:

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

Open [http://localhost:3000](http://localhost:3000). Vite proxies `/api` to
`http://localhost:8081` via `vite.config.js`.

Run the frontend checks and production build:

```bash
npm --prefix frontend test
npm --prefix frontend run build
```

The build writes `frontend/dist`. The Docker build packages these assets into
Spring Boot so the dashboard and API share one origin.

## Refresh behavior

The dashboard loads lists and charts on entry. Only the three live channel
cards refresh every 30 seconds; polling pauses while the browser tab is hidden
or Songs/Records is open. Browsing lists, charts, scroll position, focus, and the
selected modal stay stable. Returning from Songs or Records reloads dashboard data.

Songs and Records reload when filters, sorting, or pagination change and have an explicit
**Refresh** button. Backend caches can retain responses for 30 seconds, so
refreshing does not guarantee new data immediately. See the
[caching notes](../README.md#song-details-and-caching).

## Components and services

```text
frontend/src/
├── components/
│   ├── AlbumArtwork.vue       # Shared cover image and failure fallback
│   ├── CurrentSong.vue        # Live channel card
│   ├── StatsCard.vue          # Dashboard statistic
│   ├── ChannelChart.vue       # Channel distribution
│   ├── MostPlayedChart.vue    # Top tracks by play count
│   ├── TimelineChart.vue      # Songs by decade
│   ├── SongList.vue           # Dashboard song lists
│   ├── RecordsPage.vue        # Unified songs, channel tracks, and playback tables
│   └── SongDetailsModal.vue   # Artwork, statistics, and lyrics
├── services/
│   ├── api.js                # Axios API calls and channel definitions
│   ├── records.js            # URL state, validation, and time formatting
│   ├── api.test.js
│   └── records.test.js
├── App.vue                   # Hash navigation and dashboard state
├── main.js
└── style.css                 # Shared styles and CSS variables
```

The UI uses Chart.js through vue-chartjs, Axios for HTTP, and native system typography. No external font request is required.

## API integration

| Endpoint | Used for |
| --- | --- |
| `/api/songs` | Dashboard lists, statistics, and charts |
| `/api/songs/current` | Latest observed song per channel |
| `/api/songs/recent` | Recent list |
| `/api/songs/most-played` | Most-played ranking |
| `/api/songs/channel/{id}` | Channel-filtered songs |
| `/api/songs/{id}` | Song details and persisted lyrics metadata |
| `/api/records/tracks` | Paginated track records |
| `/api/songs/library` | Paginated songs combined across channels |
| `/api/records/plays` | Paginated playback history |

See the [Records API reference](../docs/records-api.md) for supported query
parameters, validation, response shapes, and timezone behavior.

## Customization

Channel names and colors are defined in `src/services/api.js`:

```javascript
export const CHANNELS = {
  '0': { name: 'Main Mix', color: '#5145a6' },
  '1': { name: 'Mellow Mix', color: '#176d82' },
  '2': { name: 'Rock Mix', color: '#9a3971' }
}
```

Shared colors, card backgrounds, shadows, and border radius are CSS variables
in `src/style.css`; component styles live alongside their templates.

## Visual checks and screenshots

Check desktop and 390px / 320px mobile layouts. Page content should not scroll
sideways; Records keeps wide columns inside its labeled table scroller. Confirm
keyboard selection in the most-played ranking and decade controls, modal focus
and Escape, and Records filtering/pagination. Reduced-motion preferences disable
chart and CSS animations.

Artwork uses a shared placeholder when a URL is missing or fails. The placeholder
resets when a new song supplies a different URL. Records and long song lists keep
lazy loading: before taking a full-page screenshot, scroll through the view,
allow covers to finish loading, then return to the top. Check that each image has
loaded or has a visible fallback; a full-page capture alone does not load every
image below the viewport. Use the current screenshot filenames in `docs/screenshots`.
