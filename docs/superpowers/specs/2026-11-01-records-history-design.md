# Records and playback history

## Intent and agreed scope

The user wants all saved records in a paginated, sortable view, including when
tracks played and their metadata. They explicitly chose both a unique-track
summary and individual playback history. Preserve the existing dashboard and
the corrected upstream channel IDs: Main 0, Mellow 1, Rock 2.

## Approaches and recommendation

1. **One Records page, two tabs (recommended):** All Tracks and Playback History
   share filtering, table presentation, and pagination conventions. Navigation
   stays compact while the distinction between a track and a play stays clear.
2. **Two separate pages:** Direct navigation to either view, but more navigation
   and duplicated controls for closely related records.
3. **One combined table:** Fewer views, but mixes track totals with individual
   play events and makes the meaning of a row ambiguous. Do not use this option.

Both datasets and the shared page/tabs design were approved by the user on
2026-11-01, including commit, push, deployment, and endpoint documentation.

## Page behavior

- Add Dashboard / Records navigation, with a linkable Records URL and refresh
  support using hash navigation to avoid new proxy routing requirements.
- All Tracks: one row per existing artist/title/album/channel record; show cover,
  title, artist, album, release year, channel, first observed play, last observed
  play, and play count. Existing records remain visible immediately.
- Playback History: one row per newly observed play; show observed date/time,
  channel, title, artist, album, release year, and cover.
- Both tabs support text search across title/artist/album and channel selection.
  History additionally supports an inclusive date range in Europe/Istanbul.
- Sort relevant table columns ascending/descending. Default to last played
  descending for tracks and observed date/time descending for history. Break
  ties by ID so paging has a deterministic order for unchanged data.
- Page sizes: 25, 50, 100. Show total results, current range, page number, and
  previous/next controls. Filter/sort/page-size changes return to page one.
- Keep tab, filters, sort, and page in the URL so refresh and browser back work.
- Display absolute date/time with seconds and explicit Europe/Istanbul timezone,
  rather than only relative labels such as “5 minutes ago”.
- Adapt controls and table access for narrow screens. Include visible loading,
  empty, and retryable error states. An older request must not replace newer
  results when filters change quickly.
- Existing UI is English; match its language with “Records”, “All Tracks”, and
  “Playback History”.

## Playback semantics and storage

The collector polls each channel every 30 seconds. A history timestamp means
“first observed by this tracker”, not a broadcaster-certified start time.
Polling cannot guarantee that every broadcast during downtime was captured.

Add a playback event table referencing Song with an immutable observed-at
timestamp. Insert the event and update the track summary in the same database
transaction, whenever a new play is detected. Repeated polls of an unchanged
song must not create repeated events. Persist the per-channel current-track
identity so an ordinary app restart while that song is still playing does not
create a second event. A change away from a track and later return creates a
new event. If a whole replay occurs while the tracker is offline and resumes
on the same track, the current upstream metadata cannot establish that replay;
do not invent one.

Do not reconstruct historical events from firstPlayed/lastPlayed/playCount.
Those fields cannot reveal all intermediate play times. Show the actual start
of history collection as context in the history view. Preserve existing song
records and counts; schema changes are additive.

## Backend and performance

- Add paginated query endpoints for the two views; preserve existing list API
  responses for dashboard compatibility.
- Perform filtering, sorting, counts, and pagination in PostgreSQL rather than
  loading the whole dataset in the browser.
- Use explicit response DTOs with rows, page, size, total rows, and total pages.
  Return the history collection start time with history metadata.
- Validate allowed sort fields, direction, page bounds, page size, channel, text
  length, and date-range ordering. Invalid requests return useful HTTP 400 errors.
- Add indexes for history timestamp/channel and track association; avoid an N+1
  metadata query for history rows.
- Use explicit time conversions at the API boundary. Existing Song timestamps
  are local Europe/Istanbul values; do not silently reinterpret existing data as
  UTC. New history timestamps should be unambiguous instants.

## Constraints and non-goals

Use the existing Vue, Spring Boot, PostgreSQL, and Oracle Free VM stack. No new
paid service. No fabricated old history, export, authentication changes, or
dashboard redesign. Preserve the concurrent observability work in this checkout;
coordinate any shared collector edits rather than overwriting them.

## Verification and delivery

Test pagination boundaries, sort order/ties, combined search/channel/date
filters, rejected query inputs, and recording semantics (unchanged polling,
track changes, repeats, restart, transaction failure). Verify both views in a
browser, including changing filters and navigating pages. Run the full backend
and frontend checks and production build. Keep feature commits scoped to this
work. Before a production rollout, back up the database, apply additive schema
changes, and verify records, history collection, and app health after deploy.

## Builder choices

Keep endpoint DTOs, query composition, and table styling small and consistent
with the existing code. Reuse table/control code where that makes the two views
consistent without introducing a general-purpose framework. Exact column widths,
icons, component boundaries, and SQL index names are left to implementation.
