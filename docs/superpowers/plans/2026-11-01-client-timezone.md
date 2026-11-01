# Client timezone implementation plan

Goal: display first/last played, play history, and history start in the browser's
timezone; use that same timezone for history date filtering. User requested the
change and asked how UTC is handled. Prior push/deploy authorization continues.

Design: events already store UTC Instants. Legacy Song first/last fields store
Istanbul wall time; interpret these at their known source timezone and return
UTC Instants in every song API. Do not reinterpret legacy wall time as UTC.
No data migration is needed for this display/query change. Keep legacy database
storage explicit in API documentation. The browser uses Intl's detected IANA
timezone and shows its name. API clients can supply timeZone; retain the old
Istanbul date-filter default for callers that omit it. Use ZoneId calendar days,
including daylight-saving transitions, rather than fixed hour offsets.

- [ ] Add frontend regression tests for browser-default zones, different zones,
  UTC input vs offset input, day rollover, and daylight saving. Observe failure.
- [ ] Add backend tests for UTC track serialization and date-filter boundaries
  in Istanbul, New York (23/25-hour days), Tokyo and UTC; reject invalid zones.
- [ ] Implement Intl formatting/labels, UTC DTO output for records and legacy
  dashboard endpoints, and timeZone-aware date-filter predicates.
- [ ] Run frontend tests/build and PostgreSQL integration verification. Obtain
  an independent review of the scoped change and resolve material findings.
- [ ] Commit and push only this change. Back up production DB, upload committed
  feature files, deploy app with both existing Compose files, and verify live
  API output, local timezone labels, record continuity, and health.

Existing checkout and unrelated observability changes are preserved. The legacy
database representation is kept readable; a physical storage migration to UTC
would need a separately tested schema conversion and is outside this change.
