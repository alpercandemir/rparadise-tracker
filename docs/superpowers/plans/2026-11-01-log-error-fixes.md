# Application Log Error Fixes Implementation Plan

> **For agentic workers:** Execute inline with test-driven development and verification before completion.

**Goal:** Reject incomplete song metadata without persistence errors, report successful tracking only after commit, and disable Open in View.

**Architecture:** Validate artist/title/album in `SongService` before repository access; a specific validation exception lets the scheduler warn and retain its existing failure/retry accounting. Log successful tracking in the scheduler after the transactional service returns. Existing records DTO conversion stays within its service transaction.

**Tech Stack:** Java 25, Spring Boot 4, PostgreSQL 17, JUnit, Mockito, Micrometer.

**Spec:** User-approved findings in this chat: two channel 2 null-title failures, premature success logging, and the Open in View startup warning.

## Global Constraints

- Preserve all pre-existing working-tree changes; do not commit unrelated work.
- Keep database NOT NULL constraints and existing poll metrics/retry behavior.
- Use a disposable local database for integration tests.

## Review Focus

- Null, empty, and whitespace-only artist/title/album must fail before repository access.
- Invalid metadata must not advance collector freshness or its song checkpoint.
- Other channels and the next valid poll must still succeed.
- A failed transaction must not emit a success message.
- Records/history/dashboard endpoints must work with Open in View disabled.

## Task 1: Validation, transaction-aware logging, and Open in View

**Files:** `SongService.java`, new `InvalidSongMetadataException.java`, `SongTrackerScheduler.java`, `application.properties`, service/scheduler tests, and existing `RecordsPostgresTest.java`.

**Interfaces:** Preserve `SongService.saveOrUpdateSong(RadioParadiseResponse, String): Song`; introduce `InvalidSongMetadataException` for the scheduler's warning path.

- [x] Add failing tests for missing metadata, failed-poll metrics/recovery, and absence of success logs on failed persistence.
- [x] Run targeted Maven tests and confirm the expected failures.
- [x] Validate required fields before any repository access; catch validation failures as warnings; move success logging after the service returns; set `spring.jpa.open-in-view=false`.
- [x] Run the complete Maven suite and disposable PostgreSQL API/integration tests with `scripts/test-records-postgres.sh`; require zero failures.
- [x] Review only this task's changes, preserving the existing working tree.
- [x] Deploy the tested changed files after checking the remote baseline, with a rollback image and source backup; verify health, API endpoints, successful polls, and logs.

## Execution Notes

The user explicitly authorized these fixes; continue inline without an additional plan approval step. Reuse the current checkout because the deployed work already depends on its uncommitted changes. Do not create a commit unless requested.

Validation: targeted regressions failed for the expected missing validation and premature logging, then passed. Full disposable PostgreSQL verification passed 48 tests, zero failures/errors/skips. Independent review identified a null/literal-null song-key collision; its regression failed before replacing the string key with a record and passed in the final suite. No findings remain deferred.

Deployment: app recreated healthy at approximately 15:21 Istanbul time on 2026-11-01. Only six changed source/test/config files were uploaded after baseline SHA-256 checks. Previous image: `rparadise-tracker-app:before-logfix-20261101-1219`; source/build log backup: `/home/ubuntu/rparadise-logfix-backup-20261101-1219`. No database migration or other container recreation.

Live verification: health UP; dashboard song endpoints and tracks/plays APIs returned data with UTC timestamps; public root/tracks/plays returned HTTPS 200. All three channels had recent successful polls. Incomplete metadata on channels 1 (15:22:44) and 0 (15:23:46) produced concise warnings and recovered on the following polls; corresponding failure counters incremented once. No ERROR entries or Open in View warning since deployment; container healthy with zero restarts.
