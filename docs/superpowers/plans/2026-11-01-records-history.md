# Records and Playback History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship sortable, searchable, paginated song records and durable playback history, then push and deploy to the existing Oracle VM.

**Architecture:** Keep the Vue dashboard and add hash-based Records navigation. Add a playback event entity and record events transactionally through the existing SongService boundary. New read endpoints use Spring Data specifications and explicit page DTOs; the latest persisted event per channel deduplicates the first poll after restart.

**Tech Stack:** Vue 3, native node tests, Spring Boot 4 / Java 25, PostgreSQL 17, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-11-01-records-history-design.md`

## Global Constraints

- Preserve existing dashboard responses, song records, and channel IDs 0=Main, 1=Mellow, 2=Rock.
- No fabricated old history or paid services. Timestamp means first observation.
- Preserve concurrent observability edits; do not change its collector or infrastructure files.
- Use 25/50/100 rows, stable sorting, URL state, and Europe/Istanbul display.
- User explicitly approved implementation, push, and production deployment; execute inline without another approval round.

## Review Focus

- App restart on an unchanged track must not increment counts or duplicate history: recording unit + PostgreSQL restart-state test.
- Text containing SQL LIKE wildcards must match literally: PostgreSQL search test.
- Same-time rows must page deterministically; out-of-range pages are empty: PostgreSQL paging tests.
- Date range ends at the following Istanbul midnight, not UTC midnight: PostgreSQL boundary test.
- Fast filter changes and browser back must not show obsolete results: UI request sequencing + URL state tests/browser validation.

### Task 1: Durable play recording

**Files:** Create `model/PlaybackEvent.java`, `repository/PlaybackEventRepository.java` under `src/main/java/com/radioparadise/tracker/`; modify `service/SongService.java`; extend `src/test/java/com/radioparadise/tracker/service/SongServiceTest.java`.

**Interfaces:** `SongService.saveOrUpdateSong(RadioParadiseResponse, String): Song` stays compatible. PlaybackEvent has id, Song, channel, Instant observedAt. Repository exposes latest channel event and earliest global event with an entity graph for Song.

- [ ] Write recording assertions for first observation, unchanged poll/recreated service, track return, separate channels, and event-save failure.
- [ ] Run targeted tests, observe missing event behavior fail.
- [ ] Implement transactionally persisted recording and restart deduplication; retain scheduler interface and metrics.
- [ ] Run complete backend unit suite successfully.

### Task 2: Server-side records API and PostgreSQL verification

**Files:** Create `controller/RecordsController.java`, `service/RecordsService.java`, `dto/RecordsPage.java`, `dto/TrackRecord.java`, `dto/PlaybackRecord.java`, `service/RecordsQuery.java`; extend SongRepository and PlaybackEventRepository; create `src/test/java/com/radioparadise/tracker/records/RecordsPostgresTest.java`, `scripts/test-records-postgres.sh`, and `docs/records-api.md`.

**Interfaces:** `GET /api/records/tracks` and `GET /api/records/plays`: page (0-based), size (25 default), sort, direction, q, channel; plays also from/to as ISO dates. DTO: items, page, size, totalElements, totalPages, historyStartedAt. Track rows expose metadata + ISO offset firstPlayed/lastPlayed; play rows expose immutable playedAt instant + metadata.

- [ ] Write query validation tests and PostgreSQL integration tests for combined filters, stable page boundaries, literal wildcard search, invalid inputs, Istanbul date bounds, transaction rollback, and persisted dedupe.
- [ ] Run tests before implementation and observe intended failure.
- [ ] Implement allowlisted query validation, criteria predicates, stable sort, offset timestamp DTOs, and bounded page queries.
- [ ] Run full backend suite and dedicated disposable PostgreSQL integration suite successfully; document verified endpoints and response fields.

### Task 3: Records page and delivery

**Files:** Create `frontend/src/components/RecordsPage.vue`, `frontend/src/services/records.js`, `frontend/src/services/records.test.js`; modify `frontend/src/App.vue` and `frontend/src/services/api.js`.

**Interfaces:** Hash navigation `#/records?tab=tracks|plays&page=0&size=25&sort=...&direction=...&q=...&channel=...&from=...&to=...`. Frontend requests the Task 2 APIs; rows use explicit DTO timestamps.

- [ ] Write URL normalization, tab/sort reset, formatting, and records request tests and observe failures.
- [ ] Implement Records tabs, responsive table, all metadata/time columns, search/channel/date controls, column sort, paging, URL synchronization, request sequencing, and error retry. Clean up dashboard refresh timer on unmount.
- [ ] Run frontend tests/build and all backend tests; validate UI against populated local PostgreSQL, including pagination and browser history.
- [ ] Obtain fresh whole-feature review and resolve material findings with regression tests.
- [ ] Commit only feature files, push main, back up production DB/config, deploy the committed feature while preserving server observability, verify live API/UI/health, and share endpoints with the user.
