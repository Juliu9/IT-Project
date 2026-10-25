# Architecture and behavior

[README](../README.md) · [Sessions](#session-guarantees-and-replacement) · [Search rules](#search-and-retrieval-rules) · [Adapters](#database-selection-and-replacement) · [Models](#chat-models)

This document owns the request pipeline, conversation rules, and adapter guarantees.
For setup and commands, start with the [README](../README.md).

## Request pipeline

1. `RequestGateway` accepts text plus user/session IDs. `InputParser` returns a structured
   `SessionRequest` through exact-command matching or model extraction.
2. `SessionTransitionService` loads the session under its lock. `ConversationEngine` resolves the
   action through `ConversationDispatcher`, validates criteria, and requests a page only when needed.
3. `AudiobookRecommendationWorkflow` prepares query vectors; `AudiobookRetrievalPlanner` builds the
   mandatory exact filters and retrieval mode. `CandidateRetrievalService` queries the selected adapter.
4. `RankingService` combines available retrieval relevance, stored-user preference similarity, and
   universal rules such as recency/duration plausibility. Weights belong in
   [configuration](configuration.md#ranking-rules).
5. The session commits the completed turn, queue and shown IDs together. Response generation then
   uses fixed wording or a separate chat client; the response model never selects the books.

Catalogue embedding, summary generation, migration and CSV imports run in separate maintenance
applications. Interactive requests only generate query embeddings when retrieval needs them.
See the [request sequence diagram](diagrams/recommendation-flow.svg) for execution order.

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
Response generation runs in the store's `afterCommit` callback while the same session lease remains
held and renewed. Replacement adapters must keep excluding same-session updates until this callback
finishes, including on failure. Generation cannot roll back committed history; neither callback is
retried automatically. Other sessions are independent. `sessionLockMs` includes response generation;
`recommendMs` and `responseMs` are nested within it and must not be added to it.

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

### Books similar to a reference

"Books similar to The Hunger Games" remains `RECOMMENDATION`. The parser extracts `similarToTitle`
and an optional `similarToAuthor`; this author identifies the reference and does not restrict the
recommended authors. Extra tastes remain semantic criteria, and exact constraints remain filters.
Explicit requests for a whole series or multiple references prompt for one individual book.
A single named title goes to catalogue lookup even when it is also the name of a series.

`SimilarBookResolver` uses the configured `AudiobookTitleLookup` adapter before replacing the active
search. A unique exact title match (ignoring case/punctuation) selects the reference. Its author can
narrow editions; otherwise the user chooses from at most five title/author matches using a number,
ordinal, title or unique author. Partial title matches always need confirmation. Lookups are bounded;
a truncated result set never automatically selects a reference. No match asks which aspects the user
liked; a taste reply keeps the original exact constraints and starts a normal semantic search.

The selected book must have a usable existing dense vector. Missing vectors produce a fixed response
and warning, preserving the old search and cached page; no catalogue vectors are generated or written
at request time. Storage failures follow the normal retrieval-error contract. Title and vector lookup
are shared ports implemented by both Solr and Qdrant, without database types in conversation policy.

The reference vector is combined with additional positive/negative semantic vectors using the normal
vector math. The reference ID and shown IDs are excluded before candidate selection. Saved exact
preferences still apply; saved semantic tastes/user vectors participate only with explicit
personalisation. Retrieval uses dense similarity with the exact filters, followed by normal ranking.

The session saves the original reference vector and effective query vector. Full cached More pages
skip retrieval; a refill reuses the effective vector without title lookup or query embedding. Refine
keeps the reference and rebuilds from its original vector, avoiding repeated blending. A new standalone
search replaces the reference. Explain and Repeat retain the resolved reference as search evidence.
Pending title clarification is separate from the displayed page; Cancel/Clear history remove it.
`ClarificationResolver` routes replies to the current question before catalogue access, so a later
explanation or count question takes priority over older title choices. Title matching shares one
normaliser for case, punctuation and catalogue `$b` markers across reference and explanation flows.
Pending choices are stored once in their request snapshot; older snapshots with a duplicate choices
field remain readable.

### Outstanding questions

`Session.pendingQuestion` tracks an unanswered question independently of the latest response used
by REPEAT. HELP preserves it and repeats the question after the help text; REPEAT leaves it alone.
Count changes preserve book-selection and genre questions, including when a missing count needs its
own clarification: `Session.suspendedQuestion` restores the original question after the count answer.
Preference updates preserve unrelated questions. Both direct semantic updates and answers to an update
question can supply the missing genres and resume the waiting personalised search.
New recommendations/refinements replace the pending selection; MORE and REPEAT_RECOMMENDATIONS clear
selection questions when showing a page. While genres are still required, MORE (including counted
variants) repeats the genre question without embedding, retrieving, consuming cached books, or
saving inherited criteria as preferences. CANCEL/CLEAR_HISTORY clear the question; RESET_PREFERENCES
clears preference questions while preserving unrelated book selections. Abandoning a genre question
discards its unfinished search; any earlier completed search and cached batch remain available.

`ConversationText` owns courtesy normalization and ordinal parsing, shared by CommandParser and
ClarificationResolver. The resolver owns session decisions; Session owns preference merging and
preference-question cleanup. For example, "please, the second
one" selects rank 2 after a question, while "could you explain number two, please" explicitly requests
an explanation. Titles and search criteria still use their original text.

### Shorter follow-ups

"Make them shorter" is a `REFINE` command, not a saved-preference edit. It skips AI, preserves the
active search's content and other constraints, and uses `shorter=true` without inventing a numeric
limit. With no previous search (including cleared/expired/new sessions), it asks what content the
user wants and performs no retrieval, even when saved preferences exist.

Recommendations retain their catalogue `durationMinutes` through ranking, paging and session storage.
When every displayed duration is known and greater than one minute, a shorter refinement sets the
new duration filter strictly below the shortest displayed book. The resolved limit is retained for
More; session preferences are unchanged. If durations are missing, the page is empty, or the shortest
book is already one minute, it asks for a duration limit and preserves the old page/batch. A numeric
limit reply refines that search. Older session snapshots without durations remain readable and use
this clarification path. Explicit duration requests still use their stated bounds.

### Context and cached results

Retrieval excludes recently shown IDs before selecting up to 50 candidates. Ranking removes
remaining duplicates and shown books, retains up to 20 recommendations, and serves at most five
according to the requested count. Queued items retain ID, title, short description, score and
reading-preference evidence, so full cached pages skip preference lookup, embedding, retrieval,
and ranking. Parsing still runs (possibly through the fast path); response generation follows.

MORE keeps the cached tail and refills when needed. Short pages are valid; no remaining matches
produce an explicit exhaustion message. Changed search criteria invalidate the batch. Count changes
and Help preserve it; preference-update behaviour is specified below. The session retains at most
500 shown IDs, so older books can eventually reappear. Clear history removes searches, shown IDs,
pending recommendations and clarification state, while preserving preferences and result count.

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

### Session preference updates and search snapshots

The parser supplies `preferenceUpdates` and `preferenceRemovals`, both `SessionPreferences` values.
Likes add to the positive query; dislikes add to the negative query. Removing a like does not add a
dislike. “Avoid romance” supplies both positive removal and negative addition. Explicit replacements
name only the affected fields in `replacePreferenceFields`, for example `positiveSemanticQuery.genres`;
replacement without a new value clears that field. Other preferences remain unchanged.

An empty update asks what to change and sets `Session.awaitingPreferenceUpdate`. A short taste reply
completes the update without retrieving books. Explicit search commands (`explicitSearch=true`) start
normal recommendation work instead; cancel and clear-history end the pending update. Numeric
answers to a newer book-selection or count question take priority over an older preference question.

`RESET_PREFERENCES` clears saved session preferences and pending preference questions, and resets
the result count to 5. It preserves stored user vectors, shown-book history, the displayed page,
and the remaining cached batch. MORE uses the reset count; REPEAT_RECOMMENDATIONS retains the old
page. A cache refill for MORE continues with that search's original criteria and stored-vector
snapshot even after preferences are reset; it does not apply the new-search personalisation gate.
Replies to a personalised taste question (for example "horror") are saved in SessionPreferences
when the pending search resumes, so later personalised requests reuse them. Only the reply's semantic
preferences are saved; the original search's author, duration and other one-off constraints remain
search-specific. Exact preferences are saved through explicit preference updates.

Successful updates change only session preferences and the replay acknowledgement. They preserve the
latest displayed page, shown IDs, search history and pending batch. Both repeat modes remain usable.
New `RECOMMENDATION`/`REFINE` requests combine explicit `searchCriteria` with the latest preferences.
The resolved search keeps its effective criteria for `MORE_RESULTS`; refills do not merge today's
session preferences. `userPreferenceSnapshot` and `userPreferencesLoaded` also freeze stored reading
vectors (including their absence) for retrieval and ranking. New searches/refinements reload them.

Older sessions without explicit criteria metadata retain their saved resolved criteria as the fallback;
the original explicit/preference split cannot be reconstructed. Missing vector snapshots load once on
the first refill. New searches record the full snapshot metadata; no database migration is required.

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
`mustInclude` and `mustNotInclude` use separate `ExactCriteria` instances and remain mandatory
database filters.

For personalised retrieval with stored taste:

1. Normalize the stored-taste signal and give it equal weight to the current positive semantic vector.
2. Subtract the negative semantic vector and normalize the combined result.
3. Apply the ranker's separately configured scoring weights to retrieved candidates.

If either positive source is absent, the other contributes at full strength. Exact filters are
never relaxed to fill the result count.

A first personalised request without stored user vectors or saved session preferences asks
"What genres do you like?", even if that request includes semantic criteria or exact constraints.
It does not embed or retrieve candidates until the user answers. The answer supplies taste and
resumes the retained search. With saved preferences, positive or negative semantic taste is
still required when no user vectors exist.
Exact constraints remain mandatory filters and are never embedded as a substitute for taste. Short
answers to the taste question merge with the pending search and retain its count; explicit searches
abandon it. Corrections and contradictions use the existing exact-filter policy before retrieval.

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
| `application` | Session transaction boundary and shared chat-client wiring |
| `application.conversation` | Conversation engine and handler composition |
| `application.recommendation` | Recommendation workflow and cached-page management |
| `domain` | Intents and recommendation limits |
| `domain.conversation` | Intent dispatch, handlers, clarification, criteria merging and history policy |
| `domain.session` | Input, working-draft, search-context, completed-turn and session models |
| `inputparser` / `response` | Structured request parsing and response wording |
| `embedding` | QueryEmbeddingService, positive/negative query vectors |
| `retrieval` / `retrieval.plan` | Candidate search execution, retrieval plans and filter parsing |
| `ranker` / `ranker.scorer` | Candidate ranking and scoring rules |
| `storage.user` | Reading-preference storage contracts, models and database adapters |
| `ai` | Provider-error translation for live parsing, embedding, and response calls |
| `storage.session` | Session contract, commit errors and Redis adapter |
| `observability` | Request timing and AI usage telemetry |

Vector lookup ports expose reads only. Embedding writes belong to `AudiobookEmbeddingStore`;
migration uses its separate existing-data copy operation.

Shared audiobook code lives in sibling `com.gen3.catalogue`: `model`, `port`, `solr`,
`qdrant`, and `vector`. It has no dependency on `recommenderagent`. The server scans both
packages; maintenance launchers explicitly import the adapters they need. This is a package
boundary within the existing Maven module, with unchanged database and configuration formats.

`ConversationResponseGenerator` handles fixed decision messages and delegates recommendation
and explanation narration to `AiResponseGenerator`. The AI implementation owns prompts, output
validation, and built-in narration fallbacks. `ResponseGenerator` remains the API-facing contract.
Known provider failures are translated by the live AI adapters into `AiProviderException`:
parser/query-embedding failures return the existing 503 message, while response failures use the
built-in fallback. Unexpected programming exceptions propagate as server errors. Maintenance
jobs retain their independent retry and recovery policies.

The sibling maintenance packages remain separate runnable jobs. CSV interaction records
(`UserFavourite` and `UserHistory`) belong to `userpreferencejob.model`. Catalogue embedding
generation remains in `embeddingjob`; the query embedding module only embeds live search criteria.
`RecommendationPolicy` owns the existing limits and result-count clamping rules.

## Conversation policy

| Component | Owns |
| --- | --- |
| `SessionTransitionService.process` | The single `SessionRepository.update` load, transition, and commit boundary |
| `ConversationEngine` | Resolve, validate, retrieve when needed, then complete the turn inside that boundary |
| `ConversationDispatcher` | Validated handler dispatch and shared completion ordering |
| `ClarificationResolver` | Short numeric/title replies, pending-question priority, and cancellation routing |
| `SearchContextResolver` | Search inheritance, current preferences for new searches/refinements, and frozen preferences for MORE |
| Preference, explanation, replay, and session-action handlers | Their own action rules; no independent saves or locks |
| `ExactFilterPolicy` | Exact-filter parsing, inherited corrections and conflict validation |
| `RecommendationPager` | Cached pages, refill, duplicate exclusion and displayed ranks |
| `ConversationHistory` | Retained searches, metadata and replay snapshots |
| `ConversationResponseGenerator` / `AiResponseGenerator` | Fixed wording and AI narration after the session commits |

The parser returns `SessionRequest`, which contains incoming intent, criteria and edit instructions.
Provider responses and token usage stay inside the parser. A `ConversationDraft` holds working state
inside the session transition. `SearchContext` groups explicit and effective criteria with frozen
reading-preference evidence. Completion creates a `ConversationTurn` for response generation and
session history; parser-only edit instructions are not persisted in that turn.

Redis retains the existing flat search-field names. Old snapshots with parser-only fields remain
readable; those fields are ignored. Decisions remain outside serialized turns, in
`Session.lastDecision`, so existing replay snapshots continue to work without flushing sessions.
The response generator consumes the resolved decision; direct callers without one use the shared
`ConversationRules` classifier.

### Input parsing

`InputParser` validates input, tries `CommandParser`, and falls back to `AiParser` when no exact
command matches. It converts either result into a session request and logs the total parsing time.
`AiParser` owns the model prompt, output schema, and provider calls. Exact commands skip the model
but still use the normal session/conversation pipeline. The switch in
[`CommandParser`](../src/main/java/com/gen3/recommenderagent/inputparser/CommandParser.java)
is the authoritative alias list. It covers Help, More (including “next”), both repeat actions,
page explanations, clear history and cancellation.

Matching ignores case, extra whitespace, curly apostrophe differences, and boundary punctuation.
It removes supported polite wording such as “please”/“kindly”, opening greetings or “sorry”/“excuse me”,
and trailing “cheers”/“ta”/“many thanks”. The original input is preserved. Explicit numeric More commands such as “2 more”, “show me 2 more”
and “2 more audiobooks” preserve their count without calling AI. Bare selections such as “1”,
“one” and “the second audiobook” also skip AI as UNKNOWN; pending session context resolves their
meaning. Without a pending question they stay UNKNOWN. Negations and extra criteria still go through the model; “2 more but shorter” and “more British authors” are not
reduced to a plain More command.

The parser schema and response vocabulary remain explicit contracts rather than dynamically
loadable plugins. The model's JSON requires only `intent`; other fields, including nested
criteria, may be omitted. Parser guidance asks for compact output without null/empty values or false
flags; Java supplies their existing defaults. This is converter-based format guidance, not native
strict Structured Outputs. Standalone taste replies supply search criteria once; pending preference
updates derive their edits through `SessionPreferences.fromRequest`. Explicit preference commands
retain their addition/removal/replacement fields.

### Adding an intent

1. Add its parser contract and normalization rules, including whether it can introduce search criteria.
2. Implement or extend a `ConversationHandler` family and register its bean in
   `ConversationConfiguration`. The dispatcher rejects duplicate or missing intent registrations.
3. Keep action rules in that handler and reuse the filter, search, paging and history modules.
   Handlers mutate the supplied session; the transaction service alone loads and commits it.
4. Add behavior tests for fresh sessions, follow-ups and replay. Update response wording as needed.

Simple actions share `SessionActionHandler`; a new intent does not require a new class by default.

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
`EXPLAIN_RECOMMENDATION`, `UPDATE_PREFERENCES`, `RESET_PREFERENCES`, `CLEAR_HISTORY`, `HELP`, `CANCEL`, and `UNKNOWN`.

Only `RECOMMENDATION` and `REFINE` accept newly parsed positive/negative semantic queries or the
personalised flag. Other intents discard these fields. `MORE_RESULTS`, `REPEAT_RECOMMENDATIONS`, and
`EXPLAIN_RECOMMENDATION` inherit saved search context; `REPEAT` restores its retained request context; semantic search changes use `REFINE`.
`UPDATE_PREFERENCES` carries semantic and exact changes through separate `SessionPreferences` values;
its search-semantic fields remain empty. Standalone criteria route to `RECOMMENDATION`; context can
resolve them as an answer to a pending preference question.

## Explaining recommendations

`EXPLAIN_RECOMMENDATION` carries optional `referenceRank` and `referenceTitle`, independent of
`bookCount` and semantic criteria. No selectors means the whole latest search page. A bare “explain”
automatically explains the sole book when the latest page contains one recommendation. Title matching
ignores case/punctuation and uses exact matches before unique partial matches. Missing, ambiguous,
out-of-range or conflicting references produce a ranked-title clarification. A bare ordinal is not
an explanation request on its own. While a book-selection clarification is pending, a numeric
reply such as “1”, “1.”, “first”, or “the first one” selects that rank from the retained page;
invalid numbers ask again. An otherwise unrecognized reply can also identify a displayed title. HELP preserves this context; a new search replaces it. “Why the fourth book?” also resolves independently.
“Explain two more” and “explain book two more” both select book 2 for more detail, never two additional
books. Single-book explanations retain the original displayed rank.
Model-provided ranks and counts accept large integers before conversion to bounded internal values:
oversized ranks clarify rather than overflow, and result counts retain the existing one-to-five cap.
Fractional numeric fields and structured responses exceeding JSON limits return a friendly `400`
validation message rather than truncating a position or returning a server error.

Bare title replies to a pending explanation question match the displayed page even when the parser
labels them RECOMMENDATION or REFINE. Explicit search commands still start a new search.

The resolved explanation copies saved criteria and selects from the page inside the session update.
It does not retrieve, embed, rank, consume the pending batch, add shown IDs, replace history, or clear
pending preference clarification. No search yields a fixed onboarding message; an empty latest page
yields a distinct fixed message, even if an older page had results. The session represents committed
recommendations, not confirmation that a client displayed or spoke them.

Each retained/queued `Recommendation.readingPreferencesUsed` records whether stored preference
vectors contributed to personalised retrieval or a positive active preference ranking score. It is
copied when batch items receive page ranks; old session JSON defaults to false. Each scorer returns
its evidence in `ScoreResult`; the composite combines available contributions, and ranking reuses
that result without detecting scorer classes or scoring selected books again. Explanations never
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
Before reusing an older data volume, check server compatibility and the vendor’s upgrade instructions.

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
