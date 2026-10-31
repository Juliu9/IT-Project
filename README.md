# RecommenderAgent

## Purpose

A Spring Boot backend for conversational audiobook recommendations. It supports new searches,
refinements, more results, explanations, and replay using Qdrant or Solr, with Redis session storage.
It gives callers one conversational API instead of requiring them to coordinate search filters,
ranking, follow-up state, and response generation themselves.

## How it works

1. Recognise a command or extract intent and criteria with a chat model.
2. Resolve the conversation using saved preferences, search context, and recommendation pages.
3. Retrieve and rank eligible books when a cached page cannot satisfy the request.
4. Save the session, then return a concise response using stored book descriptions.

Catalogue embeddings, snapshot exports, short descriptions, migration, and preference imports use
dedicated maintenance launchers. Embedding generation also refreshes the local snapshot;
daily preference imports stream CSV records against that snapshot.
For detailed behaviour, see [Architecture](docs/architecture.md).

## Repository structure

Java sources are under `src/main/java/com/gen3/`:

| Package | Responsibility |
| --- | --- |
| `recommenderagent` | HTTP API, conversation, retrieval, ranking, responses, session/user storage and telemetry |
| `catalogue` | Shared audiobook models, database contracts/adapters and vector utilities |
| `embeddingjob` | Fill missing catalogue vectors (or explicitly rebuild all), then refresh the snapshot |
| `snapshotjob` | Export existing Solr/Qdrant vectors without generating embeddings |
| `shortdescriptionjob` | Generate missing summaries through regular API calls, or explicitly rebuild them |
| `migrationjob` | Copy existing supported data from Solr to Qdrant |
| `userpreferencejob` | Stream CSV favourites/history and aggregate local snapshot vectors |
| `shared.identity` | Common validation for caller and imported user IDs |
| `shared.jobruntime` | Periodic progress reporting shared by maintenance jobs |

Configuration and the dev website live in `src/main/resources/`; Java tests
are under `src/test/java/`. `compose.yaml` provides local services, `solr/` contains external schema
snippets, and `.github/workflows/ci.yml` defines CI. Optional log collection uses
`compose.observability.yaml` and `observability/`.
The one-off Qdrant payload rename tool and its tests live under
`scripts/qdrant-payload-migration/`; see the [schema transition guide](docs/qdrant-payload-migration.md).

## Prerequisites

- **Java 21** and Docker for the documented local setup and container tests.
- OpenAI credentials and connection settings supplied through the environment or optional dev `.env`.
- A populated Solr or Qdrant catalogue with the expected schema and compatible book vectors;
  starting Docker does not create audiobook data. See the [job runbooks](docs/jobs/README.md) to prepare it.
- Reachable Redis for conversation sessions. Imported user profiles are optional for non-personalised
  requests; the local vector snapshot is needed for user imports, not the recommendation server.

See [Configuration](docs/configuration.md#local-env-and-terminal-exports) for credentials and connections.

## Run locally

After meeting the prerequisites, start the local services and recommendation server:

```bash
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
| Full Maven verification, including eligible live tests | `./mvnw clean verify` |
| Apply Java formatting | `./mvnw spotless:apply` |
| Opt into live parser evaluations | `RUN_LIVE_PARSER_TESTS=true ./mvnw test -Dtest=LiveInputParserTest` |
| One-off payload tool tests; no remote writes | `python3 -m unittest discover -s scripts/qdrant-payload-migration -v` |

See [test prerequisites](docs/configuration.md#verification-and-live-test-prerequisites) for Docker,
environment gates, API charges, and CI behaviour.

## Important notes

- Select the database with `AUDIOBOOK_DATABASE`; this does not migrate existing data.
- Keep the dev profile disabled in production. User/session headers are not authentication.
- Maintenance jobs can write data: inspect the [job runbook](docs/jobs/README.md) first. Snapshot
  export is database-read-only; user import defaults to dry-run.
- Keep credentials, local logs, and generated maintenance data out of Git.

## Documentation

| Guide | Contents |
| --- | --- |
| [Configuration](docs/configuration.md) | `.env`, properties, models, database setup, demo and test prerequisites |
| [Architecture](docs/architecture.md) | Request pipeline, sessions, conversation rules and adapter contracts |
| [Job runbooks](docs/jobs/README.md) | One guide per job, execution order, commands, writes and recovery |
| [Maintenance operations](docs/maintenance.md) | Shared safety rules, progress reporting and launcher verification |
| [Observability](docs/observability.md) | Local timings, AI usage and optional Grafana export |
| [Diagrams](docs/diagrams/README.md) | System overview and execution sequences |
| [Contributing](docs/CONTRIBUTING.md) | Development workflow, checks and documentation ownership |
