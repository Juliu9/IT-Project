# Architecture and behavior

[README](../README.md) · [Sessions](#session-guarantees-and-replacement) · [Search rules](#search-and-retrieval-rules) · [Adapters](#database-selection-and-replacement) · [Models](#chat-models)

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

Redis checks lock ownership and session version before saving and renews long-running locks.
Response generation follows commit and cannot roll back history. Updates are not retried automatically.

| Failure | HTTP status |
| --- | --- |
| Session already locked | `409` |
| Commit rejected or lock lost | `503` |
| AI connection/service failure or exhausted retries | `503`, with a readable retry message |

Set `SESSION_STORE` to a registered replacement adapter's name. This disables the Redis adapter
and Boot's Redis connections, templates, repositories, and health checks.

Lock keys use a separate `lock:` namespace. When upgrading from the old lock-key scheme, drain
and stop old instances and let their leases expire before serving traffic with the new version.
Do not mix the two locking versions.

## Search and retrieval rules

### Context and cached results

A zero-match search still supplies context for `MORE_RESULTS`; clearing history removes it.
`MORE_RESULTS` restores saved search criteria. `REPEAT` replays the latest resolved request,
including explanations, help, clarifications, and empty results, without retrieving or applying
state changes again. Repeated REPEAT requests keep replaying the same original request.
`REPEAT_RECOMMENDATIONS` ("repeat the books", "show those recommendations again") instead
restores the retained search page, even after help or a single-book explanation. It leaves search
history, shown IDs and the pending queue untouched, while updating the latest replay snapshot so
"repeat that" afterward repeats that page. No page yields a fixed message; an empty latest MORE
repeats exhaustion rather than falling back to an older page. Clearing history removes the page.

The session stores one bounded `lastRequest` snapshot and its `lastDecision`, separately from
search/preference history. Raw input and final response text are not stored. A repeated explanation
uses the original selected books and criteria; AI wording may vary. Old sessions without these
fields fall back to their latest retained request. Clearing history removes the old snapshot, then
retains only the clear-history acknowledgement for replay.

Pending recommendations are catalogue snapshots. Edits and removals affect new batches; cached
items are not rechecked on each page. Sessions without a pending queue refill normally. The queue
needs no Redis-specific keys or additional storage-adapter operations.

### Refinements and exact-filter corrections

Self-contained criteria, such as “fantasy under five hours”, route to `RECOMMENDATION`.
A context-dependent `REFINE` without a previous search asks for criteria without embedding or
retrieval; stored user preferences alone are not a previous search. A self-contained request can
still answer a pending preference question and save that taste for the session.

New exact author/narrator lists replace the previous list by default. Explicit “also”/“add” wording
sets the parser's `addAuthors`/`addNarrators` flags and extends that list instead. Unrelated filters
and semantic criteria remain inherited for refinements.

Before queue invalidation or recommendation work, the planner checks exact include/exclude
constraints. Explicit new requirements override conflicting inherited exclusions, and vice versa.
Contradictions within the new message return `400` with a clarification question; the saved search,
queue, shown IDs and session version remain unchanged. Author/narrator alternatives are OR-based:
allowing A or B while excluding A remains valid. A range conflicts only when its exclusion covers
the entire required interval. Semantic combinations, such as romantic horror, are not conflicts.
This checks logical impossibility, not whether the current catalogue happens to contain matches.

### Semantic criteria and personalisation

Author-only and narrator-only semantic criteria select semantic retrieval. Exact names in
`MustInclude` and `MustNotInclude` remain mandatory database filters.

For personalised retrieval with stored taste:

1. Normalize the stored-taste signal and give it equal weight to the current positive semantic vector.
2. Subtract the negative semantic vector and normalize the combined result.
3. Apply the ranker's separately configured scoring weights to retrieved candidates.

If either positive source is absent, the other contributes at full strength. Exact filters are
never relaxed to fill the result count.

### Exact duration and year filters

| Filter | Accepted expressions | Boundaries |
| --- | --- | --- |
| Duration | Hours/minutes, decimals, `1h 30m`, `between 2 and 3 hours` | `under`/`over` are strict; `at most`/`at least` inclusive; bare duration exact |
| Publication year | Four-digit year, comparisons, inclusive ranges, `recent`, `past year`, `last N years` | `after`/`before` are strict; bare year exact; relative windows include both boundary years |

The parser preserves relative publication phrases rather than guessing a calendar year. Retrieval
resolves them against the current UTC year: `recent`/`recently` means the last five years; `past year`
means one year; `last 4 years` and `last four years` mean four. Both include and exclude filters use
these rules. In 2026 these become 2021–2026, 2025–2026, and 2022–2026 respectively, excluding future
years. Zero, fractional, negative, and unrepresentably large windows return `400`.

Catalogue metadata contains years only, so these are inclusive year ranges, not exact rolling
month/day cutoffs. Cached pages remain snapshots of the search that produced them.

Duration bounds account for whole-minute catalogue values. Invalid, ambiguous, reversed, or
unrepresentable duration ranges return `400`; unsupported or reversed year expressions also return `400`.

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

| Component | Owns |
| --- | --- |
| `SessionTransitionService.process` | One ordered transition: cached paging, retrieval, and timing inside `SessionRepository.update` |
| `ConversationState` | Semantic eligibility, context inheritance, queue invalidation, preference/count changes, history retention, and the conversation decision |
| `AiResponseGenerator` | Wording after the session commits |

`SessionRequest.decision` is temporary and excluded from request JSON. Retained search criteria and
recommendation details are persisted separately. Only the latest replay snapshot stores its decision
in `Session.lastDecision`. The response implementation consumes the resolved decision for wording. Direct callers without a resolved decision use the same conversation-policy classifier;
there is no second intent policy.

The parser interface returns `SessionRequest` directly. Provider responses and token usage stay
inside the parser implementation; the request pipeline does not depend on Spring AI response types.

A pending result-count question also accepts short answers such as “3” and “three”. The resolved
count updates the session without consuming the pending recommendation queue. An explicit count
on `MORE_RESULTS`, such as “2 more”, applies to that page without changing the saved default.

`CANCEL` acknowledges cancellation and clears pending preference clarification without clearing
search history, preferences, or queued books. “Never mind” and “cancel” also end count and book-selection
questions. Unrecognized replies no longer become personalised searches merely because a taste
question is pending.

Generic recommendation requests without criteria, such as “Recommend an audiobook”, use the
personalised path. Existing users can use stored taste vectors; users without usable preferences
are asked what they like before candidate retrieval. Explicit criteria still work without requiring
personalisation.

## Semantic information and control intents

Supported intents are `RECOMMENDATION`, `REFINE`, `MORE_RESULTS`, `REPEAT`, `REPEAT_RECOMMENDATIONS`, `CHANGE_COUNT`,
`EXPLAIN_RECOMMENDATION`, `UPDATE_PREFERENCES`, `CLEAR_HISTORY`, `HELP`, `CANCEL`, and `UNKNOWN`.

Only `RECOMMENDATION` and `REFINE` accept newly parsed positive/negative semantic queries or the
personalised flag. Other intents discard these fields. `MORE_RESULTS`, `REPEAT_RECOMMENDATIONS`, and
`EXPLAIN_RECOMMENDATION` inherit saved search context; `REPEAT` restores its retained request context; semantic changes use `REFINE`. `UPDATE_PREFERENCES` can still update exact filters.
A semantic reply to a taste-clarification question uses `REFINE`.

## Explaining recommendations

`EXPLAIN_RECOMMENDATION` carries optional `referenceRank` and `referenceTitle`, independent of
`bookCount` and semantic criteria. No selectors means the whole latest search page. Title matching
ignores case/punctuation and uses exact matches before unique partial matches. Missing, ambiguous,
out-of-range or conflicting references produce a ranked-title clarification. A bare ordinal is not
an explanation request on its own. While a book-selection clarification is pending, a numeric
reply such as “1”, “1.”, “first”, or “the first one” selects that rank from the retained page;
invalid numbers ask again. An otherwise unrecognized reply can also identify a displayed title. Other actions
such as help or a new search end this context. “Why the fourth book?” also resolves independently.
Model-provided ranks and counts accept large integers before conversion to bounded internal values:
oversized ranks clarify rather than overflow, and result counts retain the existing one-to-five cap.
Fractional numeric fields and structured responses exceeding JSON limits return a friendly `400`
validation message rather than truncating a position or returning a server error.

The resolved explanation copies saved criteria and selects from the page inside the session update.
It does not retrieve, embed, rank, consume the pending batch, add shown IDs, replace history, or clear
pending preference clarification. No search yields a fixed onboarding message; an empty latest page
yields a distinct fixed message, even if an older page had results. The session represents committed
recommendations, not confirmation that a client displayed or spoke them.

Each retained/queued `Recommendation.readingPreferencesUsed` records whether stored preference
vectors contributed to personalised retrieval or a positive active preference ranking score. It is
copied when batch items receive page ranks; old session JSON defaults to false. Explanations never
reload today's preference profile to justify an older selection. They may mention reading preferences
when recorded, but cannot infer favourite genres or specific books from that flag. Descriptions and
saved criteria support semantic explanations; filter requests alone cannot prove actual book metadata.
Response generation uses the existing role-specific chat client, with distinct explanation instructions
and an honest built-in fallback. Explanations are plain text with a 150-word total limit; oversized
AI output uses a bounded fallback covering every selected book. Clarification/no-history responses
need no response-model call.

## Configuration and schema

See [Configuration reference](configuration.md) for property defaults, environment overrides,
model selection, connections, and maintenance opt-ins.

Qdrant stores dense and sparse vectors plus filterable metadata. Exact author, narrator, language,
duration, and source constraints are applied as filters. Solr supports the same runtime storage ports,
plus catalogue embedding, summary maintenance, and preference imports. Its hybrid search blends
lexical and dense scores; candidate vectors are normalized for preference ranking.

[`solr/vector-field-schema.xml`](../solr/vector-field-schema.xml) is a manual snippet for
configuring an external Solr collection. It stays outside `src/` because the application does not
load it or bundle it as a runtime resource. Apply its definitions to the target collection and match
the vector dimensions to the configured embedding model.

## Database selection and replacement

### Backend selection

Local Docker and Qdrant integration tests use server 1.19.1 with Java client 1.18.0. Vector readers
use the current dense-vector response format; legacy flattened responses are not supported.

`audiobook.database` in `application.properties` selects one database for the whole request flow;
`qdrant` is the default. The former `audiobook.candidate-retriever` setting is rejected to prevent
mixed backends. Both `qdrant` and `solr` provide candidate search, book-vector lookup, and user-preference
storage. Maintenance jobs select their own backend with the same property.

### Add a database adapter

1. Register `AudiobookCandidateSearch`, `AudiobookVectorRepository`, and
   `UserPreferenceVectorRepository` implementations under the same backend selection.
2. Preserve semantic, keyword, hybrid, and filtered search and the existing write guarantees.
   Report unsupported operations explicitly.
3. Select the backend with `audiobook.database`. This disables Qdrant clients and adapters.

Missing implementations fail startup instead of falling back to Qdrant. Conversation and ranking
code remain unchanged. Selection does not copy data; maintenance jobs have their own store contracts.

### Solr schema and record identity

`SolrAudiobookRepository` supplies search, indexing, and exact vector lookups by the catalogue's
unique `id`. Lookups deduplicate IDs and query in batches of at most 100; missing vectors are omitted.
`SolrUserPreferenceRepository` stores exact external user IDs in a separate collection. Atomic saves
replace both taste vectors, including removal of an absent vector, while preserving unrelated metadata.
Reads do not create collections or schemas. Stored vectors must be finite, nonzero, and match the
configured dimension; preference and candidate vectors are normalized for ranking.

Solr is wired only when selected, except for the explicit migration source. The preference-import
launcher registers either Solr or Qdrant adapters without loading Redis or AI models. See
[Solr configuration](configuration.md#solr) for schema prerequisites and connection settings.

### Qdrant schema and record identity

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

The parser and response generator use separate clients:

| Role | Model override | Replacement `ChatClient` bean |
| --- | --- | --- |
| Input parser | `INPUT_PARSER_CHAT_MODEL` (`ai.input-parser.model`) | `inputParserChatClient` |
| Response generator | `RESPONSE_GENERATOR_CHAT_MODEL` (`ai.response-generator.model`) | `responseGeneratorChatClient` |

Blank overrides inherit the provider default; see [model settings](configuration.md#ai-models-and-shared-vector-dimensions).
A replacement bean affects only its role; its provider must support the model.

Response-model failure or missing/blank text uses built-in recommendation wording and preserves
the committed session. Failures are logged locally.

## Request limits

| Input | Result |
| --- | --- |
| Missing, empty, or whitespace-only text | `400` and the blank-input fallback; no model or session work |
| Decoded text over 10,000 Java characters | `400` before model calls |
| HTTP body over 40,000 bytes | `413`, including bodies without `Content-Length` |

The HTTP reader consumes at most 40,001 bytes to detect an oversized body.

See [application.properties](../src/main/resources/application.properties) for all settings and
[the diagrams](diagrams/README.md) for system views.
