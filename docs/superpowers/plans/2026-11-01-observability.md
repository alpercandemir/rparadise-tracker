# Free observability implementation plan

Goal: operate the existing Radio Paradise app with useful metrics, searchable logs,
actionable alerts and clear runbooks, inside the existing free Oracle VM.

User delegated SRE implementation decisions and requires no paid resources.
Use the existing checkout and preserve unrelated user changes. Do not commit.

Design: self-host Prometheus, Grafana OSS, Loki, Alloy, host/PostgreSQL exporters,
blackbox probes and Alertmanager on the existing ARM64 1 OCPU / 6 GB VM.
Grafana binds only to loopback and is reached via SSH. Prometheus retains 15 days
and at most 2 GB of blocks; Loki retains 7 days. All containers have memory limits
and rotated logs. No paid service, Oracle resize or additional cloud volume.
Alternatives: terminal-only lacks history and correlations; hosted SaaS introduces
account/quota dependencies. Local open-source services fit the existing capacity.

- [x] Add tested per-channel collector freshness/outcomes/duration and Micrometer
  Prometheus endpoint. Preserve public Actuator blocking. Disable SQL noise.
- [x] Provision metrics/log pipelines, dashboard and actionable alert rules.
- [x] Keep exporter SQL access limited to pg_monitor; generate local credentials.
- [x] Back up production database/config before deploying, build/tests before replace.
- [x] Validate configs, all scrape targets, logs ingestion, dashboards, alert routing,
  HTTPS availability, database continuity and resource usage after rollout.
- [ ] Configure email receiver glanceappdev@gmail.com once SMTP credentials exist.
  Never claim delivery verified until a real test email is delivered/accepted.
- [x] Document access, common investigations, thresholds, retention, rollback and
  the single-VM blind spot: this monitor cannot report if its own VM is down.

Verification: dedicated observability application image passed 13 backend tests;
Prometheus/Loki/Alloy/Alertmanager configuration validation passed; four alert
scenarios passed promtool. Nine scrape targets were UP; both HTTPS probes succeeded;
all 11 services appeared in Loki, including app/PostgreSQL/Caddy streams. Grafana's
21-panel dashboard and both metrics/log datasource health checks succeeded. A
synthetic Alertmanager alert was accepted and resolved. All 12 song IDs from the
pre-deployment backup remained present (33 songs at verification). Public app/API
returned 200, public metrics returned 404. No email was sent: SMTP file is empty.

Runtime fixes: initialize Alloy volume ownership for root with capabilities dropped;
raise Grafana memory limit from 320 to 768 MiB with GOMEMLIMIT=512MiB after cgroup
reclaim pressure was measured. Existing VM capacity is unchanged; post-rollout
MemAvailable was about 4.4 GiB and root filesystem free space about 35 GiB.

The application build used an isolated snapshot of d3e78a1 plus observability files
to avoid shipping another chat's unfinished Records feature. That chat subsequently
committed/deployed its work independently; do not revert or overwrite those changes.
