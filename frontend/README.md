# Radio Paradise Tracker — Frontend

Vue 3 + Vite dashboard for the Main, Mellow, and Rock mixes. See the
[project README](../README.md) for the full Docker stack, screenshots, backend
setup, and deployment instructions.

## Views

- **Dashboard** (`/#/`): live channel cards, statistics, channel distribution,
  most-played tracks, songs by decade, and All Songs / Recent / channel lists.
- **Records — All Tracks** (`/#/records`): searchable, sortable track records
  with artwork, metadata, first/last observed times, and play counts.
- **Records — Playback History** (`/#/records?tab=plays`): individual observed
  plays with channel and date filters. History starts with collection; earlier
  broadcasts are not reconstructed.
- **Song details**: select a song from a card, list, chart track, or Records
  title/artwork to open large artwork, channel-specific listening statistics,
  duration, and lyrics from Radio Paradise when available.

Records supports search by track, artist, or album, channel filtering, column
sorting, and 25/50/100 rows per page. Filters, sorting, and pagination are stored
in the URL so views can be bookmarked and browser Back/Forward works.
Timestamps include seconds and use the browser's timezone; playback date
filters use the same timezone. The API returns UTC instants.

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
or Records is open. Browsing lists, charts, scroll position, focus, and the
selected modal stay stable. Returning from Records reloads dashboard data.

Records reloads when filters, sorting, or pagination change and has an explicit
**Refresh** button. Backend caches can retain responses for 30 seconds, so
refreshing does not guarantee new data immediately. See the
[caching notes](../README.md#song-details-and-caching).

## Components and services

```text
frontend/src/
├── components/
│   ├── CurrentSong.vue        # Live channel card
│   ├── StatsCard.vue          # Dashboard statistic
│   ├── ChannelChart.vue       # Channel distribution
│   ├── MostPlayedChart.vue    # Top tracks by play count
│   ├── TimelineChart.vue      # Songs by decade
│   ├── SongList.vue           # Dashboard song lists
│   ├── RecordsPage.vue        # Track and playback tables
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

The UI uses Chart.js through vue-chartjs, Axios for HTTP, and Inter typography.

## API integration

| Endpoint | Used for |
| --- | --- |
| `/api/songs` | Dashboard lists, statistics, and charts |
| `/api/songs/current` | Latest observed song per channel |
| `/api/songs/recent` | Recent list |
| `/api/songs/most-played` | Most-played chart |
| `/api/songs/channel/{id}` | Channel-filtered songs |
| `/api/songs/{id}` | Song details and persisted lyrics metadata |
| `/api/records/tracks` | Paginated track records |
| `/api/records/plays` | Paginated playback history |

See the [Records API reference](../docs/records-api.md) for supported query
parameters, validation, response shapes, and timezone behavior.

## Customization

Channel names and colors are defined in `src/services/api.js`:

```javascript
export const CHANNELS = {
  '0': { name: 'Main Mix', color: '#667eea' },
  '1': { name: 'Mellow Mix', color: '#4facfe' },
  '2': { name: 'Rock Mix', color: '#f093fb' }
}
```

Shared colors, card backgrounds, shadows, and border radius are CSS variables
in `src/style.css`; component styles live alongside their templates.
