# Configuration reference

[README](../README.md) · [Architecture](architecture.md) · [Job runbooks](jobs/README.md)

[`application.properties`](../src/main/resources/application.properties) is the source for shared
rules, backend selection, provider connections, and job defaults. This guide documents project
settings—not every Spring Boot property.

## Loading and overriding settings

Precedence is **application arguments → environment variables → packaged defaults**. For example,
`--server.port=8081` wins over `SERVER_PORT` and the checked-in default. `${NAME:value}` supplies a
fallback; an empty fallback means optional, not a generated credential.

| Source | Purpose |
| --- | --- |
| `application.properties` | Checked-in defaults for the server and dedicated jobs |
| `application-dev.properties` | Enables an optional import of `./.env` when the `dev` profile is active |
| Root `.env` | Local credentials and overrides; ignored by Git and resolved from the working directory |
| Shell or IDE environment | Supplies configuration without the dev profile; also visible to Maven and environment-gated tests |
| Application arguments | Overrides for one run, supplied through `-Dspring-boot.run.arguments="--property=value"` |

The dev import treats `.env` as Java properties: use `KEY=value`, without `export` or surrounding
quotes. It does **not** export environment variables to Maven or tests. No `.env.dev` or
`.env.production` is loaded automatically. Outside the dev profile, `.env` is not imported.
Docker Compose reads the root `.env` independently for substitution; this does not configure Java.

### Local `.env` and terminal exports

Use one optional `.env` file in the repository root, beside `pom.xml`. For local Redis and a
populated local Qdrant catalogue, the minimum is:

```properties
OPENAI_API_KEY=your-key
```

Create credentials by following the [OpenAI API quickstart](https://platform.openai.com/docs/quickstart).

For remote services, add the connection/authentication values from [Technology connections](#technology-connections).
Grafana credentials are only needed for [optional log shipping](observability.md#setup).
Environment variables override imported `.env` values. The checked-in dev properties file contains
no secrets; missing `.env` files are allowed.

To export a trusted, shell-compatible `.env` to Maven and environment-gated tests, run from the
repository directory:

```bash
set -a
source .env
set +a
```

Values remain exported in that terminal. `source` executes shell commands, so only use trusted files.
Keep credentials out of Git, logs, and command-line arguments; `.env` is ignored by Git.

### Development website

Enable the `dev` profile to serve the local page at `/` and the diagnostic endpoint at
`POST /api/v1/recommendations/demo`. Without that profile, the server exposes only the normal API.
Keep `dev` disabled in production. See [Run locally](../README.md#run-locally).

| Page feature | Behavior |
| --- | --- |
| User and Session IDs | Generated for the tab, editable, and retained across reloads |
| Conversation | Reuse both IDs for follow-ups; change the Session ID to start separately |
| Imported preferences | Enter an imported User ID; generated IDs have no stored profile |
| Diagnostics | Shows the resolved request, stage timings, and available AI usage |
| Recommendation details | Shows rank, score, and stored catalogue metadata in a collapsed section |

The details lookup reads the selected Solr or Qdrant catalogue by book ID. It does not load vectors,
change the session, or affect the displayed pipeline timings. Missing metadata is shown as unavailable;
a lookup failure does not discard the recommendation response. The demo does not estimate cost.

Press Enter or use the button to submit; Shift+Enter adds a line. Only one request can be pending.

User and session IDs identify conversation state; they are **not authentication**. Both endpoints
require nonblank `X-User-Id` and `X-Session-Id` values of at most 256 Java characters. Invalid values
return `400` before parsing or session access. Add a verified identity boundary before exposing the
service to untrusted clients. Binding local services to `127.0.0.1` is not authentication.

### Verification and live-test prerequisites

Run commands from [README: Verification commands](../README.md#verification-commands).

| Suite | Requirements | External effects |
| --- | --- | --- |
| Local verification | Java 21; Docker running for container tests | Uses disposable containers; no live AI |
| Live recommendation | `RUN_LIVE_RECOMMENDATION_TESTS=true`; credentials and populated services required by the configured AI, catalogue, session and history adapters | Calls configured live services; may write session data and incur charges |
| Live parser evaluation | `RUN_LIVE_PARSER_TESTS=true` and credentials required by the configured chat provider | Calls the configured parser model; may incur charges |

The live-test variables and adapter credentials must be exported to the process. Loading `.env`
through the `dev` Spring profile does not expose them to Maven. External suites are skipped unless
their explicit `RUN_LIVE_*` gate is `true`; once enabled, missing credentials are test failures rather
than silent skips.

The recommendation test must return saved books and their titles; a nonblank fallback is not enough.
Parser evaluations use synthetic text and require neither a catalogue nor Redis. Mocked tests cover
mapping and prompt contracts; live cases measure actual model recognition.

Tests follow the replaceable boundaries described in [Contributing](CONTRIBUTING.md#tests). Core tests
mock ports and Spring AI interfaces; PostgreSQL, Redis, Solr, Qdrant and provider-specific tests verify
only their adapters. A new technology must add an adapter suite that exercises the same port contract.

CI runs local checks for pull requests and pushes to `staging` and `main`. Secret-backed tests run
only for trusted `main` events and manual `main` runs. Fork pull requests run local checks only. CI
does not enable the separate live parser evaluation.

## Application and backend selection

| Property / environment variable | Default | Purpose |
| --- | --- | --- |
| `spring.application.name` | `RecommenderAgent` | Application identity |
| `server.port` / `SERVER_PORT` | `8080` | HTTP listening port |
| `spring.threads.virtual.enabled` | `true` | Enables virtual request threads |
| `session.store` / `SESSION_STORE` | `redis` | Selects the registered session adapter |
| `audiobook.database` / `AUDIOBOOK_DATABASE` | `qdrant` | Selects one database for the operation |

Qdrant and Solr both support the live recommendation server and its maintenance jobs. Select
`qdrant` or `solr`; only that database's runtime adapters and client are enabled. Migration is the
explicit exception that connects to both databases. Selecting a database does not move records.
When switching to a different catalogue, use new sessions or clear existing history so cached pages
from the previous backend are not reused.
A replacement session adapter must preserve locking, checked commits, and expiry, not just get/set.
See [adapter contracts](architecture.md#database-selection-and-replacement).

Similarity requests require title/author metadata and an existing compatible dense vector for the
reference book. No new environment variables are needed. Solr title lookup queries `title` (it must
be indexed); Qdrant uses its title payload text match. Existing Qdrant 1.19.1 collections work without
a data rewrite; a lowercase full-text `title` payload index can speed larger catalogues. See
[reference-book behaviour](architecture.md#books-similar-to-a-reference).

## AI models and shared vector dimensions

| Property / environment variable | Default | Purpose |
| --- | --- | --- |
| `embedding.dimensions` / `EMBEDDING_DIMENSIONS` | `1536` | Shared embedding-model and database vector dimension |
| `spring.ai.retry.max-attempts` | `3` | Maximum attempts for Spring AI operations, including the first attempt |
| `ai.input-parser.model` / `INPUT_PARSER_CHAT_MODEL` | empty | Parser model override; blank inherits the provider model |
| `ai.response-generator.model` / `RESPONSE_GENERATOR_CHAT_MODEL` | empty | Independent response model override; blank inherits the provider model |
| `spring.ai.openai.api-key` / `OPENAI_API_KEY` | required | OpenAI credentials for interactive AI and catalogue embedding generation |
| `spring.ai.openai.chat.model` / `OPENAI_CHAT_MODEL` | `gpt-4o-mini` | Default interactive chat model |
| `spring.ai.openai.embedding.options.model` | `text-embedding-3-small` | Embedding model; override the property directly if needed |
| `spring.ai.openai.embedding.options.dimensions` | `${embedding.dimensions}` | Sends the shared dimension to the embedding provider |

The configured model must support the requested dimensions. Catalogue vectors, query vectors,
stored user-preference vectors, and the selected database schema must use compatible embeddings.
Changing the model or dimension does not rebuild existing data or migrate a collection. Coordinate
reindexing and preference regeneration before serving queries against changed embeddings.

Separate parser and response model names still use their configured provider; a model-name override
does not switch providers. Provider replacement is described in [chat-model wiring](architecture.md#chat-models).
The summary model is separate from both interactive roles.

## Ranking rules

Weights combine available signals; they are not percentages and need not total one. Retrieval scores
are normalized per result set. Exact filters remain mandatory regardless of ranking weights.

| Property under `ranking.scoring.` | Environment variable | Default |
| --- | --- | --- |
| `retrieval-relevance-weight` | `RANKING_SCORING_RETRIEVAL_RELEVANCE_WEIGHT` | `1.0` |
| `user-preference-weight` | `RANKING_SCORING_USER_PREFERENCE_WEIGHT` | `1.0` |
| `universal-rules-weight` | `RANKING_SCORING_UNIVERSAL_RULES_WEIGHT` | `0.5` |
| `user-preference.favourites-weight` | `RANKING_SCORING_FAVOURITES_WEIGHT` | `0.6` |
| `user-preference.history-weight` | `RANKING_SCORING_HISTORY_WEIGHT` | `0.4` |
| `universal-rules.recency-weight` | `RANKING_SCORING_UNIVERSAL_RECENCY_WEIGHT` | `0.7` |
| `universal-rules.duration-plausibility-weight` | `RANKING_SCORING_UNIVERSAL_DURATION_PLAUSIBILITY_WEIGHT` | `0.3` |

## Supported and tested versions

`Required` is a build or runtime constraint enforced by the project. `Tested` identifies the
version used by the checked-in local environment or integration tests; other server versions may
work but are not verified by this repository. Build dependency versions describe the application,
not a separately installed prerequisite.

| Component | Version | Status | Source of truth |
| --- | --- | --- | --- |
| Java | `21` (`[21,22)`) | Required | [Maven build](../pom.xml) |
| Maven | `3.9.16` wrapper; `3.9+` enforced | Required | [Maven wrapper](../.mvn/wrapper/maven-wrapper.properties) and [build enforcement](../pom.xml) |
| Spring Boot | `4.1.0` | Build dependency | [Maven parent](../pom.xml) |
| Spring AI | `2.0.1` | Build dependency | [Maven dependency management](../pom.xml) |
| Solr server | `9.6.0` | Tested | [Solr container test support](../src/test/java/com/gen3/recommenderagent/testsupport/SolrContainerTestSupport.java) |
| SolrJ client | `9.6.0` | Build dependency | [Maven dependency](../pom.xml) |
| Qdrant server | `1.19.1` | Tested/local default | [Local Compose services](../compose.yaml) |
| Qdrant Java client | `1.18.0` | Build dependency | [Maven dependency](../pom.xml) |
| PostgreSQL history server | `17-alpine` | Optional/tested | [Separate local history service](../compose.history.yaml) |
| PostgreSQL JDBC / HikariCP | `42.7.11` / `7.0.2` | Build dependencies, managed by Boot | [Maven dependency management](../pom.xml) |
| Redis server | `7-alpine` | Tested/local default | [Local Compose services](../compose.yaml) |
| Grafana Alloy | `1.20.0` | Optional local observability | [Observability Compose services](../compose.observability.yaml) |

The `redis:7-alpine` image is a moving major-version tag rather than an exact patch release. Pin it
to a patch tag or image digest if byte-for-byte environment reproducibility is required. When a
version changes, update its executable source above first, run the relevant integration tests, and
then update this summary table in the same change.

## Technology connections

### Redis

Required by the live server when `SESSION_STORE=redis`; maintenance jobs do not need sessions.
The local Compose stack starts Redis automatically. For a separate installation, use the
[official Redis installation guide](https://redis.io/docs/latest/operate/oss_and_stack/install/archive/install-redis/).

| Property under `spring.data.redis.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `host` | `REDIS_HOST` | `localhost` |
| `port` | `REDIS_PORT` | `6379` |
| `username` | `REDIS_USERNAME` | empty; set when authentication requires it |
| `password` | `REDIS_PASSWORD` | empty; set when authentication requires it |

### Read-history storage

Optional PostgreSQL storage is independent of Solr/Qdrant, preference vectors, and Redis sessions.
Follow the [read-history runbook](jobs/read-history.md) for explicit schema setup, CSV import,
failure semantics, and replacement adapters. Enable only after preparing the database.

| Property under `read-history.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `enabled` | `READ_HISTORY_ENABLED` | `false`; enables the PostgreSQL reader on the server |
| `maximum-books-per-user` | `READ_HISTORY_MAXIMUM_BOOKS_PER_USER` | `10000`; oversized histories fail open with a warning, never silently truncate |
| `jdbc-url` | `READ_HISTORY_JDBC_URL` | `jdbc:postgresql://localhost:5432/read_history`; independent history database |
| `username` | `READ_HISTORY_USERNAME` | empty; use a read-only role for the server |
| `password` | `READ_HISTORY_PASSWORD` | empty; supply through environment/dev `.env` |
| `pool-size` | `READ_HISTORY_POOL_SIZE` | `5`; maximum history connections per process |
| `import.enabled` | `READ_HISTORY_IMPORT_ENABLED` | `false`; opt-in for the dedicated importer |
| `import.csv-path` | `READ_HISTORY_IMPORT_CSV_PATH` | empty; existing-format history CSV |
| `import.batch-size` | `READ_HISTORY_IMPORT_BATCH_SIZE` | `1000`; transactional CSV-row batch, allowed range 1–100000 |

No history is loaded at startup or cached in Redis. Lookup requests at most the per-user limit plus
one. Pool acquisition is bounded to two seconds, JDBC query timeout to five seconds, and driver
socket timeout to ten seconds; these are operation bounds, not a total request deadline. Failed
lookups log and continue without history exclusions, not with a claim that history was empty.

### Qdrant

The local Compose stack starts Qdrant automatically. For other local or self-hosted deployments,
follow the [Qdrant quickstart](https://qdrant.tech/documentation/quickstart/).

| Property under `qdrant.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `url` | `QDRANT_URL` | `http://localhost:6333`; endpoint used to configure the connection |
| `grpc-port` | `QDRANT_GRPC_PORT` | `6334`; query connection port |
| `api-key` | `QDRANT_API_KEY` | empty; required for authenticated servers |
| `collection` | `QDRANT_COLLECTION` | `audiobooks_hybrid`; existing audiobook catalogue |
| `user-preference-collection` | `QDRANT_USER_PREFERENCE_COLLECTION` | `user`; stored favourites/history vectors |
| `embedding-dimension` | Inherits `embedding.dimensions` | `1536`; keep aligned with the model and collection |
| `timeout` | `QDRANT_TIMEOUT` | `15s`; timeout for one Qdrant call, not the whole HTTP request |

Starting local Docker does not populate either collection. Existing schema and record-identity
requirements are in [Qdrant schema](architecture.md#qdrant-schema-and-record-identity).

The current demonstration Qdrant catalogue has 8-bit scalar quantisation enabled for its vectors.
Treat that as part of the deployed collection configuration rather than an application default: the
server does not enable quantisation when it connects to an existing collection.

> **Data-quality warning:** the current demonstration Qdrant catalogue contains fake/placeholder
> values for some `narrators`, `language`, `duration_minutes`, and publication-date metadata. Do not
> treat those fields as authoritative or migrate them into production unchanged. Replace them with
> sourced metadata and update the schema, mappings, indexes, tests, and documentation if the real
> dataset uses different fields.

#### Catalogue payload indexes

Prepare these indexes when supplying an existing Qdrant catalogue, especially when strict mode
rejects filters on unindexed fields. Names and casing must match the stored payload fields.

| Payload fields | Index type | Used for |
| --- | --- | --- |
| `id` | Keyword | Catalogue identity lookups, vector reads, and migration checks; separate from Qdrant point ID |
| `authors`, `narrators`, `language`, `source` | Keyword | Exact inclusion/exclusion filters |
| `duration_minutes` | Integer | Runtime range filters |
| `pubdate` | Datetime | Publication-year requests translated to date ranges |
| `short_description` | Keyword | Missing-summary selection by the short-description job |
| `title` | Optional full-text, lowercase | Faster reference-title lookup on larger catalogues |

When the catalogue adapter creates a new collection during an explicit write, it creates the
standard indexes above except `short_description` and `title`. Normal server startup and reads
do not create collections or backfill indexes on existing collections. Add missing standard indexes
to an existing catalogue yourself; the short-description job creates its own `short_description`
index before selecting records. The optional `title` index is not created automatically.
Payload indexes are separate from the collection's named `dense` and `keywords` (sparse) vectors.
The explicit Solr-to-Qdrant migrator also prepares `id`, `duration_minutes`, and `pubdate` indexes
on an existing collection. No `publicationYear` payload is stored or read. A valid `pubdate` may be
date-only (`2013-07-03`) or an ISO timestamp; timestamps are normalized to UTC. Missing/invalid dates
have no inferred year. For example, year 2013 filters from January 1, 2013 up to January 1, 2014.
Catalogue JSON and saved recommendation JSON use `id`, `duration_minutes`, and `pubdate` too.
Clear old development sessions when switching schemas; there are no legacy field aliases.
Collections using old payload names must be migrated to the documented schema before running the
agent or maintenance jobs.

### Solr

These settings apply to the recommendation server and jobs when Solr is selected. Solr is not
included in the local Compose stack; install and operate it using the
[official Solr guide](https://solr.apache.org/guide/solr/latest/). Redis is still the session store.

| Property under `solr.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `url` | `SOLR_URL` | `http://localhost:8983/solr` |
| `collection` | `SOLR_COLLECTION` | `combinedbooks` |
| `username` | `SOLR_USERNAME` | empty; optional authentication |
| `password` | `SOLR_PASSWORD` | empty; optional authentication |
| `vector-field` | `SOLR_VECTOR_FIELD` | `embedding`; dense-vector field |
| `user-preference-collection` | `SOLR_USER_PREFERENCE_COLLECTION` | `user`; separate collection containing user taste vectors |
| `embedding-dimension` | Inherits `embedding.dimensions` | `1536`; must match the external field schema |

To select Solr, supply these values through your environment or the dev `.env`:

```properties
AUDIOBOOK_DATABASE=solr
SOLR_URL=http://localhost:8983/solr
SOLR_COLLECTION=combinedbooks
SOLR_VECTOR_FIELD=embedding
SOLR_USER_PREFERENCE_COLLECTION=user
EMBEDDING_DIMENSIONS=1536
```

Supply OpenAI credentials and Redis settings as usual. Add Solr credentials when authentication is
enabled. `SOLR_URL` points to `/solr`, not a particular collection.

Prepare the collections before use; reads and normal server startup never create collections or schemas:

- **Catalogue:** Use a string `id` unique key as the book ID. Apply the
  [catalogue vector schema](../solr/vector-field-schema.xml), matching the configured field name and
  dimensions. Generate vectors with the opt-in embedding job if absent. The adapter uses `source`,
  `title`, `authors`, `description`, `narrators`, `language`, `duration_minutes`, `pubdate`, and
  `short_description`. Keyword search, including the keyword part of hybrid retrieval, targets only
  the analyzed `all` field; populate it with the text you want searchable. Explicit query field
  selectors are restricted to `all` too. Exact author/narrator/language/source filters still use their
  dedicated keyword (`string`) fields. Store metadata needed for responses and updates.
  Publication filtering expects indexed/stored `pubdate` with Solr date type `pdate` (or equivalent).
  Use a stored/indexed integer field for `duration_minutes`. The adapter does not read the old
  `durationMinutes` or `publicationYear` fields and does not rename your source records.
- **User preferences:** Create a separate collection using the
  [preference schema](../solr/user-preference-schema.xml). Its string `id` is the exact external user ID;
  `favouritesVector` and `historyVector` are optional stored vectors of the shared dimension. Use the
  preference import job to populate them. The importer replaces mapped vector sides, preserves an
  absent or completely unmapped side, and preserves other user fields through atomic updates. Retain
  `_version_` and make non-copy fields
  stored or backed by docValues as required for Solr atomic updates.

The catalogue and preference collections must have different names to prevent ID collisions. Unknown
users can still supply session preferences. Preference read errors fall back to session taste; catalogue
search errors are reported as unavailable. Candidate vectors are returned and normalized for ranking;
missing vectors make the preference ranking signal unavailable for that record. Malformed stored
vectors fail rather than supplying misleading similarity scores.

Solr hybrid search blends lexical and dense scores; it does not use Qdrant's sparse-vector schema.
Expect ranking differences between databases even with equivalent catalogue content.

## Maintenance jobs: explicit opt-in

Use the dedicated launcher and its opt-in setting. Normal server startup rejects maintenance flags.
**Jobs can write records or incur provider charges and are not scheduled automatically.** Commands,
prerequisites, and recovery live in the [job runbooks](jobs/README.md); shared progress and safety
rules are in [Maintenance operations](maintenance.md).

### Catalogue embedding generation

Target selection uses `AUDIOBOOK_DATABASE` (`qdrant` or `solr`). The job fills missing dense vectors
by default and preserves metadata. Explicit `rebuild-all` replaces existing vectors too. The Qdrant
implementation also builds sparse vectors for each book being embedded.

| Property under `audiobook.embedding-index.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `enabled` | `AUDIOBOOK_EMBEDDING_INDEX_ENABLED` | `false`; explicit opt-in |
| `mode` | `AUDIOBOOK_EMBEDDING_INDEX_MODE` | `missing-only`; generate absent dense vectors, or explicit `rebuild-all` |
| `page-size` | `AUDIOBOOK_EMBEDDING_INDEX_PAGE_SIZE` | `100`; records per page |
| `maximum-records` | `AUDIOBOOK_EMBEDDING_INDEX_MAXIMUM_RECORDS` | `0`; all records matching the mode, or a positive scan limit |

Missing-only does not detect edited book text or the model that produced an existing vector.
Model/dimension changes require a complete compatible rebuild; see the
[embedding runbook](jobs/embeddings.md#edited-books-and-model-changes). Both modes rebuild the full
book-vector snapshot after a successful pass, even if no embeddings were generated.

### Short-description generation

The selected database supplies eligible records and receives generated text immediately.
This job uses regular OpenAI calls, not the Batch API. It runs only through its dedicated launcher
with explicit enablement.

| Property under `audiobook.short-description.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `enabled` | `AUDIOBOOK_SHORT_DESCRIPTION_ENABLED` | `false`; explicitly enable the dedicated job |
| `mode` | `AUDIOBOOK_SHORT_DESCRIPTION_MODE` | `missing-only`; explicit `rebuild-all` regenerates and overwrites existing summaries |
| `maximum-records` | `AUDIOBOOK_SHORT_DESCRIPTION_MAXIMUM_RECORDS` | `100000`; eligible-book cap per run; positive |
| `concurrency` | `AUDIOBOOK_SHORT_DESCRIPTION_CONCURRENCY` | `5`; maximum concurrent generation requests; positive |
| `openai.base-url` | `OPENAI_SHORT_DESCRIPTION_BASE_URL` | `https://api.openai.com/v1`; regular API endpoint |
| `openai.model` | `OPENAI_SHORT_DESCRIPTION_MODEL` | `gpt-4o-mini`; summary model |

The job uses `OPENAI_API_KEY` independently of interactive Spring AI chat clients.
It groups up to ten books per request and saves incrementally. Missing-only can resume by selecting
remaining books. **Rebuild-all restarts selection and can overwrite/recharge processed books.**
See the [short-description runbook](jobs/short-descriptions.md).

### User-preference import

The job streams CSV interaction records and aggregates the last compatible local snapshot; it does
not call an embedding model or retrieve catalogue vectors remotely. A valid snapshot is required
in both modes. See the [import runbook](jobs/user-preferences.md) for CSV columns, memory/disk
requirements and replacement semantics.

| Property under `user.preferences.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `import-on-startup` | `USER_PREFERENCES_IMPORT_ON_STARTUP` | `false`; opt-in for the dedicated launcher |
| `import.mode` | `USER_PREFERENCES_IMPORT_MODE` | `dry-run`; read and calculate without writes; explicit approved `write` allows mapped-side updates |
| `favourites-csv-path` | `USER_PREFERENCES_FAVOURITES_CSV_PATH` | empty; favourites input path |
| `history-csv-path` | `USER_PREFERENCES_HISTORY_CSV_PATH` | empty; history input path |
| `import.max-book-ids-per-group` | `USER_PREFERENCES_IMPORT_MAX_BOOK_IDS_PER_GROUP` | `100000`; maximum distinct books referenced per aggregation group and per user; does not limit the full snapshot cache |
| `import.max-records-in-memory` | `USER_PREFERENCES_IMPORT_MAX_RECORDS_IN_MEMORY` | `100000`; sort-run and whole-user batch target |
| `import.work-directory` | `USER_PREFERENCES_IMPORT_WORK_DIRECTORY` | `./data/user-import-work`; temporary external-sort files |
| `import.max-unmapped-user-ratio` | `USER_PREFERENCES_IMPORT_MAX_UNMAPPED_USER_RATIO` | `0.10`; write-mode abort threshold after full preflight |
| `import.manifest-path` | `USER_PREFERENCES_IMPORT_MANIFEST_PATH` | `./data/user-import-work/approved.properties`; exact dry-run approval manifest |

### Local book-vector snapshots

| Property | Environment | Default / meaning |
| --- | --- | --- |
| `book.vector-snapshot.directory` | `BOOK_VECTOR_SNAPSHOT_DIRECTORY` | `./data/book-vector-snapshot`; persistent local snapshot storage shared by maintenance jobs |
| `book.vector-snapshot.page-size` | `BOOK_VECTOR_SNAPSHOT_PAGE_SIZE` | `500`; catalogue records read per snapshot export page |

Snapshots are separated by backend and catalogue collection. Compatibility uses the backend,
collection, configured `spring.ai.openai.embedding.options.model`, and `embedding.dimensions`.
The selected `audiobook.database` requires its matching `<backend>.collection` property. Built-in
defaults come from the Qdrant and Solr configuration tables above; the snapshot identity resolver
does not contain a backend list or duplicate those defaults. A replacement backend must configure
its own collection and register a `CatalogueSnapshotSource` to generate snapshots.
**The model identifier is a declared contract; database records do not store model provenance.**
Changing servers while retaining the same backend/collection name requires a manual snapshot rebuild.
The [snapshot launcher](jobs/snapshots.md) needs no enable flag. Imports accept a compatible snapshot
regardless of age; refresh it explicitly after catalogue changes. The embedding launcher refreshes
it automatically after successful embedding generation. Snapshot publication does not change any
database fields.

### Solr-to-Qdrant migration

This is the exception to single-database operation: it connects to both source and destination.
It copies existing supported fields and vectors without generating embeddings or summaries.

| Property under `audiobook.qdrant.migration.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `enabled` | `QDRANT_MIGRATION_ENABLED` | `false`; opt-in for the dedicated launcher |
| `batch-size` | `QDRANT_MIGRATION_BATCH_SIZE` | `100`; records per batch |
| `max-records` | `QDRANT_MIGRATION_MAX_RECORDS` | `0`; full catalogue, or a positive record limit |

## Common setups

| Setup | Configuration to supply |
| --- | --- |
| Local development | Start Redis and Qdrant with Compose, populate the catalogue, supply `OPENAI_API_KEY`, and use the `dev` profile for `.env` loading and the website. |
| Remote Qdrant | Set `QDRANT_URL`, `QDRANT_GRPC_PORT`, authentication and collection names for the remote instance; configure Redis separately. |
| Solr recommendation server | Select `AUDIOBOOK_DATABASE=solr`, configure both collections and their schemas above, populate catalogue vectors, and provide OpenAI and Redis connections. |
| Catalogue maintenance in Solr | Select `AUDIOBOOK_DATABASE=solr`, supply Solr connection/schema settings, and run the dedicated embedding or summary launcher with its opt-in mode. Embedding generation needs OpenAI credentials; summary generation uses its regular API client. |

Optional Grafana settings belong to the Compose collector rather than `application.properties`;
see [observability setup](observability.md#setup).
