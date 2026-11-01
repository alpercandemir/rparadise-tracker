# Song details and caching

Approved approach: shared Vue modal, on-demand LRCLIB lyrics persisted in PostgreSQL,
and bounded 30-second backend caches. Continue through commit, push, and Oracle deployment.

1. Add and test a bounded cache with expiry, concurrent miss coalescing, and exception handling.
2. Cache RP reads by channel and immutable API list/page responses by validated query.
   Keep the scheduler as the only RP caller. Use server caching only to avoid stacked browser TTLs.
3. Add nullable song metadata columns and a details endpoint. Fetch exact artist/title/album
   matches from LRCLIB with short timeouts, store lyrics, source, duration, status, and check time.
   Retry misses after one day, failures after five minutes, and successful results after 30 days.
   Update only enrichment columns so concurrent collection cannot lose play counts.
4. Build one accessible native dialog with large cover, facts, listening statistics,
   lyrics attribution, honest empty/error states, keyboard dismissal, and focus restoration.
   Wire cards, list rows, chart song selections, tracks, and playback-history song IDs.
5. Verify unit tests, frontend build, PostgreSQL integration, and browser desktop/mobile behavior.
6. Commit only task files, push the current branch, back up production PostgreSQL,
   upload tracked build inputs, rebuild with both Compose files, and verify live API/UI/health.

The existing Java 25 / Spring Boot / Vue stack and VM are retained. Existing rows remain valid;
Hibernate adds nullable columns. No bulk lyrics crawl, paid services, or fabricated trivia.
User-staged file deletions are left outside this commit.
