# RecommenderAgent

A Spring Boot service that turns natural-language requests into audiobook recommendations.
It understands search criteria and follow-up requests, retrieves and ranks books, and returns a
concise response explaining why the books fit.

## High-level concepts

1. **Parse:** A chat model identifies the intent, desired and avoided concepts, exact filters,
   and requested result count.
2. **Find and rank:** The service embeds search concepts, retrieves candidates from Qdrant,
   and ranks them using relevance, available user preferences, and general scoring rules.
3. **Remember:** Redis keeps a 24-hour session, including search context, recently shown books,
   and a batch of recommendations waiting to be shown.
4. **Respond:** A separate chat client explains the selected books using their stored short descriptions.

Catalogue embedding generation, short-description generation, migration, and preference imports
run as separate, explicit maintenance jobs. Normal requests only generate search-query embeddings.

## How the recommender agent works

### 1. Turn the message into a structured request

`RequestGateway` accepts text at `/api/v1/recommendations`, along with user and session IDs.
`AiInputParser` asks its chat model to produce a `SessionRequest` containing:

- An **intent**, such as a new recommendation, a refinement, “more”, or “repeat”.
- Positive and negative **semantic criteria**: concepts the user wants or wants to avoid.
- **Exact filters**, such as author, narrator, language, duration, publication year, or source.
- A result count and whether the user explicitly requested personalisation.

For example, “dark fantasy under ten hours, but no romance” supplies desired fantasy concepts,
avoided romance concepts, and an exact duration limit. Semantic criteria influence similarity;
exact filters restrict which records are eligible. Only new searches and refinements introduce
new semantic criteria; follow-up control intents can inherit the saved search.

### 2. Resolve the conversation under a session lock

`SessionTransitionService` loads or creates the session through `SessionRepository.update`.
The Redis adapter locks that user/session pair so separate server instances cannot update it
at the same time. `ConversationState` then resolves the request against saved search criteria,
preferences, and history, and discards cached recommendations when the search changes.

Some intents need no search: help, count changes, clearing history, and repeat are handled from
conversation state. “More” without an earlier search asks what the user wants instead of
embedding or retrieving anything. A personalised request without usable taste information asks
for preferences.

### 3. Retrieve candidates when the cache cannot supply the page

`AudiobookRecommendationWorkflow` prepares positive and negative query embeddings. For explicitly
personalised retrieval, it also blends available stored favourite/history vectors with the current
query. Without stored vectors, it uses the supplied or saved preferences.

`AudiobookRetrievalPlanner` builds the exact filters and chooses semantic, keyword, hybrid,
or filter-only retrieval from the searchable content. `CandidateRetrievalService` executes the
plan through the selected database adapter. Positive vectors point toward desired concepts;
negative vectors steer away from avoided concepts. Exact filters remain mandatory.

Qdrant excludes recently shown book IDs before selecting up to **50 candidates**. This leaves
unseen books eligible even when the closest matches have already been recommended.

### 4. Rank a batch and serve one page

`RankingService` removes duplicate or already-shown books and combines available scoring signals:
retrieval relevance, similarity to stored user preferences, and general rules such as recency and
duration plausibility. Their weights are configured in `application.properties`.

The service retains up to **20 ranked recommendations**, serves up to **five** according to the
requested count, and saves the unused recommendations in the session. Each saved item includes
its ID, title, short description, and score, so cached pages do not need another catalogue lookup.

| Follow-up | Behavior |
| --- | --- |
| “More please” | Consume the next cached page; skip preference lookup, embedding, retrieval, and ranking when enough items remain. Refill when needed, combining any cached tail with fresh results. |
| “Repeat those” | Reuse the last recommendation page without consuming the cache or retrieving books. An empty “more” outcome is also repeated. |
| Refine the search or start a new one | Discard the pending batch and retrieve using the resolved criteria. |
| Change the result count or ask for help | Keep the pending batch. |
| Clear history | Clear shown IDs, search history, and pending recommendations; keep saved preferences and the result-count setting. |

Short “more” pages are allowed. If none remain, the response explicitly says there are no more
matches for the current search. New exact filters supplied with “more”, or preference updates,
also invalidate the pending batch.

### 5. Commit the session, then explain the books

Queue consumption, shown IDs, and conversation changes commit together before a response is
written. The Redis adapter checks lock ownership and session version before saving, renews long
locks, and refreshes the session's 24-hour expiry. A failed transition leaves the saved queue
unchanged; response generation runs after the lock is released.

`ConversationState` supplies a temporary `ConversationDecision` describing the resolved action.
`AiResponseGenerator` uses built-in wording for actions and clarification, or asks its separate
chat client to explain the selected books using the resolved criteria and stored short descriptions.
The response model does not choose the books. If it fails, built-in wording returns the selected
recommendations; the committed session is preserved.

Request logs record stage timings, retrieval mode, and AI token usage. See
[Architecture and behavior](docs/architecture.md) for adapter guarantees and detailed rules, or
[the request sequence diagram](docs/diagrams/recommendation-flow.svg) for the execution order.

## Repository structure

Java sources live under `src/main/java/com/gen3/`:

| Package | Responsibility |
| --- | --- |
| `recommenderagent` | Live API: parsing, conversation state, retrieval, ranking, responses, storage adapters, and telemetry |
| `embeddingjob` | Read existing catalogue records and generate their vector embeddings |
| `shortdescriptionjob` | Submit and import generated short descriptions |
| `migrationjob` | Copy existing supported fields and vectors from Solr to Qdrant |
| `userpreferencejob` | Read CSV favourites/history and aggregate existing book vectors into user preferences |

The jobs reuse storage models and adapters from `recommenderagent`. Their launchers are separate
from the recommendation server. Matching tests live under `src/test/java/com/gen3/`.

| Location | Purpose |
| --- | --- |
| `src/main/resources/application.properties` | Backend selection, model choices, scoring, connections, and job settings |
| `compose.yaml` | Local Redis and Qdrant services |
| `compose.observability.yaml` / `observability/` | Optional Grafana Alloy log collector |
| `solr/vector-field-schema.xml` | Manual Solr vector-field schema snippet |
| `docs/` | Detailed behavior, maintenance instructions, and system diagrams |
| `.github/workflows/ci.yml` | Build and test checks |

## Run locally

Requirements: **Java 21**, Docker Compose, and an OpenAI API key.
You also need an existing audiobook collection with the expected Qdrant schema and indexed data;
starting Docker alone does not populate a catalogue.

```bash
docker compose up -d
export OPENAI_API_KEY="your-key"
./mvnw spring-boot:run
```

The server defaults to `http://localhost:8080`, Redis to `localhost:6379`, and Qdrant to
`http://localhost:6333` with gRPC on port `6334`. Set credentials in your shell or IDE;
the application does not automatically load a repository `.env` file.

```bash
curl -X POST http://localhost:8080/api/v1/recommendations \
  -H "X-Session-Id: session-123" \
  -H "X-User-Id: user-456" \
  -H "Content-Type: text/plain" \
  -d "Recommend a dark fantasy audiobook under ten hours, but no romance."
```

Reuse the same user and session IDs for follow-ups such as “more please” and “repeat those”.

## Important commands

Run formatting checks, build checks, unit tests, and local container integration tests
without calling live AI services. Docker must be available:

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw clean verify -DexcludedGroups=external
```

Run the entire suite, including live integration tests, with valid `OPENAI_API_KEY`,
`QDRANT_URL`, and `QDRANT_API_KEY` values and Redis available locally:

```bash
./mvnw clean verify
```

Apply Java formatting:

```bash
./mvnw spotless:apply
```

Maintenance commands and their prerequisites are in [Maintenance jobs](docs/maintenance.md).
They cover indexing either Solr or Qdrant, summary submission/import, migration, and preference imports.
For optional log shipping, follow [Grafana Cloud logs](docs/observability.md).

CI runs local tests for both shared branches. Trusted PRs to `main`, pushes to `main`, and manual
runs on `main` also run the entire suite with repository secrets. Fork PRs run the local suite.

## Important notes

- **Backend support:** Qdrant is the complete recommendation backend. Solr supports embedding and
  summary maintenance and migration, but the recommendation server rejects it until its missing
  vector-lookup and user-preference adapters are implemented. Backend selection does not move data.
- **Replaceable technologies:** Storage and provider contracts isolate adapters. Replacing Redis
  must preserve session locking, checked commits, and expiry; a plain get/set cache is insufficient.
  See [adapter contracts](docs/architecture.md#database-selection-and-replacement).
- **Existing data:** Maintenance jobs are opt-in, write data, and must run individually. Migration
  copies existing supported values; it does not generate embeddings or summaries. Preserve pending
  summary manifests so interrupted jobs can be recovered safely.
- **Session behavior:** Responses contain at most five books. The session retains up to 500 shown
  IDs, so older books may eventually reappear. Cached book details are snapshots. Session changes
  commit before response generation; a response failure does not roll them back.
- **Input limits:** Requests allow up to 10,000 Java characters, with a 40,000-byte HTTP body cap.
  Oversized input is rejected before model calls.
- **Client exposure:** User/session headers are caller-supplied; they are not authentication.
  Establish a verified identity boundary before exposing the service to untrusted clients.
- **Qdrant upgrades:** Local Docker uses server 1.19.1. Back up older volumes and upgrade through
  consecutive minor versions; do not attach a 1.15.x volume directly to 1.19.1.
- **Secrets:** Never commit API keys, `.env` files, local logs, or generated maintenance data.

## Further reading

- [Architecture and behavior](docs/architecture.md): terminology, conversation rules, internal
  packages, configuration, and adapter guarantees.
- [Maintenance jobs](docs/maintenance.md): commands, database prerequisites, progress, and recovery.
- [Grafana Cloud logs](docs/observability.md): optional collector setup and exported log fields.
- [System diagrams](docs/diagrams/README.md): overview, request sequence, and maintenance jobs.
