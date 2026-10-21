# RecommenderAgent

Spring Boot service for audiobook recommendations. It parses a natural-language request, retrieves
and ranks candidates, retains a 24-hour Redis session, and returns a concise spoken-style response.

## How it works

1. OpenAI parses the request into an intent, semantic query, exact filters, and result count.
2. Qdrant retrieves unseen candidates using semantic, keyword, hybrid, or filter-only search.
3. The ranker scores candidates and defensively excludes any book already shown in the session.
4. Redis saves the request and up to 500 recent shown-book IDs under the `(userId, sessionId)` pair.

Session changes use one `SessionRepository.update` operation: lock the `(userId, sessionId)`
pair, load or create its session, apply the change once, commit, and release the lock. Redis
checks lock ownership and session version before saving, and renews the lock for long updates.
Busy sessions return 409; rejected commits or lost locks return 503. Updates are not retried
automatically. Final response generation happens after commit, so a response failure does not
undo history or shown-book changes.

Redis is the default session adapter (`SESSION_STORE=redis`). To replace it, register an
implementation of `SessionRepository` and set `SESSION_STORE` to its backend name; this disables
the Redis session adapter and its configuration. Request handling and conversation rules do not
need to change. The replacement must preserve cross-instance exclusion, single-attempt commits,
the 24-hour sliding expiry, and the shared session error types. A cache offering only get/set
operations is insufficient without additional coordination.

Supported intents are `RECOMMENDATION`, `REFINE`, `MORE_RESULTS`, `CHANGE_COUNT`,
`UPDATE_PREFERENCES`, `CLEAR_HISTORY`, `HELP`, and `UNKNOWN`.

Personalised requests such as “Give me books I would like” use a stored Qdrant user vector when
available. Otherwise, session preferences are used; if none exist, the service asks what the user
likes. `REFINE` and `MORE_RESULTS` reuse the last search.

## Run locally

Requirements: Java 21, Docker Compose, and an OpenAI API key.

```bash
docker compose up -d
export OPENAI_API_KEY="your-key"
./mvnw spring-boot:run
```

Make a request:

```bash
curl -X POST http://localhost:8080/api/v1/recommendations \
  -H "X-Session-Id: session-123" \
  -H "X-User-Id: user-456" \
  -H "Content-Type: text/plain" \
  -d "Recommend a dark fantasy audiobook under ten hours, but no romance."
```

## Configuration

Only `OPENAI_API_KEY` is required. The defaults suit local Docker:

| Variable | Default | Purpose |
| --- | --- | --- |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis session store |
| `QDRANT_URL` | `http://localhost:6333` | Qdrant endpoint |
| `QDRANT_GRPC_PORT` | `6334` | Qdrant gRPC port |
| `QDRANT_API_KEY` | empty | Qdrant Cloud API key |
| `QDRANT_COLLECTION` | `audiobooks_hybrid` | Audiobook collection |
| `QDRANT_TIMEOUT` | `10s` | Maximum time for one Qdrant call |
| `AUDIOBOOK_CANDIDATE_RETRIEVER` | `qdrant` | Set to `solr` for the legacy adapter |
| `QDRANT_MIGRATION_ENABLED` | `false` | Run the opt-in Solr-to-Qdrant migration |

Qdrant stores dense and sparse vectors plus filterable metadata. Exact author, narrator, language,
duration, and source constraints are applied as filters. Solr remains only for legacy retrieval,
migration, and its integration tests.

`solr/vector-field-schema.xml` is a manual snippet for configuring an external Solr collection.
It stays outside `src/` because the application does not load it or bundle it as a runtime
resource. Apply its definitions to the target collection and match the vector dimensions to the
configured embedding model.

## Short book descriptions

Recommendations use a stored, one-sentence `short_description` when it is available. Generate
these descriptions offline with OpenAI's Batch API; normal requests never call AI for a summary.
The job reads and updates Qdrant directly, without re-embedding books.

Start with a small submission to check quality and spend:

```bash
export AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=submit
export AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MAXIMUM_RECORDS=1000
./mvnw spring-boot:run
```

The job records the Batch ID and exact Qdrant point IDs in
`data/short-descriptions/short-description-batch.json`. Once OpenAI reports it as completed,
import its output:

```bash
export AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=import
./mvnw spring-boot:run
```

For the remaining catalogue, submit up to 100,000 records at a time and repeat after each import;
each submission selects the records still missing `short_description`. A pending manifest blocks
another submission until it is imported. Batch requests use
`gpt-4o-mini`, group ten books per request, and ask for a spoiler-free sentence of at most 20
words. Keep the default mode (`disabled`) for ordinary application startup.

## Fill missing catalogue metadata

To add deterministic placeholder narrator, language, duration, and publication-year values to the
Qdrant catalogue—without creating short descriptions or calling OpenAI—run:

```bash
export AUDIOBOOK_FAKE_METADATA_ENRICH_ON_STARTUP=true
./mvnw spring-boot:run
```

The job scans the entire Qdrant collection in pages and only fills fields that are missing.
Existing values are preserved, so it is safe to rerun. It runs only while the
short-description batch mode is `disabled`. Normal requests only read the stored Qdrant payload,
so these mock values are treated like ordinary catalogue data after enrichment. Unset the variable
for normal application startup.

## Grafana Cloud logs

The optional Alloy collector sends only structured `recommendation.completed` events to Grafana
Cloud; it excludes request text and session contents.

1. Create a Grafana Cloud access-policy token with `logs:write`, then create `.env` in the repo:

   ```dotenv
   GRAFANA_LOKI_URL=https://<loki-host>/loki/api/v1/push
   GRAFANA_LOKI_USER=<loki-user-id>
   GRAFANA_LOKI_TOKEN=<logs-write-token>
   ```

2. Configure the application log file. In IntelliJ, add this VM option; from a terminal, set the
   same value as `LOGGING_FILE_NAME`:

   ```text
   -Dlogging.file.name=/absolute/path/to/IT-Project/logs/recommender-agent.log
   ```

3. Start the collector and inspect it if needed:

   ```bash
   mkdir -p logs && touch logs/recommender-agent.log
   docker compose -f compose.yaml -f compose.observability.yaml up -d
   docker compose -f compose.yaml -f compose.observability.yaml logs -f alloy
   ```

4. In Grafana **Explore**, select the Loki source and run:

   ```logql
   {service="recommender-agent", environment="local"}
   ```

   Add `outcome="FAILURE"` to see only failed requests. Alloy retains its file position and sends
   events written while it was stopped once it resumes.

## Tests

Run all tests with Docker available:

```bash
./mvnw clean test
```

The suite includes unit tests plus Redis and Solr container-backed integration tests. Never commit
API keys, `.env`, or local logs.

Changes targeting `staging` run the API-free test suite. Changes targeting `main` run the tagged
integration checks. Configure the repository secrets `OPENAI_API_KEY`, `QDRANT_URL`, and
`QDRANT_API_KEY`; only the `main` checks receive them.
