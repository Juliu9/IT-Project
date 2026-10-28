# Configuration reference

[README](../README.md) · [Architecture](architecture.md) · [Maintenance commands](maintenance.md)

[`application.properties`](../src/main/resources/application.properties) defines shared rules,
backend selection, provider connections, and job defaults. Settings are grouped by responsibility:
universal rules apply across adapters; technology sections configure a particular implementation.
This reference covers the repository's settings, rather than every property supported by Spring Boot.

## Loading and overriding settings

For local runs, command-line application arguments override environment variables, which override
values in the packaged properties files. For example, `--server.port=8081` overrides `server.port=8080`.
An expression such as `${QDRANT_TIMEOUT:15s}` reads the named variable and uses `15s` when absent.
An empty default (`${QDRANT_API_KEY:}`) means optional configuration, not a generated credential.

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

The `dev` profile serves `/` and `/index.html`, and enables `POST /api/v1/recommendations/demo`.
Without it the server is backend-only; both the page and demo endpoint are unavailable.
Keep the profile disabled in production. Launch commands are in the [README](../README.md#run-locally).

The page shows response text, resolved request, stage timings, and AI usage including query
embeddings. It provides editable User ID and Session ID fields, initially generated for the tab.
Submitted IDs persist across reloads through session storage and appear in response details.
Use an imported user's ID to test stored reading preferences; generated IDs have no imported profile.
Reuse the same user/session pair for follow-ups; change the session ID for a separate conversation.

Below the Session Request, the collapsed Recommendation details section shows each book's
stored catalogue fields (authors, narrators, language, publication year, source, runtime, and
descriptions), alongside its rank and score. Only the dev endpoint performs this metadata lookup;
it reads the configured Solr or Qdrant catalogue by book ID without loading vectors or changing
session data. Missing fields display as unavailable; lookup failures leave the response intact.
The extra lookup time is outside the recommendation pipeline timings shown above.

The demo collects per-operation metrics; the standard endpoint still returns plain text.
Both use the same recommendation flow. The page reports the whole recommendation stage and
distinguishes no AI call from unavailable usage. Costs are not estimated.

User/session headers identify a conversation; they are not authentication. Establish a verified
identity boundary before exposing the service to untrusted clients. Local Compose publishes its
service ports on `127.0.0.1` only; this does not authenticate the HTTP API.
Both endpoints require nonblank `X-User-Id` and `X-Session-Id` headers of at most 256 Java characters
each. Invalid IDs return `400` before parsing or session access. IDs remain opaque and are passed
through unchanged; UUID formatting is not required.

### Verification and live-test prerequisites

Commands are listed once in [README: Verification commands](../README.md#verification-commands).
Local verification includes Maven validation, Spotless, Checkstyle, unit tests, and disposable
container integration tests; Docker must be running.

The live recommendation test requires exported, nonempty `OPENAI_API_KEY`, `QDRANT_URL`, and
`QDRANT_API_KEY`, an accessible populated Qdrant catalogue, and Redis. This environment gate applies
even though local Qdrant can run without authentication. A dev `.env` import does not satisfy it.
The test requires actual saved recommendations and their titles in the response; a nonblank fallback
alone does not pass. Without the required variables, it is skipped.

Live parser evaluations additionally require `RUN_LIVE_PARSER_TESTS=true` and exported
`OPENAI_API_KEY`. They send synthetic text to the configured parser model, without catalogue or
Redis access. Cases cover natural phrases outside the fast path, ranks versus counts, repeat
variants, and duration extraction. Mocked tests check mappings and prompt contracts; these live
cases measure model recognition. Both kinds of live test incur provider charges.

CI checks local tests on PRs and pushes to `staging` and `main`. Trusted PRs to `main`, pushes to
`main`, and manual runs on `main` also run the secret-backed suite; fork PRs use local tests only.
The workflow does not opt into the separate live parser evaluations.

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
The summary Batch model is separate from both interactive roles.

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

## Technology connections

### Redis

Required by the live server when `SESSION_STORE=redis`; maintenance jobs do not need sessions.

| Property under `spring.data.redis.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `host` | `REDIS_HOST` | `localhost` |
| `port` | `REDIS_PORT` | `6379` |
| `username` | `REDIS_USERNAME` | empty; set when authentication requires it |
| `password` | `REDIS_PASSWORD` | empty; set when authentication requires it |

### Qdrant

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

### Solr

These settings apply to the recommendation server and jobs when Solr is selected. Solr is not
included in the local Compose stack; Redis is still the session store.

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
  `title`, `authors`, `description`, `narrators`, `language`, `durationMinutes`, `publicationYear`, and
  `short_description`. Keyword search, including the keyword part of hybrid retrieval, targets only
  the analyzed `all` field; populate it with the text you want searchable. Explicit query field
  selectors are restricted to `all` too. Exact author/narrator/language/source filters still use their
  dedicated keyword (`string`) fields. Store metadata needed for responses and updates.
- **User preferences:** Create a separate collection using the
  [preference schema](../solr/user-preference-schema.xml). Its string `id` is the exact external user ID;
  `favouritesVector` and `historyVector` are optional stored vectors of the shared dimension. Use the
  preference import job to populate them. Saving replaces both vector fields, clears a missing side,
  and preserves other user fields through atomic updates. Retain `_version_` and make non-copy fields
  stored or backed by docValues as required for Solr atomic updates.

The catalogue and preference collections must have different names to prevent ID collisions. Unknown
users can still supply session preferences. Preference read errors fall back to session taste; catalogue
search errors are reported as unavailable. Candidate vectors are returned and normalized for ranking;
missing vectors make the preference ranking signal unavailable for that record. Malformed stored
vectors fail rather than supplying misleading similarity scores.

Solr hybrid search blends lexical and dense scores; it does not use Qdrant's sparse-vector schema.
Expect ranking differences between databases even with equivalent catalogue content.

## Maintenance jobs: explicit opt-in

Use the dedicated launcher as well as the corresponding opt-in setting. Normal server startup
rejects enabled maintenance flags. Run jobs individually against the intended database and dataset;
they can write records and generate provider charges. Commands, prerequisites, and recovery steps
belong in [Maintenance jobs](maintenance.md).

### Catalogue embedding generation

Target selection uses `AUDIOBOOK_DATABASE` (`qdrant` or `solr`). The job reads existing records,
regenerates vectors, and preserves metadata. The Qdrant implementation also builds sparse vectors.

| Property under `audiobook.embedding-index.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `enabled` | `AUDIOBOOK_EMBEDDING_INDEX_ENABLED` | `false`; explicit opt-in |
| `page-size` | `AUDIOBOOK_EMBEDDING_INDEX_PAGE_SIZE` | `100`; records per page |
| `maximum-records` | `AUDIOBOOK_EMBEDDING_INDEX_MAXIMUM_RECORDS` | `0`; full catalogue, or a positive record limit |

### Short-description generation

The selected database supplies descriptions and receives summaries. Submission calls the provider;
import writes completed summaries. Keep the output directory and pending manifests between runs.

| Property under `audiobook.short-description.batch.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `mode` | `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE` | `disabled`; choose `submit`, `import`, or `recover` explicitly |
| `provider` | `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_PROVIDER` | `openai`; must have a registered provider adapter |
| `id` | `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID` | empty; optional explicit batch ID, otherwise use the pending manifest |
| `output-directory` | `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_OUTPUT_DIRECTORY` | `./data/short-descriptions`; local batch and recovery files |
| `maximum-records` | `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MAXIMUM_RECORDS` | `100000`; submission record cap |
| `openai.base-url` | `OPENAI_BATCH_BASE_URL` | `https://api.openai.com/v1`; Batch transport endpoint |
| `openai.model` | `OPENAI_BATCH_MODEL` | `gpt-4o-mini`; summary model |

Recovery verifies terminal provider failure and archives the pending manifest; it does not import
partial results. OpenAI Batch uses `OPENAI_API_KEY` independently of interactive chat clients.

### User-preference import

The job aggregates existing catalogue vectors from CSV interaction records; it does not call an
embedding model. See the maintenance guide for CSV columns and database support.

| Property under `user.preferences.` | Environment variable | Default / purpose |
| --- | --- | --- |
| `import-on-startup` | `USER_PREFERENCES_IMPORT_ON_STARTUP` | `false`; opt-in for the dedicated launcher |
| `favourites-csv-path` | `USER_PREFERENCES_FAVOURITES_CSV_PATH` | empty; favourites input path |
| `history-csv-path` | `USER_PREFERENCES_HISTORY_CSV_PATH` | empty; history input path |
| `import.max-users-per-group` | `USER_PREFERENCES_IMPORT_MAX_USERS_PER_GROUP` | `100`; maximum complete users processed together |
| `import.max-book-ids-per-group` | `USER_PREFERENCES_IMPORT_MAX_BOOK_IDS_PER_GROUP` | `10000`; maximum distinct books loaded per group |

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
| Catalogue maintenance in Solr | Select `AUDIOBOOK_DATABASE=solr`, supply Solr connection/schema settings, and run the dedicated embedding or summary launcher with its opt-in mode. Embedding generation needs OpenAI credentials; summary generation uses its selected Batch provider. |

Optional Grafana settings belong to the Compose collector rather than `application.properties`;
see [observability setup](observability.md#setup).
