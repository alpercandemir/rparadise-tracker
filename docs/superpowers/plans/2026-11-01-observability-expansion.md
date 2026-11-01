# Observability expansion implementation plan

**Goal:** Observe RP outbound calls, internal API endpoints and VM capacity, and provide authenticated public HTTPS Grafana access.

**Architecture:** Keep the existing self-hosted stack and 30-second scraping. Wire the existing RestClient to Micrometer observations, enable bounded client histograms, provision API/VM dashboards, and route `/grafana/` through Caddy. No new service or paid resources.

**Authorization:** User delegated dashboard and implementation choices. Execute in the current checkout, preserving existing uncommitted work. Do not commit or replace production secrets.

## Constraints and review focus

- URI templates only: no song IDs, raw request URLs or query parameter values in metric labels.
- Preserve RP connect/read timeouts, empty-result behavior, and existing application/DB changes.
- Client failures (HTTP errors and transport errors) must emit measurements.
- Grafana requires login; public metrics and database/exporter ports stay private.
- Existing dashboards/logs remain accessible with correct subpath links.
- Host disks/interfaces need filters excluding Docker pseudo devices; zero swap is legitimate.

## Task 1: Outbound client observations

- [x] Add real HTTP fixture tests proving success, HTTP failure, transport failure and URI-template cardinality.
- [x] Run tests red, inject ObservationRegistry into the existing RestClient and enable client histograms/SLOs.
- [x] Run targeted and full backend tests.

## Task 2: Dashboards and access

- [x] Provision API dashboard with rates, statuses, endpoint latency/volume, upstream errors and collector context.
- [x] Provision VM dashboard with CPU modes/load, RAM, swap, disk capacity/inodes/I/O, network, pressure and uptime.
- [x] Link dashboards and logs under `/grafana/`; add proxy/subpath/session settings and a public login probe.
- [x] Validate JSON, PromQL, Compose, Caddy and existing alert-rule tests.

## Task 3: Deploy and verify

- [x] Back up changed production inputs and database, capture current app image for rollback.
- [x] Build/test before replacing the app; deploy only changed inputs and services.
- [x] Verify actual client histograms, server endpoint metrics, every new panel query, scrape/probe health and public login/auth protection.
- [x] Document public access, metric semantics, limitations and rollback. Review the diff.

## Verification evidence

- Local and ARM64 deployment build: 33 backend tests discovered, 21 passed, 12 opt-in PostgreSQL tests skipped; no failures/errors. Frontend tests/build and application image build passed.
- Compose, Caddy and Prometheus configuration valid; all 4 existing promtool alert test scenarios passed (13 rules).
- 24 API and 43 VM PromQL queries executed successfully against production. All panels have finite data except RP error-type history, which is correctly empty without upstream failures.
- 10 scrape targets UP, all 3 HTTPS probes successful. RP client count and histogram buckets present with URI `/api/now_playing?chan={channel}`; all 3 collector channels fresh.
- Host network counter verified on `enp0s6`; host metrics fixed using private host-network exporter plus persistent Docker-bridge-only firewall allowance.
- Public login/website/API return 200; anonymous Grafana API returns 401; app and Grafana metrics paths/subtrees return 404. Actual login returns Secure/HttpOnly/SameSite=Lax session cookie. Browser login page loaded.
- Resource check: ~4.4 GiB available RAM, ~35 GiB available disk; no new services or paid capacity.
- Backups: `/home/ubuntu/rparadise-observability-backup-20261101T115505Z`; previous app image tagged for rollback.

## Review and implementation decisions

Independent focused review found two issues, both fixed and runtime-verified: unauthenticated Grafana metrics exposure, and empty healthy error-ratio stats.
Caddy must be recreated after archive extraction replaces a bind-mounted file inode; reload alone read the previous file.
Host exporter network mode required an explicit private INPUT allowance because Oracle's default firewall rejects Docker-to-host requests. The rule is persisted and the original firewall file backed up.
No per-container resource exporter was added; whole-VM, JVM and existing service metrics meet the requested scope without adding service overhead.
