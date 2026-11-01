# Oracle Cloud deployment

Initially deployed on 2026-11-01 from commit `8885909`; observability was added later the same day.

- Website: https://rparadise.138.2.168.194.sslip.io/
- VM: `rparadise-tracker`, Frankfurt (`eu-frankfurt-1`), AD-1
- Shape: `VM.Standard.A1.Flex`, 1 OCPU, 6 GB RAM, ARM64
- OS: Ubuntu 24.04 Minimal; boot disk: 46.6 GB
- Public IP: `138.2.168.194` (ephemeral)
- Application directory: `/home/ubuntu/rparadise-tracker`
- Build log: `/home/ubuntu/rparadise-deploy.log`

The VM uses Oracle's Always Free allowance. No account upgrade, paid domain,
load balancer, managed database, or paid workload protection was selected.
Oracle currently documents 1,500 OCPU-hours, 9,000 GB-hours, and 200 GB combined
boot/block storage in its free allowance. These allowances apply across the
tenancy, so check total usage before adding resources.

References: [Oracle Always Free](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm),
[sslip.io DNS and TLS](https://sslip.io/).

## Access and operation

SSH uses the existing local `~/.ssh/id_ed25519` key. Password authentication is
disabled. TCP 22, 80, and 443 are reachable from any IPv4 address. PostgreSQL
5432 is published only on host loopback (`127.0.0.1`) for SSH tunneling;
the app's 8081 port is not published by Docker.

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@138.2.168.194
cd /home/ubuntu/rparadise-tracker
sudo docker compose -f compose.yaml -f compose.observability.yaml ps
sudo docker compose logs --tail=100 app
sudo docker compose exec -T app curl -fsS http://localhost:8081/actuator/health
```

Docker starts on boot; all services use `restart: unless-stopped`. Caddy obtains
and renews the Let's Encrypt certificate, with certificate state in a Docker
volume. The server `.env` has mode 600 and a generated database password. Keep
it on the server; do not replace it when updating the source.

### Local PostgreSQL client through SSH

Run on your computer and leave the terminal open:

```bash
ssh -i ~/.ssh/id_ed25519 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes -N \
  -L 127.0.0.1:15432:127.0.0.1:5432 ubuntu@138.2.168.194
```

In DBeaver, DataGrip or another PostgreSQL client use host `127.0.0.1`, port `15432`,
database `radioparadise`, user `radioparadise`, and the password from the local
`.env.postgres-access` file (Git-ignored, mode 600). PostgreSQL SSL is disabled;
SSH encrypts the connection between your computer and the VM. Disable the client's
built-in SSH tunnel when using this separate terminal tunnel. This connects directly
to production; edits affect live data. Database password authentication is still required.

If using the client's built-in SSH feature instead, use SSH host `138.2.168.194`,
SSH user `ubuntu`, private key `~/.ssh/id_ed25519`, and database host `127.0.0.1`,
database port `5432` as viewed from the VM. No separate terminal tunnel is needed.

Stop the foreground tunnel with Ctrl+C. If a background tunnel already listens on
15432, inspect `lsof -nP -iTCP:15432 -sTCP:LISTEN` and stop only that SSH PID with
`kill PID`. Do not stop every SSH process because Grafana may use another tunnel.

The deployed source was uploaded as an archive; it is not a Git checkout.
To update from the local repository, upload the build inputs and rebuild:

```bash
COPYFILE_DISABLE=1 tar --no-xattrs -czf /tmp/rparadise-deploy.tgz \
  --exclude=node_modules --exclude=dist --exclude=.DS_Store --exclude='*.log' \
  Dockerfile .dockerignore compose.yaml compose.observability.yaml observability Caddyfile mvnw pom.xml .mvn src frontend
ssh -i ~/.ssh/id_ed25519 ubuntu@138.2.168.194 \
  'tar --no-same-owner -xzf - -C /home/ubuntu/rparadise-tracker' \
  < /tmp/rparadise-deploy.tgz
ssh -i ~/.ssh/id_ed25519 ubuntu@138.2.168.194 \
  'cd /home/ubuntu/rparadise-tracker && sudo docker compose -f compose.yaml -f compose.observability.yaml up -d --build --wait --wait-timeout 300'
```

## Data and free-tier limits

Production started with a fresh database; local development records were not
migrated. PostgreSQL stores data in `rparadise-tracker_postgres-data`. Never use
`docker compose down -v` unless you intend to delete that data.

Download a backup to your computer with:

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@138.2.168.194 \
  'cd /home/ubuntu/rparadise-tracker && sudo docker compose exec -T postgres pg_dump -U radioparadise -d radioparadise' \
  > radioparadise-backup.sql
```

Backups are not yet scheduled. Oracle may reclaim idle free VMs; keep backups
outside the VM. The website hostname embeds the ephemeral public IP, so a
replacement IP requires updating `SITE_ADDRESS` in `.env` and recreating Caddy.

## Deployment verification

- Vue production build and all 7 backend tests passed on the ARM64 VM.
- The dashboard loaded publicly over HTTPS with a trusted certificate.
- HTTP redirects to HTTPS; public `/actuator/health` returns 404.
- Internal health reported UP; app and PostgreSQL passed their health checks.
- The API returned stored songs for channels 0, 1, and 2.
- PostgreSQL's persistent volume and restart policy were inspected.

## Observability added on 2026-11-01

Grafana, Prometheus, Loki, Alloy, Alertmanager and host/database/HTTPS exporters now run on the same VM.
The two Compose files must be used together on future deployments to retain resource limits and logging.
See [the Turkish operator guide](observability.md) for Grafana access, logs, investigations and retention.
Grafana is now available publicly over authenticated HTTPS at
https://rparadise.138.2.168.194.sslip.io/grafana/. The API & Upstream and Oracle VM dashboards
extend the existing overview and logs. Set `GRAFANA_ROOT_URL` and `GRAFANA_COOKIE_SECURE=true` in production.
Node exporter listens only on Docker's private host gateway; no additional public ports were opened.
No paid service or cloud capacity was added. Email remains disabled until the Gmail app password is supplied
and an actual SMTP delivery test succeeds.
