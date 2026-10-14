# RecommenderAgent

An AI-driven audiobook recommendation service built with Spring Boot 4.1 and Java 21.

The service accepts a natural-language request, parses it into structured positive and negative
semantic queries plus exact filters, retrieves audiobook candidates, ranks them, stores the session
history in Redis, and returns a natural-language response.

## Recommendation flow

1. `AiInputParser` classifies the request and creates a `SessionRequest`.
2. `ActionRegistry` selects the action for the parsed intent.
3. `AudiobookRetrievalPlanner` selects a retrieval mode from the request content.
4. Positive and negative semantic queries are embedded once. Candidate retrieval uses the
   normalized direction `positive - negative`, while ranking reuses the two vectors separately.
5. Qdrant retrieves candidates by semantic similarity, exact filters, or hybrid search.
6. `RankingService` removes duplicates and previously shown books, scores each candidate with the
   configured composite of retrieval relevance, user-preference, and universal-rule scorers, and
   orders by the combined score.
7. `RequestApplicationService` records the shown book IDs, and `SessionService` appends the
   completed request to the Redis-backed `Session`.

Retrieval is deliberately independent of intent:

| Request content | Retrieval mode |
| --- | --- |
| Semantic query and filters | `HYBRID` |
| Semantic query only | `SEMANTIC` |
| Filters only | `FILTER_ONLY` |
| Neither | `NONE` |

Personalised requests use dense semantic retrieval with the same exact filters, even when no
search text is supplied. See the preference flow below.

### Candidate scoring

Each scorer returns a bounded score and reports when it lacks usable evidence. Retrieval relevance
normalizes the search backend's candidate scores within the current result set to `[0, 1]`; it does
not recreate or rescore the semantic evidence already represented by Qdrant's ANN or hybrid result.
User-preference scores are also in `[0, 1]`. Universal rules add user-independent publication
recency and duration-plausibility signals. One composite scorer averages all available signals using
their configured weights, so missing evidence does not count as a zero signal.

Weights are configurable with the `ranking.scoring.*` properties in `application.properties` (or
their corresponding `RANKING_SCORING_*` environment variables). By default, retrieval relevance
and user-preference scorers have equal weight, while universal rules have a weight of `0.5`.
Publication recency and duration plausibility contribute `0.7` and `0.3` within the universal
scorer. Until the source catalogue supplies publication years, deterministic placeholder years are
derived from book IDs; real values are always preserved.

Topics, genres, and keywords provide semantic and lexical search content. Exact catalogue
requirements are represented separately as `mustInclude` and `mustNotInclude`, so a request such as
"only English, but not from source NLS" becomes a Qdrant `must` language condition and a `must_not`
source condition. Both groups support author, narrator, language, duration, and source. Positive and
negative semantic queries continue to control vector direction rather than exact exclusions.

## Request model and intents

`SessionRequest` contains:

- `positiveSemanticQuery`
- `negativeSemanticQuery`
- `mustInclude`
- `mustNotInclude`
- `bookCount`
- `personalised` (asks for recommendations based on the user's taste)
- `preferenceClarificationRequired` (response state when preferences are needed)
- intent, raw text, request metadata, and recommendations

The supported intents are:

- `RECOMMENDATION`
- `REFINE`
- `MORE_RESULTS`
- `CHANGE_COUNT`
- `UPDATE_PREFERENCES`
- `CLEAR_HISTORY`
- `HELP`
- `UNKNOWN`

`REFINE` merges new criteria into the latest search, `MORE_RESULTS` repeats that search while
excluding books already shown in the session, and `CHANGE_COUNT` updates the bounded session result
limit. `UPDATE_PREFERENCES` stores semantic and exact-filter defaults in the Redis-backed session;
these defaults are merged into later searches. Imported favourite and listening-history vectors are
stored separately in Qdrant and automatically influence personalized ranking when the request's user
ID has a matching preference point.

### Personalised recommendations

For requests such as "Give me books I would like", the parser sets `personalised=true`.
The workflow looks up the user's Qdrant preference point using `X-User-Id` and combines the
favourites and listening-history vectors with equal weight, then normalizes the result for
candidate retrieval. If only one vector exists, that vector is used. No query embedding API call
is needed for a known user. Exact request/session filters and the usual ranking and shown-book
exclusions still apply.

When there is no stored user vector, `SessionPreferences` supplies the embedding input. Semantic
preferences and structured author, narrator, language, duration, and source preferences are encoded;
positive preferences are combined with the inverse of negative preferences.

If these preferences are empty, the response is "What genres, authors, or narrators do you like?"
and retrieval is skipped. Redis retains `awaitingPersonalisedPreferences` on the session. The
next preference answer in the same `X-Session-Id` is appended to `SessionPreferences`, encoded,
and immediately used to retrieve recommendations. For example:

1. User: "Give me books I would like."
2. Assistant: "What genres, authors, or narrators do you like?"
3. User: "I like fantasy and Ursula Le Guin."
4. Assistant returns recommendations using the newly saved preferences.

`MORE_RESULTS` and `REFINE` retain the personalised mode of the previous search. `HELP` and
`CHANGE_COUNT` leave the pending preference question active; `CLEAR_HISTORY` cancels it.

Each session has a `bookCount` limit. It defaults to the shared `MAX_BOOK_COUNT` value of five, and
values above that maximum are capped by `Session.setBookCount`.

## Storage and retrieval adapters

Qdrant is the default candidate source. It stores a normalized named dense vector (`dense`), a
BM25-style sparse vector (`keywords`), and filterable audiobook metadata. Authors, narrators,
language, duration, and source can be required or excluded. Hybrid retrieval combines dense and
sparse results using reciprocal-rank fusion.

Redis stores session history. `shownBooks` contains only unique audiobook IDs rather than duplicate
recommendation objects. There is no PostgreSQL or user-profile dependency.

The Solr implementation is retained as an isolated compatibility adapter under
`storage/audiobook/solr`. Normal recommendation functionality does not depend on Solr. Set
`AUDIOBOOK_CANDIDATE_RETRIEVER=solr` only when the legacy adapter is explicitly required.

An opt-in migration runner can copy a Solr catalogue to Qdrant. Set
`QDRANT_MIGRATION_ENABLED=true` for a migration run, then return it to `false` after completion.

## Prerequisites

- Java 21
- Maven 3.9 or the included Maven wrapper
- Docker and Docker Compose
- An OpenAI API key

Solr 9.x is required only for the legacy adapter, migration, and Solr integration tests.

## Configuration

Required:

| Variable | Description |
| --- | --- |
| `OPENAI_API_KEY` | OpenAI chat and embedding API key |

Common optional settings:

| Variable | Default | Description |
| --- | --- | --- |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `QDRANT_URL` | `http://localhost:6333` | Qdrant HTTP URL |
| `QDRANT_GRPC_PORT` | `6334` | Qdrant gRPC port |
| `QDRANT_API_KEY` | empty | Qdrant API key |
| `QDRANT_COLLECTION` | `audiobooks_hybrid` | Qdrant collection |
| `AUDIOBOOK_CANDIDATE_RETRIEVER` | `qdrant` | Candidate adapter (`qdrant` or `solr`) |
| `QDRANT_MIGRATION_ENABLED` | `false` | Enable the Solr-to-Qdrant migration runner |

Solr settings (`SOLR_URL`, `SOLR_COLLECTION`, `SOLR_USERNAME`, and `SOLR_PASSWORD`) are optional
unless Solr retrieval or migration is enabled.

## Running locally

Start Redis and Qdrant:

```bash
docker compose up -d
```

Export the API key and run the application:

```bash
export OPENAI_API_KEY="your-key"
./mvnw spring-boot:run
```

The API accepts plain text:

```bash
curl -X POST http://localhost:8080/api/v1/recommendations \
  -H "X-Session-Id: session-123" \
  -H "X-User-Id: user-456" \
  -H "Content-Type: text/plain" \
  -d "Recommend a dark fantasy audiobook under ten hours, but no romance."
```

## Grafana Cloud logs (local)

This optional setup sends one safe, structured `recommendation.completed` event per request to
Grafana Cloud. It does not send request text, session contents, or the application's other logs.

### 1. Create Grafana Cloud credentials

In Grafana Cloud, create a Cloud Access Policy token with the `logs:write` scope. From the Loki
connection details, copy the Loki push URL and user ID. Create a `.env` file in the repository root:

```dotenv
GRAFANA_LOKI_URL=https://<loki-host>/loki/api/v1/push
GRAFANA_LOKI_USER=<loki-user-id>
GRAFANA_LOKI_TOKEN=<logs-write-token>
```

Docker Compose reads this file automatically. IntelliJ does not; do not put the Grafana variables
in an IntelliJ environment-file setting.

### 2. Configure application file logging

Create the local log file and configure the application to write to it:

```bash
mkdir -p logs && touch logs/recommender-agent.log
```

In IntelliJ, add the following under **Run configuration → VM options**. Replace the path with the
absolute path to this repository:

```text
-Dlogging.file.name=/absolute/path/to/IT-Project/logs/recommender-agent.log
```

For a terminal run instead, use:

```bash
LOGGING_FILE_NAME="$PWD/logs/recommender-agent.log" ./mvnw spring-boot:run
```

### 3. Start and manage the collector

Start Redis, Qdrant, and the opt-in Alloy collector:

```bash
docker compose -f compose.yaml -f compose.observability.yaml up -d
```

Check its status and follow its logs:

```bash
docker compose -f compose.yaml -f compose.observability.yaml ps
docker compose -f compose.yaml -f compose.observability.yaml logs -f alloy
```

Stop or restart only the collector without stopping Redis or Qdrant:

```bash
docker compose -f compose.yaml -f compose.observability.yaml stop alloy
docker compose -f compose.yaml -f compose.observability.yaml restart alloy
```

### 4. View request events

Make a recommendation request, then in Grafana **Explore** select the Loki data source and query:

```logql
{service="recommender-agent", environment="local"}
```

For failures only, add `outcome="FAILURE"` to the selector. Events are labelled with `service`,
`environment`, `outcome`, and `backend`. Alloy persists its file position, so it catches up on
completion events written while it was stopped. `.env` and `logs/` are intentionally ignored by Git.

## Tests

Run the full suite with Docker available:

```bash
./mvnw clean test
```

JUnit 5 unit tests cover parsing, planning, embeddings, retrieval, ranking, actions, and session
handling. Testcontainers starts Redis and Solr for integration tests. Docker-backed tests are
skipped automatically if Docker is unavailable.

## Project structure

```text
src/main/java/com/gen3/recommenderagent/
├── api/                 # REST entry point
├── application/         # Use cases, intent actions, and session service
├── candidateretriever/  # Candidate adapters, query vectors, and retrieval plans
├── common/              # Constants shared across layers
├── config/              # Redis and application configuration
├── domain/              # Intents and session-domain models
├── embedding/           # Text construction and vector operations
├── engine/              # Recommendation workflow
├── inputparser/         # Natural-language request parsing
├── ranker/              # Candidate scoring and final ranking
├── response/            # Natural-language response generation
└── storage/             # Redis, Qdrant, and isolated Solr repositories
```

The test source tree mirrors these application packages; integration-only support remains under
`integration/` and `testsupport/`.

Never commit API keys or `.env` files.
