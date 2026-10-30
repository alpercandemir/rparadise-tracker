# Docker Stack Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build and start Vue, Spring Boot, PostgreSQL, and Caddy with Docker Compose.

**Architecture:** A multi-stage image builds Vue and packages its static files inside Spring Boot. Caddy serves the app through a single origin; PostgreSQL and the application have no published ports in the default stack.

**Tech Stack:** Node 24, Java 25, Maven wrapper, PostgreSQL 17, Caddy 2, Docker Compose.

**Spec:** The user approved the proposed application image plus PostgreSQL/Caddy Compose stack and authorized committing and pushing the application changes.

## Global Constraints

- Retain PostgreSQL and its existing named data volume.
- Retain 30-second collection and five-minute dashboard refresh.
- Reuse the current checkout; preserve unrelated staged deletions.
- Default HTTP entrypoint: http://localhost:8080, bound to loopback.
- Domain deployment: configurable public bindings on ports 80/443 with automatic HTTPS.
- Run the application as a non-root user; expose only health through Actuator internally.

## Review Focus

- Vue assets must actually be present and served in the resulting application image.
- The app must wait for PostgreSQL, and Caddy must wait for application health.
- Database records must survive application/container restarts.
- Environment files and generated artifacts must stay out of build context and commits.
- Local verification must not interrupt the running development stack.

## Task 1: Build and orchestrate the application

- [x] Add a multi-stage Dockerfile and .dockerignore.
- [x] Add internal database-aware health through Spring Boot Actuator.
- [x] Extend compose.yaml with app and Caddy, restart policies, dependency health checks, and persistent volumes.
- [x] Add Caddyfile, .env.example, and compose.dev.yaml for optional database access from a host development process.
- [x] Document local startup, environment configuration, server deployment, health checks, and data retention.

## Task 2: Verify and deliver

- [x] Build the Docker image; Maven verification and Vue compilation must pass inside the build.
- [x] Start an isolated Compose project on port 18080 and confirm all services are healthy.
- [x] Verify HTML/assets, API routes, channel filtering, internal health, external health blocking, and live PostgreSQL writes.
- [x] Restart the app and compare persisted song IDs and metadata.
- [x] Review changes independently and resolve material findings.
- [x] Stop the isolated stack.

Delivery: commit only the application changes and push the current branch without force after verification.

## Verification record

- Vue production build and all 7 Java tests passed inside the Docker build.
- Live smoke passed for dashboard HTML/assets, API ordering/filtering/errors, three-channel collection, and public Actuator blocking.
- PostgreSQL and app were healthy; Java ran as UID 10001 and used +0300.
- All 5 original records retained IDs, metadata, and first-played timestamps after both application and PostgreSQL containers were recreated.
- Independent review identified the UTC container default as incompatible with existing naive timestamps. Added configurable APP_TIMEZONE with Europe/Istanbul default and verified it inside the container. No other actionable findings.
- Domain configuration adapted to HTTPS port 443. Actual certificate issuance requires a real server/domain and was not exercised locally.
