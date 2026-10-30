# Radio Paradise Tracker

A Java 25 + Spring Boot application that continuously tracks currently playing songs on Radio Paradise channels and exposes the data through a REST API.

## Features

- Polls Radio Paradise API on a fixed interval (every 30 seconds by default)
- Stores songs in a persistent PostgreSQL database
- Tracks per-song play counts and last played timestamps
- Exposes browsing endpoints for all, recent, most-played, and channel-filtered songs
- Includes a Vue.js dashboard frontend with charts and channel-based views

## Architecture

- **Backend:** Spring Boot (`src/main/java`)
  - Scheduled polling with `SongTrackerScheduler`
  - External API integration with `RadioParadiseClient`
  - Persistence via Spring Data JPA + PostgreSQL
- **Frontend:** Vue 3 + Vite (`frontend`)
  - Live dashboard cards for each channel
  - Most-played and distribution charts
  - Filterable song list

## Quick Start

### Full Docker stack

Install Docker with the Compose plugin, then run:

```bash
docker compose up -d --build --wait
```

Open [http://localhost:8080](http://localhost:8080). The dashboard and `/api` share this address. No local Java, Maven, or Node installation is required: the image builds Vue, runs the backend tests, and packages the frontend into Spring Boot.

The stack contains:

- `app`: Java 25 / Spring Boot, running as a non-root user with a database-aware health check.
- `postgres`: PostgreSQL 17 with persistent data in the `postgres-data` volume.
- `caddy`: HTTP entrypoint and automatic HTTPS when configured with a public domain. Certificates persist in `caddy-data`.

Only Caddy publishes ports. PostgreSQL and Spring Boot are accessed over the internal Docker network. Defaults bind the website to localhost. Configuration overrides can be placed in a local `.env` file, starting from `.env.example`.

`APP_TIMEZONE` defaults to `Europe/Istanbul`, matching the current development data. Song timestamps do not carry a timezone: keep the same application timezone when reusing a database and when alternating Docker and host development. For host development, set `TZ` to the same value before starting Maven.

```bash
docker compose ps
docker compose logs --tail=100 app
docker compose exec app curl -fsS http://localhost:8081/actuator/health
docker compose down
```

`docker compose down` retains the database and certificate volumes. **Adding `-v` deletes the stored data.** Do not run a second backend against the same database: each backend instance runs its own song collector.

### Development without application containers

To run Maven and Vite directly, start only PostgreSQL with the optional host port mapping. Stop a running containerized backend first to avoid duplicate collectors:

```bash
docker compose stop app caddy
```

### 1) Start PostgreSQL

With Docker installed and running:

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d --wait postgres
```

This starts PostgreSQL 17 on `localhost:5432` with database, username, and password all set to `radioparadise` by default for local development. Data survives container and application restarts. If you configured custom credentials in `.env`, export the same values before starting Maven; Spring Boot does not automatically load Docker Compose's `.env` file.

### 2) Start the backend

```bash
./mvnw spring-boot:run
```

Backend runs on `http://localhost:8081`.

### 3) Start the frontend (optional dashboard)

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

Frontend runs on `http://localhost:3000`.

## Deploy to a server

Use a Linux VM with Docker/Compose and a domain whose DNS records point to that VM. A 4 GB RAM VM is a reasonable starting point for this stack. Clone this repository onto the server and copy `.env.example` to `.env`. Set:

```dotenv
DB_USERNAME=radioparadise
DB_PASSWORD=replace-with-a-long-random-password
APP_TIMEZONE=Europe/Istanbul
SITE_ADDRESS=radio.example.com
BIND_ADDRESS=0.0.0.0
HTTP_PORT=80
HTTPS_PORT=443
```

Use your actual domain and a unique password before the database's first startup. Allow incoming TCP ports 80 and 443 through the server/provider firewall, and restrict SSH access. Keep database and application ports unpublished. Then start the stack:

```bash
docker compose up -d --build --wait
```

Caddy obtains and renews the domain's HTTPS certificate automatically. For an update, pull the latest code and run the same Compose command. For database backups, schedule `pg_dump` and copy the output off the VM; Docker volumes alone are not backups.

The image build runs all backend tests. Internal health is available through `docker compose exec app curl -fsS http://localhost:8081/actuator/health`; Caddy returns 404 for public `/actuator/*` requests. Health checks report failures, while `restart: unless-stopped` restarts containers when their processes exit.

Known tracking limitation: the collector remembers the last song per channel in memory. Restarting the backend can count a currently playing song again; Dockerization does not change that behavior.

## API Endpoints

- `GET /api/songs` - List all tracked songs
- `GET /api/songs/recent` - List latest 20 songs by `lastPlayed`
- `GET /api/songs/most-played` - List top 20 songs by `playCount`
- `GET /api/songs/channel/{channelId}` - Filter songs by channel (`0`, `1`, `2`)

### Channel IDs

- `0` -> Main Mix
- `1` -> Rock Mix
- `2` -> Mellow Mix

## Configuration

All core settings are in `src/main/resources/application.properties`:

- `server.port=8081`
- `radioparadise.api.base-url=https://api.radioparadise.com`
- `radioparadise.api.channels=0,1,2`
- `radioparadise.scheduler.fixed-delay=30000`

## Database Configuration

The backend uses these environment variables, with defaults matching the local Docker setup:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/radioparadise` |
| `DB_USERNAME` | `radioparadise` |
| `DB_PASSWORD` | `radioparadise` |

To use an existing PostgreSQL server, create the database and a user with permission to create and update tables, then export these variables before starting the backend. Use your own credentials outside local development. Docker Compose also reads `DB_USERNAME` and `DB_PASSWORD`; changing them after the database volume has been initialized does not update the existing database credentials.

Hibernate creates or updates the schema on startup (`spring.jpa.hibernate.ddl-auto=update`) and retains tables on shutdown. The previous H2 database was in memory, so it has no on-disk data to migrate. Switching a running instance does not transfer its in-memory songs.

For local database inspection:

```bash
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

## Screenshots

### Dashboard Overview

![Dashboard Overview](docs/screenshots/dashboard-overview.png?v=20260417)

### Dashboard Mobile View

![Dashboard Mobile](docs/screenshots/dashboard-mobile.png)

## Tech Stack

- Java 25
- Spring Boot
- Spring Data JPA
- PostgreSQL
- Lombok
- Maven
- Vue 3
- Vite
- Chart.js
