# Self-hosted observability

Start with both files: `docker compose -f compose.yaml -f compose.observability.yaml up -d`.
The overlay requires `GRAFANA_ADMIN_PASSWORD` and `PG_EXPORTER_PASSWORD` in the deployment `.env`.
Create an empty regular `.secrets/smtp_password` file before startup; Docker must not create a directory at that path.

Grafana is publicly available at https://rparadise.138.2.168.194.sslip.io/grafana/ through Caddy HTTPS.
Log in with the existing `admin` account and deployment password. Anonymous access and signup are disabled;
production uses Secure, SameSite=Lax session cookies. No new public port is opened. Grafana's direct host
port remains `127.0.0.1:3001` for internal health checks; normal browser access uses the HTTPS URL.
Set `GRAFANA_ROOT_URL` to the full public URL ending in `/grafana/`, and `GRAFANA_COOKIE_SECURE=true` in
production `.env`. Local HTTP development defaults to `http://localhost:8080/grafana/` and insecure cookies;
set `GRAFANA_ROOT_URL` explicitly when using another local port. The proxy preserves the `/grafana/` prefix,
as required by Grafana's `serve_from_sub_path` setting. Caddy blocks `/grafana/metrics` and its subtree,
because Grafana otherwise exposes these metrics without login.

The Operations folder contains Overview (`radioparadise-ops`), API & Upstream (`radioparadise-api`),
Oracle VM (`radioparadise-vm`), and Application Logs (`radioparadise-logs`). Navigation preserves the time range.
Dashboard and datasource edits belong in these files because provisioning overwrites UI edits.

The API dashboard shows internal `/api/*` request rate, method/route/status breakdowns, latency mean/p95/p99,
request totals, outbound RP status/transport failures and latency, collector channel outcomes/freshness, and DB pool waits.
`http_client_requests_seconds_*` measures observed RestClient calls (including body extraction); collector duration
additionally covers decoding and persistence. RP channel values remain URI placeholders, avoiding unbounded labels.
Transport failures can use `CLIENT_ERROR` or `IO_ERROR` status. HTTP200 with an empty/malformed application response
can still be an HTTP success; consult collector outcomes for end-to-end success. No request bodies or per-call traces
are stored. Request totals from Prometheus `increase` are estimates; quiet periods may have empty percentile/error panels.

The VM dashboard shows capacity/uptime, CPU modes and normalized load, RAM/available memory, swap, PSI pressure,
OOM kills, root disk/inodes, physical disk throughput/IOPS/latency, VM network bytes/errors/drops and exporter health.
Zero total swap is expected on this VM. No per-container resource exporter or additional service was added.

The application log dashboard is `/grafana/d/radioparadise-logs`. It provides severity and text filters,
an entry-volume chart, all application logs and a separate warnings/errors panel. Its queries
parse historical plain-text severity and retain unclassified startup lines in All. Only
`job="docker"` production records are shown, excluding isolated pipeline validation fixtures.
Grafana Explore's Live mode can tail `{job="docker",service="app"}`.

The **MDC field** dropdown selects `userAgent`, `clientIp`, `traceId`, `requestId`,
`httpMethod`, `requestPath`, `httpStatus` or `durationMs`. Enter a case-sensitive
literal value in **MDC value (contains)**, for example `sinan-was-here` or `Chrome`.
The filter matches text within that field. Leave it empty to include all logs,
including historical/startup/collector lines without MDC. These controls filter
the volume chart and both log panels. **Search text** remains a separate literal
search across the entire log line. Quotes, backslashes, backticks and spaces do
not need special escaping; regular expressions are not interpreted.

MDC fields are extracted from the fixed request-context prefix at query time;
they are not indexed stream labels and do not appear in Loki's label-selector menu.
The volume query discards per-request fields after filtering to keep its series
bounded by severity. No ingestion/schema change or reprocessing is needed for
existing logs. Textbox values use Grafana's percent-encode formatter and Loki's
`urldecode` inside a `contains` template so values cannot alter the query/template
syntax. Temporary boolean match fields are removed after filtering.

### Request context (MDC)

Backend request logs include `traceId`, `requestId`, `clientIp`, `userAgent`,
`httpMethod` and `requestPath`. A completion entry adds `httpStatus` and `durationMs`.
Application logs emitted during the same synchronous request carry the same context;
the previous thread context is restored on success and failure. Error dispatches reuse
the original IDs and path without adding a second completion entry. Scheduled collector
logs have no HTTP context. Successful actuator requests log completion at DEBUG to avoid
health-check/scrape noise; server failures log at WARN.

Responses include `X-Trace-ID` and `X-Request-ID`. A valid version-00 W3C `traceparent`
supplies the trace ID; otherwise the app generates a 32-character hexadecimal ID.
`X-Request-ID` is accepted only when it contains 1–64 letters, digits, dots, underscores
or hyphens; otherwise it is regenerated. These IDs correlate logs, without installing
distributed tracing or creating/exporting spans. Request headers are untrusted correlation
values, not proof of identity.

Tomcat's native forwarded-header handling resolves `clientIp` from Caddy's
`X-Forwarded-For` using its internal-proxy trust rules. The filter uses `getRemoteAddr()`
and never parses forwarded headers itself. Keep the backend port private as in Compose;
adjust `server.tomcat.remoteip.internal-proxies` for a different trusted proxy network,
or set `SERVER_FORWARD_HEADERS_STRATEGY=NONE` for a direct deployment.
See [Spring Boot's proxy configuration](https://docs.spring.io/spring-boot/4.0/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server).

User agents are capped at 512 characters and paths at 2048; control characters are
replaced and quotes/backslashes escaped. Query strings, bodies, cookies, authorization
and arbitrary headers are excluded. Client IP and user agent remain in log text and
follow Loki's seven-day retention; they are never metric tags or indexed Loki labels.
Search an ID in the Application Logs dashboard text filter, or in Explore:

```logql
{job="docker",service="app"} |= "traceId=4bf92f3577b34da6a3ce929d0e0e4736"
```

Alloy groups new Java stack traces while per-file labels still separate containers, then assigns
a bounded lowercase `level` label. Multiline grouping flushes after 3 seconds or 256 lines.
It does not drop logs by severity or enable otherwise-disabled application DEBUG/TRACE logging.
Initial integration validation used isolated INFO/WARN/ERROR fixtures with a multiline exception:
six input lines became three Loki events with the correct labels; the validator was removed.

## Database setup

Use a dedicated login called `postgres_exporter`, grant `pg_monitor` and `CONNECT ON DATABASE radioparadise`,
and set its password to `PG_EXPORTER_PASSWORD` through a secure deployment procedure. Do not use the application login.
The overlay preloads `pg_stat_statements`; after PostgreSQL restarts, execute as the database owner:

```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

The exporter enables statement statistics but does not include SQL query text as metric labels.
The dashboard uses standard PostgreSQL exporter metrics. Slow statements over 500 ms, lock waits and checkpoints
also appear in PostgreSQL container logs. For diagnosis, query the database directly (query text can contain sensitive values):

```sql
SELECT queryid, calls, round(mean_exec_time::numeric, 2) AS mean_ms,
       round(total_exec_time::numeric, 2) AS total_ms, rows, query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;
```

## Data and limits

Observability container memory limits total 2144 MiB. Grafana has a 768 MiB container limit and a 512 MiB Go
memory target; the initial 320 MiB limit caused heavy memory reclaim during startup on this VM.
Metrics scrape every 30 seconds. Prometheus keeps up to 15 days
or 2 GB of persisted blocks, whichever comes first; WAL/head data adds overhead beyond the block retention setting.
Loki keeps 7 days using its compactor. Filesystem Loki has no disk byte cap; retention deletion is asynchronous and
the host disk alerts are essential. Loki ingestion is capped at 1 MiB/s and 2 MiB bursts. Docker json-file logs rotate
at 10 MiB with 3 files per container. Volumes survive container recreation.

Alloy reads only Docker logfiles through a read-only bind mount. It has no Docker API socket. The Docker data
root is assumed to be `/var/lib/docker`; adjust both the mount and file matcher if the daemon uses a different
data root. Compose service/project attributes are attached by the overlay's Docker log driver configuration.
Existing containers need recreation to acquire these settings. Service names remain stable after recreation;
container IDs are not log labels.

On a fresh installation, initialize Alloy's data volume ownership before starting it. The image's data directory
belongs to the `alloy` user, while this deployment runs as root without capabilities to read root-owned Docker logs.
Without this one-time step it cannot write its positions/remotecfg files:

```sh
docker compose -f compose.yaml -f compose.observability.yaml run --rm --no-deps \
  --cap-add CHOWN --cap-add DAC_OVERRIDE --entrypoint /bin/sh alloy \
  -c 'chown -R 0:0 /var/lib/alloy'
```

Normal Alloy execution still drops every capability; the initialization capabilities are not persisted.

Node exporter reads the host root filesystem read-only and uses the host PID and network namespaces, without
privileged mode. The host network namespace is essential: otherwise netdev metrics describe the container's eth0.
It binds only to Docker's private host gateway `172.17.0.1:9100`, scraped through `host.docker.internal`.
Oracle's host firewall rejects Docker-to-host input by default. A persistent rule in `/etc/iptables/rules.v4`
allows TCP9100 only from `172.18.0.0/16` arriving on `br+` interfaces to `172.17.0.1/32`.
It does not allow the public interface. The original firewall file is in the expansion backup.
For a fresh VM with the same Docker subnet/gateway, insert before the INPUT reject and persist the same line:

```sh
sudo iptables -I INPUT 1 -s 172.18.0.0/16 -d 172.17.0.1/32 -i br+ -p tcp --dport 9100 -j ACCEPT
```

Use the actual Compose subnet from `docker network inspect rparadise-tracker_default` on another VM.
If the Compose network is recreated with another subnet, update this rule. On hosts with another Docker gateway,
set `NODE_EXPORTER_LISTEN_ADDRESS` accordingly and verify routing/firewall access.
Changing the scrape address starts new node time series; the VM dashboard uses current targets. Existing history is retained.
This setup does not provide per-container CPU/memory accounting.
The HTTPS probes run on this VM and verify certificate validation, HTTP200 and TLS. They cannot detect total VM
or regional network loss from outside the VM.

## Alerts and email activation

Prometheus evaluates rules and sends alerts to the internal Alertmanager. Grafana shows firing alerts and provides
an Alertmanager datasource for active alerts and silences. The checked-in active Alertmanager config has an empty
receiver: **no email is sent until SMTP is activated**.

For the authorized Gmail sender/recipient, install a Google App Password in `.secrets/smtp_password` without a
trailing newline, with host owner UID 65534 and mode 0400. The parent directory may remain mode 0700 because Compose
bind-mounts the single file. Never commit this file. Copy `alertmanager/gmail.yml.example` to
`alertmanager/alertmanager.yml`, validate it with `amtool check-config`, and recreate Alertmanager.
Check `/api/v2/status` internally and send a temporary synthetic alert to verify actual email delivery and resolution.
Configuration validation alone does not prove mail delivery. Grouping/repeat intervals suppress repeated messages;
the default repeat interval is 4 hours. TargetDown inhibits related collector/application/DB alerts for the same target.

User-facing HTTP panels/rules exclude `/actuator.*`. The 5xx and p95 alerts require 20 requests per 5 minutes to reduce
noise with low traffic; probes and collector alerts cover quiet periods. Collector alerts require 180 seconds without
a successful poll after the app has been running 180 seconds, then remain pending 2 minutes. A successful unchanged
song still counts as collector success.

## Configuration validation

Run from the deployment directory after images are available:

```sh
docker compose -f compose.yaml -f compose.observability.yaml config --quiet
docker compose -f compose.yaml -f compose.observability.yaml run --rm --no-deps --entrypoint promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose -f compose.yaml -f compose.observability.yaml run --rm --no-deps --entrypoint promtool --workdir /etc/prometheus prometheus test rules alerts.test.yml
docker compose -f compose.yaml -f compose.observability.yaml run --rm --no-deps --entrypoint /bin/loki loki -config.file=/etc/loki/config.yml -verify-config=true
docker compose -f compose.yaml -f compose.observability.yaml run --rm --no-deps alloy validate /etc/alloy/config.alloy
docker compose -f compose.yaml -f compose.observability.yaml run --rm --no-deps --entrypoint amtool alertmanager check-config /etc/alertmanager/alertmanager.yml
```

Confirm all Prometheus targets are UP, both probes succeed, Loki has service labels/logs, Grafana queries return
data, and the root disk panel reflects the host filesystem. App series appear after relevant activity.
Review `docker stats --no-stream` under normal traffic before increasing retention or scrape frequency.

## Primary references

- [Prometheus storage retention](https://prometheus.io/docs/prometheus/latest/storage/)
- [Loki filesystem storage limitations](https://grafana.com/docs/loki/latest/configure/storage/)
- [Loki compactor retention](https://grafana.com/docs/loki/latest/operations/storage/retention/)
- [Alloy log processing](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.process/)
- [PostgreSQL exporter v0.20.1 configuration](https://github.com/prometheus-community/postgres_exporter/blob/v0.20.1/README.md)
- [Node exporter container host mounts](https://github.com/prometheus/node_exporter#docker)
- [Alertmanager email configuration](https://prometheus.io/docs/alerting/latest/configuration/#email_config)
- [Grafana plugin update configuration](https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/#preinstall_auto_update)

Explicit image tags are pinned in the overlay, not floating `latest` tags. Update them deliberately with config
validation and runtime checks. The stack uses no paid or externally hosted telemetry service.

## Expansion rollout and rollback

Before this rollout, the VM database, inputs and `.env` were backed up in a private directory recorded by
`/home/ubuntu/rparadise-observability-last-backup`. The previous app image is tagged
`rparadise-tracker-app:before-observability-expansion`. No credentials were rotated.

To revert, restore `inputs.tgz` from that backup into the deployment directory, recreate Grafana/node-exporter/Caddy
with the two Compose files, and reload Prometheus using SIGHUP. Recreate Caddy after restoring archived inputs: archive extraction
can replace the file inode, so a reload alone may still read an old bind-mounted file. Retag the saved previous image as
`rparadise-tracker-app:latest` and recreate app with `--no-build --no-deps` if reverting client instrumentation too.
Restoring `.env` reverts the public URL/cookie settings. If reverting the exporter networking, also remove the
rule marked `rparadise-node-exporter` from the live INPUT chain and `/etc/iptables/rules.v4` (the original is
`firewall-rules.v4` in the backup). Do not restore the entire runtime firewall blindly: Docker manages its own chains.
No database restore is needed for this rollout: it changes no schema.
