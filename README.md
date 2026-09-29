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
6. `RankingService` removes duplicates, applies semantic-query adjustments, and limits results.
7. `SessionService` appends the completed request directly to the Redis-backed `Session`.

Retrieval is deliberately independent of intent:

| Request content | Retrieval mode |
| --- | --- |
| Semantic query and filters | `HYBRID` |
| Semantic query only | `SEMANTIC` |
| Filters only | `FILTER_ONLY` |
| Neither | `NONE` |

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

`REFINE`, `MORE_RESULTS`, `CHANGE_COUNT`, and `UPDATE_PREFERENCES` currently have boilerplate
actions and do not mutate the session. Persistent user/session preferences are intentionally not
implemented yet.

Each session has a `bookCount` limit. It defaults to the shared `MAX_BOOK_COUNT` value of five, and
values above that maximum are capped by `Session.setBookCount`.

## Storage and retrieval adapters

Qdrant is the default candidate source. It stores a normalized named dense vector (`dense`), a
BM25-style sparse vector (`keywords`), and filterable audiobook metadata. Authors, narrators,
language, duration, and source can be required or excluded. Hybrid retrieval combines dense and
sparse results using reciprocal-rank fusion.

Redis stores session history. There is no PostgreSQL or user-profile dependency.

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
