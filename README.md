# RecommenderAgent

A Spring Boot service that turns natural-language requests into audiobook recommendations.
It understands search criteria and follow-up requests, retrieves and ranks books, and returns a
concise response explaining why the books fit.

[Request flow](#how-the-recommender-agent-works) · [Structure](#repository-structure) · [Run locally](#run-locally) · [Commands](#important-commands) · [Notes](#important-notes)

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
conversation state. Explanations also reuse the latest recommendation page. “More” without an earlier search asks what the user wants instead of
embedding or retrieving anything. A personalised request without usable taste information asks
for preferences.

### 3. Retrieve candidates when the cache cannot supply the page

`AudiobookRecommendationWorkflow` prepares positive and negative query embeddings. For explicitly
personalised retrieval, it also blends available stored favourite/history vectors with the current
query. Without stored vectors, it uses the supplied or saved preferences.

`AudiobookRetrievalPlanner` builds exact filters and selects a retrieval plan.
`CandidateRetrievalService` executes semantic, keyword, hybrid, or filter-only searches through
the selected database adapter. Positive vectors point toward desired concepts;
negative vectors steer away from avoided concepts. Exact filters remain mandatory.

Qdrant excludes recently shown book IDs before selecting up to **50 candidates**. This leaves
unseen books eligible even when the closest matches have already been recommended.

### 4. Rank a batch and serve one page

`RankingService` removes duplicate or already-shown books and combines available scoring signals:
retrieval relevance, similarity to stored user preferences, and general rules such as recency and
duration plausibility. Their weights are configured in `application.properties`.

The service retains up to **20 ranked recommendations**, serves up to **five** according to the
requested count, and saves the unused recommendations in the session. Each saved item includes
its ID, title, short description, score, and whether stored reading preferences contributed, so cached pages do not need another catalogue lookup.

| Follow-up | Behavior |
| --- | --- |
| “More please” | Serve the next cached page; refill when short, keeping the cached tail. |
| “Why these books?” / “Why the second book?” / “Why Harry Potter?” | Explain the latest page or a uniquely selected book, without searching or consuming the queue. |
| “Repeat that” | Replay the latest request, including help, explanations and clarifications; do not rerun searches or actions. |
| “Repeat the books” | Replay the latest recommendation page, even after help or an explanation; leave the queue untouched. |
| Refine or start a search | Discard the queue and retrieve using resolved criteria. |
| Change the result count or ask for help | Keep the pending batch. |
| Clear history | Clear search history, shown IDs, and the queue; keep preferences and result count. |

A full cached page skips preference lookup, embedding, retrieval, and ranking. Parsing and response
generation still run. Short “more” pages are allowed. If none remain, the response explicitly says there are no more
matches for the current search. New exact filters supplied with “more”, or preference updates,
also invalidate the pending batch.

Explanation requests use `EXPLAIN_RECOMMENDATION`. Invalid ranks, ambiguous/missing titles, or
conflicting rank/title references list the actual page in rank order and ask which book. An
explicit retry such as “Why the fourth book?” uses that same page. No earlier page is selected
after an empty MORE, and clearing history removes explanation context. Explanations connect the
saved search criteria to descriptions and mention stored reading preferences only when recorded
as having contributed; they do not invent exact metadata or specific favourites.

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

The server is backend-only by default: the demo page and JSON demo endpoint are unavailable.
For local development, the `dev` profile also loads an optional `.env` file from the working
directory. Use Java properties syntax: no `export` prefix or surrounding quotes. For example:

```properties
OPENAI_API_KEY=your-key
QDRANT_API_KEY=your-key
```

Existing environment variables take precedence. Missing `.env` files are allowed.
Start with:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Keep this profile disabled in production. Open `http://localhost:8080/` to test the website. It shows the
response, resolved request, stage timings, and token usage (including query embeddings).
The browser tab keeps its conversation across reloads using session storage.
The demo uses a generated user ID, so it has no imported user preference profile.
The JSON demo endpoint is `POST /api/v1/recommendations/demo`; the existing endpoint still
returns plain text. Demo metric collection is enabled only for `/demo` requests.
Both use the same recommendation flow. Timings overlap in the detailed
logs; the website measures the recommendation stage as a whole. Costs are not estimated.

The server defaults to `http://localhost:8080`, Redis to `localhost:6379`, and Qdrant to
`http://localhost:6333` with gRPC on port `6334`. Docker publishes these database ports on
`127.0.0.1` only. Outside the `dev` profile, set credentials in your shell or IDE; `.env` is
not automatically loaded. This import supplies application properties, not environment variables
for Maven or every test. Keep `.env` local; it is ignored by Git.

```bash
curl -X POST http://localhost:8080/api/v1/recommendations \
  -H "X-Session-Id: session-123" \
  -H "X-User-Id: user-456" \
  -H "Content-Type: text/plain" \
  -d "Recommend a dark fantasy audiobook under ten hours, but no romance."
```

Reuse the same user and session IDs for follow-ups such as “more please” and “repeat those”.

## Important commands

### Local verification

Run formatting/build checks, unit tests, and container integration tests without live AI calls.
Docker must be available:

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw clean verify -DexcludedGroups=external
```

### Entire test suite

Run live integration tests as well, with valid `OPENAI_API_KEY`,
`QDRANT_URL`, and `QDRANT_API_KEY` values and Redis available locally:

```bash
./mvnw clean verify
```

The live recommendation test requires actual saved recommendations and their titles in the response;
a nonblank fallback alone does not pass. Blank-input and no-match behavior are checked separately.

### Formatting and maintenance

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
