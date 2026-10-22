# Architecture and behavior

[Back to README](../README.md)

The [README walkthrough](../README.md#how-the-recommender-agent-works) explains the request
pipeline. This document records internal responsibilities, behavior rules, and adapter contracts.

## Terminology

| Term | Meaning | Distinguish from |
| --- | --- | --- |
| Conversation policy | Rules governing how a request uses earlier criteria, preferences, recommendations, and history. | Intent routing or response wording |
| Conversation decision | Resolved conversational action, such as presenting recommendations, asking for clarification, or acknowledging an action. | Response text, intent, or “conversation outcome” |
| Search context | Retained criteria and personalisation choice for the latest recommendation search. | The entire conversation or saved preferences |
| Pending recommendations | Ranked books from the current search that have not yet been shown. | Shown books or the last page |

## Session guarantees and replacement

All session changes use one `SessionRepository.update` for the `(userId, sessionId)` pair.
An adapter must preserve cross-instance exclusion, single-attempt commits, the 24-hour sliding
expiry, and shared session error types. Queue consumption and shown IDs must commit together;
a cache providing only get/set needs additional coordination.

Redis checks lock ownership and session version before saving and renews locks during long
updates. Busy sessions return 409; rejected commits or lost locks return 503. Updates are not
retried automatically. Response generation happens after commit and cannot roll back history.

Set `SESSION_STORE` to a registered replacement adapter's name. This disables the Redis adapter
and Boot's Redis connections, templates, repositories, and health checks.

Lock keys use a separate `lock:` namespace. When upgrading from the old lock-key scheme, drain
and stop old instances and let their leases expire before serving traffic with the new version.
Do not mix the two locking versions.

## Search and retrieval rules

- A previous search with zero matches still supplies context for `MORE_RESULTS`. Clearing history
  removes that context. Both `MORE_RESULTS` and `REPEAT` supply resolved criteria to response generation.
- Pending recommendations are catalogue snapshots. Edits and removals take effect for new batches;
  cached items are not checked against the database on each page. Sessions without a pending queue
  refill normally, and the queue requires no Redis-specific keys or extra storage-adapter operations.
- Author-only and narrator-only semantic criteria select semantic retrieval. Exact names in
  `MustInclude` and `MustNotInclude` remain mandatory filters.
- For personalised retrieval with stored taste, one normalized taste vector and the current positive
  semantic vector contribute equally. Retrieval subtracts the negative vector and normalizes the
  result. When a positive source is absent, the other contributes at full strength. The ranker applies
  its separately configured weights afterward.
- Exact filters are never relaxed to fill the result count. Duration filters support hours/minutes,
  decimals, mixed units (`1h 30m`), and ranges (`between 2 and 3 hours`). `under` and `over` are strict;
  `at most` and `at least` include the boundary. Bare durations are exact, with bounds accounting for
  whole-minute catalogue values. Invalid, ambiguous, reversed, or unrepresentable ranges return 400.
- Publication-year filters accept exact four-digit years, strict `after`/`before` comparisons,
  inclusive `at least`/`at most` comparisons, and inclusive ranges. Unsupported or reversed
  expressions return 400.

## Recommendation server packages

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
(`UserFavourite` and `UserHistory`) belong to `userpreferencejob.model`. Catalogue embedding
generation remains in `embeddingjob`; the query embedding module only embeds live search criteria.
`RecommendationPolicy` owns the existing limits and result-count clamping rules.

## Conversation policy

`SessionTransitionService.process` remains the entry point for a single session transition.
`ConversationState` owns semantic eligibility, context inheritance, pending-batch invalidation,
preference/count updates, history retention and the resolved conversation decision. The transition
implementation retains cached paging, retrieval and timing inside one `SessionRepository.update`;
response generation still follows a successful commit.

`ConversationDecision` is temporary and excluded from session JSON. Retained search criteria and
recommendation details are persisted separately. The response implementation consumes this decision
for wording. Direct callers without a resolved decision use the same conversation-policy classifier;
there is no second intent policy.

The parser interface returns `SessionRequest` directly. Provider responses and token usage stay
inside the parser implementation; the request pipeline does not depend on Spring AI response types.

## Semantic information and control intents

Supported intents are `RECOMMENDATION`, `REFINE`, `MORE_RESULTS`, `REPEAT`, `CHANGE_COUNT`,
`UPDATE_PREFERENCES`, `CLEAR_HISTORY`, `HELP`, and `UNKNOWN`.

Only `RECOMMENDATION` and `REFINE` accept newly parsed positive/negative semantic queries or the
personalised flag. Other intents discard these fields. `MORE_RESULTS` and `REPEAT` inherit the saved
search context; semantic changes use `REFINE`. `UPDATE_PREFERENCES` can still update exact filters.
A semantic reply to a taste-clarification question uses `REFINE`.

## Configuration and schema

The server needs `OPENAI_API_KEY` plus an existing populated Qdrant collection. Connection defaults
suit local Docker:

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
duration, and source constraints are applied as filters. Solr supports embedding and summary
maintenance jobs and migration; it is not a complete selectable recommendation backend.

[`solr/vector-field-schema.xml`](../solr/vector-field-schema.xml) is a manual snippet for
configuring an external Solr collection. It stays outside `src/` because the application does not
load it or bundle it as a runtime resource. Apply its definitions to the target collection and match
the vector dimensions to the configured embedding model.

## Database selection and replacement

Local Docker and Qdrant integration tests use server 1.19.1 with Java client 1.18.0. Vector readers
use the current dense-vector response format; legacy flattened responses are not supported.

`audiobook.database` in `application.properties` selects one database for the whole request flow;
`qdrant` is the default. The former `audiobook.candidate-retriever` setting is rejected to prevent
mixed backends. The recommendation server still rejects `solr` until its missing vector lookup and
user-preference adapters are implemented; both maintenance jobs can select Solr independently.

To add another database, register its implementations of `AudiobookCandidateSearch`,
`AudiobookVectorRepository` and `UserPreferenceVectorRepository` under the same selection setting.
Selecting it disables all Qdrant clients and adapters. Missing implementations fail startup; they
never fall back to Qdrant. Preserve semantic, keyword, hybrid, and filtered search and the existing
write guarantees; report unsupported operations explicitly. Conversation and ranking code do not
change. A change of selection does not copy any data.

Qdrant field names, index types, vector schema, and filter translation live in
`QdrantAudiobookSchema`, inside the adapter. Reads still require an existing collection without
creating indexes. Explicit repository writes can create an absent collection; the embedding
maintenance job requires an existing collection. Existing collections are not automatically
inspected or migrated.

Qdrant catalogue lookups match the payload's `bookId`, independently of whether the point ID is an
integer or a UUID. Existing points retain their original IDs during explicit writes; migration skips
existing books. New books keep the deterministic UUID convention. A generated ID already occupied by
another record is rejected instead of overwritten.

Each `bookId` must identify one point in the selected collection. Lookup, migration, and explicit
writes reject multiple matches for a requested book ID; writes also reject duplicate IDs within an
input batch. No duplicate is selected, merged, or deleted automatically. Matching reads are
paginated in groups of at most 100. Keep the `bookId` keyword index on existing strict-mode
collections. Maintenance writes still require a stable catalogue and one writer; checks do not
enforce uniqueness against concurrent external writers.

## Chat models

The parser and response generator have separate clients. Set `INPUT_PARSER_CHAT_MODEL` and
`RESPONSE_GENERATOR_CHAT_MODEL` independently (properties `ai.input-parser.model` and
`ai.response-generator.model`). A blank value inherits the configured provider's default; OpenAI's
default is `gpt-4o-mini`, overridable with `OPENAI_CHAT_MODEL`. To use different providers, register
a Spring AI `ChatClient` bean named `inputParserChatClient` or `responseGeneratorChatClient`; each
replacement affects only its role. Model names must be supported by that client's provider.
Response-model failures and missing or blank response text use the built-in recommendation response;
the already committed session is preserved. These failures are recorded in local logs.

## Request limits

Recommendation text is limited to 10,000 Java characters. The HTTP endpoint also reads at most
40,000 bytes before rejecting an oversized body with 413, including bodies without Content-Length.
Overlong decoded text returns 400 before any model call.

See [application.properties](../src/main/resources/application.properties) for all settings and
[the diagrams](diagrams/README.md) for system views.
