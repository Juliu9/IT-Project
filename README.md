# RecommenderAgent

Spring Boot service for audiobook recommendations. It parses a natural-language request, retrieves
and ranks candidates, retains a 24-hour Redis session, and returns a concise spoken-style response.

## How it works

1. OpenAI parses the request into an intent, semantic query, exact filters, and result count.
2. Qdrant retrieves unseen candidates using semantic, keyword, hybrid, or filter-only search.
3. The ranker scores candidates and defensively excludes any book already shown in the session.
4. Redis saves the request and up to 500 recent shown-book IDs under the `(userId, sessionId)` pair.

Every search mode excludes those shown IDs inside Qdrant before selecting up to 50 candidates.
Existing collections in Qdrant strict mode need a keyword payload index on `bookId`; new
collections created by this adapter already include it. The ranker checks exclusions again.
`MORE_RESULTS` reuses the previous search. Without a previous search it asks what the user wants,
without embedding or retrieval. A previous search with no matches still counts. Clear history to
make earlier books eligible again. Books outside the 500-ID window may reappear.

Author-only and narrator-only semantic preferences select semantic retrieval; exact names in
`MustInclude` and `MustNotInclude` remain database filters.

`REPEAT` (for example, “show those again”) returns the most recent recommendation page
from session history without consuming pending results or rerunning retrieval. After clearing
history, there is nothing to repeat. Short “more” pages are allowed; an empty “more” explicitly
says there are no further matches, and “repeat” preserves that empty outcome instead of returning
older books. Response wording is generated normally. Both “more” and
“repeat” supply the saved search criteria to the response generator so it can explain the fit.

Each search ranks up to 20 recommendations from those candidates. The session stores the
unserved recommendations (ID, title, short description, and score), then serves up to five per
request using the existing result-count rules. Plain “more” skips preference lookup, embedding,
retrieval, and ranking while that queue has enough results. Parsing and response generation still
run normally. A short cached tail is combined with a fresh batch when possible; the tail is
excluded from the new retrieval too.

New searches, refinements, preference changes, exact filters supplied with “more”, and clearing
history discard the queue. Changing the result count or asking for help keeps it. Queue consumption
and shown IDs commit together under the existing session lock and share its 24-hour sliding expiry;
failed updates leave the saved queue untouched. There are no Redis-specific keys or database lookup
requirements. Old sessions without a queue refill normally.

Cached details are a snapshot of the catalogue when the batch was ranked. Catalogue edits or
removals take effect for new batches; queued entries are not checked against the database on each
page.

Session changes use one `SessionRepository.update` operation: lock the `(userId, sessionId)`
pair, load or create its session, apply the change once, commit, and release the lock. Redis
checks lock ownership and session version before saving, and renews the lock for long updates.
Busy sessions return 409; rejected commits or lost locks return 503. Updates are not retried
automatically. Lock keys use a separate `lock:` namespace; existing session data keys are unchanged.
When upgrading from the old lock-key scheme, drain and stop old instances and allow their leases
to expire before serving traffic with the new version. Do not mix the two locking versions.
Final response generation happens after commit, so a response failure does not
undo history or shown-book changes.

Redis is the default session adapter (`SESSION_STORE=redis`). To replace it, register an
implementation of `SessionRepository` and set `SESSION_STORE` to its backend name; this disables
the Redis session adapter and Boot's Redis connections, templates, repositories, and health checks.
Request handling and conversation rules do not
need to change. The replacement must preserve cross-instance exclusion, single-attempt commits,
the 24-hour sliding expiry, and the shared session error types. A cache offering only get/set
operations is insufficient without additional coordination.

Supported intents are `RECOMMENDATION`, `REFINE`, `MORE_RESULTS`, `REPEAT`, `CHANGE_COUNT`,
`UPDATE_PREFERENCES`, `CLEAR_HISTORY`, `HELP`, and `UNKNOWN`.

Personalised requests such as “Give me books I would like” use a stored Qdrant user vector when
available. Otherwise, session preferences are used; if none exist, the service asks what the user
likes. `REFINE` and `MORE_RESULTS` reuse the last search.

For known users, retrieval combines one normalized stored-taste vector and the current positive
semantic vector equally, then subtracts the negative semantic vector and normalizes the result.
When a positive source is absent, the other contributes at full strength. Exact `MustInclude` and
`MustNotInclude` constraints remain database filters; they are never relaxed to fill the count.
The final ranker still applies its separately configured scoring weights.

Duration filters support hours/minutes, decimals, mixed units (`1h 30m`), and ranges
(`between 2 and 3 hours`). `under` and `over` are strict; `at most` and `at least` include the boundary.
A bare duration is exact. Bounds account for the catalogue's whole-minute durations. Invalid,
ambiguous, reversed, or unrepresentable ranges return 400 instead of silently losing the filter.

Publication-year filters accept exact four-digit years, strict `after`/`before` comparisons,
inclusive `at least`/`at most` comparisons, and inclusive ranges such as `between 2000 and 2010`.
Unsupported or reversed expressions return 400.

## Run locally

Requirements: Java 21, Docker Compose, and an OpenAI API key.

The local Qdrant image is now 1.19.1. Before reusing a volume created by 1.15.x,
back it up and follow Qdrant's consecutive-minor-version upgrade procedure;
do not point 1.19.1 directly at an old 1.15.x volume. Test containers use fresh storage.

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
| `AUDIOBOOK_DATABASE` | `qdrant` | One database adapter set for search, vectors, preferences, and enrichment |
| `EMBEDDING_DIMENSIONS` | `1536` | Shared embedding-model and vector-store dimension |
| `QDRANT_MIGRATION_ENABLED` | `false` | Run the opt-in Solr-to-Qdrant migration |

Qdrant stores dense and sparse vectors plus filterable metadata. Exact author, narrator, language,
duration, and source constraints are applied as filters. Solr remains for explicit migration and its integration tests; it is not a complete selectable backend.

`solr/vector-field-schema.xml` is a manual snippet for configuring an external Solr collection.
It stays outside `src/` because the application does not load it or bundle it as a runtime
resource. Apply its definitions to the target collection and match the vector dimensions to the
configured embedding model.

## Database selection and replacement

Local Docker and Qdrant integration tests use server 1.19.1 with Java client 1.18.0.
Vector readers use the current dense-vector response format; legacy flattened responses are not supported.


`audiobook.database` in `application.properties` selects one database for the whole request flow;
`qdrant` is the default. The former `audiobook.candidate-retriever` setting is rejected to prevent
mixed backends. The recommendation server still rejects `solr` until its missing vector lookup
and user-preference adapters are implemented; both maintenance jobs can select Solr independently.

To add another database, register its implementations of `AudiobookCandidateSearch`,
`AudiobookVectorRepository` and `UserPreferenceVectorRepository`
under the same selection setting. Selecting it disables all Qdrant clients and adapters. Missing
implementations fail startup; they never fall back to Qdrant. Preserve semantic, keyword, hybrid,
and filtered search and the existing write guarantees; report unsupported operations explicitly.
Conversation and ranking code do not change. A change of selection does not copy any data.

Qdrant field names, index types, vector schema, and filter translation live in
`QdrantAudiobookSchema`, inside the adapter. Reads still require an existing collection without
creating indexes; explicit indexing creates an absent collection. Existing collections are not
automatically inspected or migrated.

Qdrant catalogue lookups match the payload's `bookId`, independently of whether the point ID
is an integer or a UUID. Existing points retain their original IDs during explicit writes;
migration skips existing books. New books keep the deterministic UUID convention.
A generated ID already occupied by another record is rejected instead of overwritten.

Each `bookId` must identify one point in the selected collection. Lookup, migration, and
explicit writes reject multiple matches for a requested book ID; writes also reject duplicate
IDs within an input batch. No duplicate is selected, merged, or deleted automatically.
Matching reads are paginated in groups of at most 100. Keep the `bookId` keyword index on
existing strict-mode collections. Maintenance writes still require a stable catalogue and one
writer; checks do not enforce uniqueness against concurrent external writers.

## Catalogue embedding maintenance

All catalogue indexing orchestration, book-to-vector conversion, and job-specific database adapters
live under `com.gen3.embeddingjob`. Search-query embedding remains in the recommendation service;
shared catalogue records and general database persistence stay in the storage packages.

Book-vector generation is disabled by default and runs only in a separate, non-web process.
Choose the database containing the existing records with `AUDIOBOOK_DATABASE=solr` or `qdrant`.
The same selected adapter reads the records and updates their vectors; neither migration nor
normal request handling starts this job. Query embeddings used by normal searches are unchanged.

For example, index up to 100 existing Solr records:

```bash
AUDIOBOOK_EMBEDDING_INDEX_ENABLED=true AUDIOBOOK_DATABASE=solr \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.embeddingjob.EmbeddingIndexApplication \
  -Dspring-boot.run.arguments="--audiobook.embedding-index.maximum-records=100"
```

Replace `solr` with `qdrant` to index the records already in Qdrant. Only the selected database
client is loaded; the job does not need Redis or the recommendation backend's user-preference
adapters. OpenAI credentials and the selected database connection settings are still required.
The Solr collection must have the configured stored vector field, or Qdrant must have the
configured named-vector schema, matching `embedding.dimensions`.

`audiobook.embedding-index.page-size` defaults to 100. `maximum-records=0` processes the full
catalogue. This explicitly regenerates existing dense vectors, incurs embedding-provider cost,
and also builds Qdrant keyword vectors. Updates preserve record IDs, metadata, custom fields,
and short descriptions. Blank records are skipped. Failed pages are not written if embedding
fails; earlier completed pages remain saved. Rerunning starts at the beginning, so use a stable
catalogue and run one indexing job at a time. The process closes its clients and exits when done.

The former `solr.embedding-index-on-startup` and `solr.embedding-index-page-size` settings are
replaced by this database-independent job. Enabling the new flag on the normal server is rejected;
use the dedicated entry point. Migration and indexing cannot run together. Another database can
implement `com.gen3.embeddingjob.store.AudiobookEmbeddingStore` and register its adapter for the selected database.

Solr-to-Qdrant migration is the sole two-database exception and requires a dedicated non-web run.
It copies the supported catalogue fields, existing short descriptions, and the configured stored
Solr dense-vector field. It never calls an embedding or summary model, normalizes existing vectors,
or generates sparse keyword vectors. Records without vectors are copied with no vectors; they
need a separate opt-in indexing step before vector search can find them. Existing destination
records are skipped so reruns preserve their summaries and vectors. The record limit counts source
records read, including skipped destination records. Source vector dimensions must match Qdrant.
The embedding job above indexes these copied records. This does not complete the selectable
Solr recommendation backend.

Run against a stable source catalogue and avoid concurrent writes to the destination during migration:


```bash
QDRANT_MIGRATION_ENABLED=true ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.migrationjob.MigrationApplication \
  -Dspring-boot.run.arguments="--audiobook.database=qdrant"
```

## Chat models

The parser and response generator have separate clients. Set `INPUT_PARSER_CHAT_MODEL` and
`RESPONSE_GENERATOR_CHAT_MODEL` independently (properties `ai.input-parser.model` and
`ai.response-generator.model`). A blank value inherits the configured provider's default;
OpenAI's default is `gpt-4o-mini`, overridable with `OPENAI_CHAT_MODEL`.
To use different providers, register a Spring AI `ChatClient` bean named `inputParserChatClient`
or `responseGeneratorChatClient`; each replacement affects only its role. Model names must be
supported by that client's provider. Provider failures and missing or blank response text use the
built-in recommendation response; the already committed session is preserved. These failures
are recorded in local logs.

## Short book descriptions

Recommendations use a stored, one-sentence `short_description` when it is available. Generate
these descriptions offline through a `ShortDescriptionBatchProvider`; OpenAI Batch is the default
adapter. Normal requests never call AI for a summary.
The database-independent job reads `description` through the selected `AudiobookEnrichmentStore`,
generates `shortDescription`, and uploads through that same adapter without re-embedding books.
The adapter maps those domain fields to its database names (Qdrant uses `short_description`) and
preserves existing short descriptions.

All short-description orchestration, provider transport, and summary-specific database adapters live
under `com.gen3.shortdescriptionjob`. The normal server never loads this job, even when explicitly
configured: it rejects enabled summary modes and directs you to the maintenance entry point.
Choose `AUDIOBOOK_DATABASE=solr` or `qdrant`; only that database is connected. This job uses neither
Redis nor embedding generation and does not require a complete recommendation backend.

Start with a small submission to check quality and spend:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=submit \
AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MAXIMUM_RECORDS=1000 \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

Solr requires a stored, single-valued `short_description` field (for example, type `string`) and
the standard `_version_` field. It updates only `short_description`, checks `_version_` to reject concurrent changes,
and preserves existing summaries. On a conflict, the manifest is retained; retry import to apply
remaining results. Qdrant retains its conditional missing-only payload updates. Neither adapter
rewrites book vectors. Run one submission/import process at a time and retain its output directory.

The job records the Batch ID, provider, backend/dataset identity, and opaque record references in
`data/short-descriptions/short-description-batch.json`. Once OpenAI reports it as completed,
import its output using the same backend, endpoint, and collection. A mismatched provider or dataset, or a legacy
manifest without identity is rejected before provider calls or database writes, leaving the manifest
intact. Legacy batches require explicit recovery with their original implementation/configuration;
these manifests are never silently converted. Import the output:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=import \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

For the remaining catalogue, submit up to 100,000 records at a time and repeat after each import;
each submission selects the records still missing `short_description`. A pending manifest blocks
another submission until it is imported. An input-upload failure leaves no pending manifest, so
submission can be retried normally. Once batch submission starts, an unknown outcome retains the
manifest to prevent duplicate generation: reconcile the batch with the provider, then supply
`AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID` to import it. Manifest updates are atomic so a failed
write preserves the previous recovery data. OpenAI batch requests default to `gpt-4o-mini`
(`OPENAI_BATCH_MODEL`), group ten books per request, and ask for a spoiler-free sentence of at most
20 words. The shared job enforces that word limit for every provider. Keep the default mode
(`disabled`) for ordinary application startup.

To replace OpenAI Batch, implement `ShortDescriptionBatchProvider`, register the adapter, and set
`AUDIOBOOK_SHORT_DESCRIPTION_BATCH_PROVIDER` to its name. Its `upload` must only prepare input;
`submit` starts generation, and `completedDescriptions` returns book IDs and descriptions. The
job owns manifests, database references, safe retries, and import validation. Existing manifests
without a provider field belong to OpenAI. The replacement adapter must be conditional on its
configured name; do not switch provider while a batch is pending.

## Grafana Cloud logs

The optional Alloy collector sends only structured `recommendation.completed` events to Grafana
Cloud; it excludes request text and session contents. Nonmatching log lines, malformed JSON,
and summaries with missing, mistyped, or extra top-level fields are dropped before export. The
allowlist follows the ten fields emitted by `RequestTelemetry`; update it if that event schema
changes. Other application logs remain local.

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


## User preference import

CSV import and preference-vector aggregation run separately from the recommendation server.
The existing opt-in flag and CSV path settings are retained. This job uses the selected
database adapters; Qdrant currently provides the complete pair of audiobook-vector and
user-preference adapters. It averages existing book vectors without calling an AI model.

```bash
USER_PREFERENCES_IMPORT_ON_STARTUP=true ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.favourites-csv-path=/path/favourites.csv --user.preferences.history-csv-path=/path/history.csv"
```

Run maintenance jobs individually. The normal server rejects their enabled flags.

Only `RECOMMENDATION` and `REFINE` accept newly parsed positive/negative semantic queries
or the personalised flag. Other intents discard these fields. `MORE_RESULTS` and `REPEAT`
inherit the saved search context; semantic changes use `REFINE`. `UPDATE_PREFERENCES`
can still update exact filters. A semantic reply to a taste-clarification question uses `REFINE`.

Solr summary imports read current IDs, summaries, and versions in batches of at most 500,
then apply bounded, version-checked updates. A failed later batch retains the manifest;
retrying preserves summaries already written.

## Source layout

The recommendation server is under `com.gen3.recommenderagent`:

| Package | Responsibility |
| --- | --- |
| `api` | HTTP requests and error responses |
| `application` | Session transitions, recommendation workflow and chat-client wiring |
| `domain` | Intents, recommendation policy and conversational session models |
| `inputparser` / `response` | Structured request parsing and response wording |
| `embedding` | QueryEmbeddingService, positive/negative query vectors and shared vector math |
| `retrieval` / `retrieval.plan` | Candidate search execution, retrieval plans and filter parsing |
| `ranker` / `ranker.scorer` | Candidate ranking and scoring rules |
| `storage.audiobook` / `storage.user` | Storage contracts, models and database adapters |
| `storage.session` | Session contract, commit errors and Redis adapter |
| `observability` | Request timing and AI usage telemetry |

The sibling maintenance packages remain separate runnable jobs. CSV interaction records
(`UserFavourite` and `UserHistory`) belong to `userpreferencejob.model`.
Catalogue embedding generation remains in `embeddingjob`; the query embedding module
only embeds live search criteria. `RecommendationPolicy` owns the existing limits
and result-count clamping rules.


## Conversation policy

`SessionTransitionService.process` remains the entry point for a single session transition.
`ConversationState` owns semantic eligibility, context inheritance, pending-batch invalidation,
preference/count updates, history retention and the resolved conversation outcome.
The transition implementation retains cached paging, retrieval and timing inside one
`SessionRepository.update`; response generation still follows a successful commit.

`ConversationOutcome` is temporary and excluded from session JSON. Existing session fields
and recommendation details remain unchanged, so no Redis migration is required.
The response implementation consumes this outcome for wording. Direct callers without a
resolved outcome use the same conversation-policy classifier; there is no second intent policy.

Control intents still discard new semantic criteria while retaining exact filters. MORE with
new exact filters invalidates its pending batch, and semantic taste replies use REFINE.
This refactor preserves the existing clarification, history-retention and response-precedence rules.


The parser interface returns `SessionRequest` directly. Provider responses and token usage stay
inside the parser implementation; the request pipeline does not depend on Spring AI response types.
