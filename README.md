# RecommenderAgent

A Spring Boot backend for conversational audiobook recommendations. It supports new searches,
refinements, more results, explanations, and replay using Qdrant or Solr, with Redis session storage.

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
| `embeddingjob` | Generate catalogue vectors, then refresh the local snapshot |
| `snapshotjob` | Export existing Solr/Qdrant vectors without generating embeddings |
| `shortdescriptionjob` | Submit, import and recover generated summaries |
| `migrationjob` | Copy existing supported data from Solr to Qdrant |
| `userpreferencejob` | Stream CSV favourites/history and aggregate local snapshot vectors |
| `jobruntime` | Shared periodic job-progress reporting |

Configuration and the dev website live in `src/main/resources/`; Java tests
are under `src/test/java/`. `compose.yaml` provides local services, `solr/` contains external schema
snippets, and `.github/workflows/ci.yml` defines CI. Optional log collection uses
`compose.observability.yaml` and `observability/`.

## Run locally

Requires **Java 21**, Docker, and OpenAI credentials. Prepare a populated catalogue with the
expected schema; starting Docker does not create audiobook data.

Set up the root `.env` using [Configuration](docs/configuration.md#local-env-and-terminal-exports), then:

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

See [test prerequisites](docs/configuration.md#verification-and-live-test-prerequisites) for Docker,
environment gates, API charges, and CI behaviour.

## Important notes

- Select the database with `AUDIOBOOK_DATABASE`; this does not migrate existing data.
- Keep the dev profile disabled in production. User/session headers are not authentication.
- Maintenance jobs can write data: inspect the [job runbook](docs/jobs/README.md) first. Snapshot
  export is database-read-only; user import defaults to dry-run. Retain recovery manifests.
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
