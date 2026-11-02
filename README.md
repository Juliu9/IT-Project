# RecommenderAgent

## Purpose

A Spring Boot API for conversational audiobook recommendations using Qdrant or Solr, with Redis
session storage. It owns search, ranking, follow-up state, explanations, and response generation.

## How it works

1. Recognise a command or extract intent and criteria with a chat model.
2. Resolve the conversation using saved preferences, search context, and recommendation pages.
3. Retrieve and rank eligible books when a cached page cannot satisfy the request.
4. Save the session, then return a concise response using stored book descriptions.

Catalogue embeddings, snapshots, summaries, migration, and preference imports run as separate
maintenance jobs. Embedding generation also refreshes the snapshot used by preference imports.
For detailed behaviour, see [Architecture](docs/architecture.md).

## Repository structure

Java sources are under `src/main/java/com/gen3/`:

| Package | Responsibility |
| --- | --- |
| `recommenderagent` | HTTP API, conversation, retrieval, ranking, responses, session/user storage and telemetry |
| `shared.audiobookcatalogue` | Audiobook models, database contracts/adapters and snapshots |
| `shared.vector` | Vector mathematics shared by catalogue, preference and recommendation code |
| `embeddingjob` | Fill missing catalogue vectors (or explicitly rebuild all), then refresh the snapshot |
| `snapshotjob` | Export existing Solr/Qdrant vectors without generating embeddings |
| `shortdescriptionjob` | Generate missing summaries through regular API calls, or explicitly rebuild them |
| `migrationjob` | Copy existing supported data from Solr to Qdrant |
| `userpreferencejob` | Stream CSV favourites/history and aggregate local snapshot vectors |
| `shared.readhistory` | Read/write contracts and replaceable history storage adapters |
| `shared.csv` | Streaming CSV readers, input records and book-ID normalization shared by import jobs |
| `readhistoryjob` | Standalone additive history import for read-book exclusion |
| `shared.identity` | Common validation for caller and imported user IDs |
| `shared.jobruntime` | Periodic progress reporting shared by maintenance jobs |

Configuration and the dev page live in `src/main/resources/`; tests are under `src/test/java/`.
Local services use `compose.yaml`, Solr schemas live in `solr/`, and CI is in `.github/workflows/`.

## Prerequisites

Read-book exclusion is optional: prepare the separate PostgreSQL history database and enable
`READ_HISTORY_ENABLED` to use it. See [read-history setup and import](docs/jobs/read-history.md).
The default local Compose stack does not start PostgreSQL; `compose.history.yaml` is a separate opt-in.

- **[Java 21](https://adoptium.net/temurin/releases/)** and
  **[Docker with Compose](https://docs.docker.com/get-started/)** for the documented local setup and
  container tests. The checked-in `./mvnw` wrapper downloads the pinned Maven version; a separate
  Maven installation is not required.
- [OpenAI API access](https://platform.openai.com/docs/quickstart) and connection settings supplied
  through the environment or optional dev `.env`.
- A populated Solr or Qdrant catalogue with compatible vectors. **Starting Docker does not create
  audiobook data.** See the [job runbooks](docs/jobs/README.md).
- Reachable Redis for conversation sessions. Imported user profiles are optional for non-personalised
  requests; the local vector snapshot is needed for user imports, not the recommendation server.

See [Configuration](docs/configuration.md#local-env-and-terminal-exports) for credentials and connections.

On macOS, select an installed Java 21 for the current terminal before running Maven:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

These exports apply only to that terminal session. The final command should report Java 21.

## Run locally

After meeting the prerequisites, start the local services and recommendation server:

```bash
set -a && source .env && set +a
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Open `http://localhost:8080/`. Submit using the button or Enter; Shift+Enter inserts a new line.
The dev website accepts user/session IDs and displays responses,
resolved requests, timings and token usage. See [demo behaviour](docs/configuration.md#development-website).
For a backend-only run, export configuration through your shell/IDE and use `./mvnw spring-boot:run`.

The standard endpoint returns plain text:

```bash
curl -X POST http://localhost:8080/api/v1/recommendations \
  -H "X-Session-Id: session-123" \
  -H "X-User-Id: user-456" \
  -H "Content-Type: text/plain" \
  -d "Recommend fantasy audiobooks under ten hours."
```

Reuse both IDs for follow-ups such as “more”, “explain”, or “repeat the recommendations”.

## Verification commands

| Purpose | Command |
| --- | --- |
| Local checks, unit and container tests; no live AI | `OPENAI_API_KEY=ci-placeholder ./mvnw clean verify -DexcludedGroups=external` |
| Full Maven verification; external tests remain opt-in | `./mvnw clean verify` |
| Apply Java formatting | `./mvnw spotless:apply` |
| Opt into live parser evaluations | `RUN_LIVE_PARSER_TESTS=true ./mvnw test -Dtest=LiveInputParserTest` |
| Opt into the configured live request path | `RUN_LIVE_RECOMMENDATION_TESTS=true ./mvnw test -Dtest=LiveRecommendationIntegrationTest` |

See [test prerequisites](docs/configuration.md#verification-and-live-test-prerequisites) for Docker,
environment gates, API charges, and CI behaviour.

## Important notes

- Select the database with `AUDIOBOOK_DATABASE`; this does not migrate existing data.
- **Keep the dev profile disabled in production. User/session headers are not authentication.**
- **Maintenance jobs can write data or incur AI charges.** Read the [job runbook](docs/jobs/README.md).
- **Never commit credentials, local logs, snapshots, or generated maintenance data.**

## Documentation

| Guide | Contents |
| --- | --- |
| [Configuration](docs/configuration.md) | `.env`, properties, models, database setup, demo and test prerequisites |
| [Architecture](docs/architecture.md) | Request pipeline, sessions, conversation rules and adapter contracts |
| [Job runbooks](docs/jobs/README.md) | One guide per job, execution order, commands, writes and recovery |
| [Maintenance operations](docs/maintenance.md) | Shared safety rules, progress reporting and launcher verification |
| [Observability](docs/observability.md) | Local timings, AI usage and optional Grafana export |
| [Deployment and future work](docs/deployment-and-future-work.md) | Production safeguards, scaling guidance and suggested improvements |
| [Diagrams](docs/diagrams/README.md) | System overview and execution sequences |
| [Contributing](docs/CONTRIBUTING.md) | Development workflow, checks and documentation ownership |
