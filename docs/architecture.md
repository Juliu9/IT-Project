# Architecture and behavior

[README](../README.md) · [Configuration](configuration.md) · [Diagrams](diagrams/README.md) · [Job runbooks](jobs/README.md)

This document describes the system's durable boundaries and runtime guarantees. Setup, commands,
and property defaults live in the linked operational documents. Phrase-level conversation examples
belong in behavior tests rather than this overview.

## System at a glance

The repository contains one interactive recommendation server and six independently launched
maintenance jobs. They share catalogue models and database adapters but have separate entry points.

```text
HTTP request
  → parse intent
  → lock and load session
  → resolve conversation action
  → retrieve and rank candidates when needed
  → commit session
  → generate response text

Maintenance launchers
  → embeddings | snapshots | short descriptions | migration | user preferences | read history
```

The server supports either Qdrant or Solr. Redis stores interactive sessions. Maintenance jobs do
not require Redis and run separately from the HTTP server.

## Request pipeline

1. `RequestGateway` validates the text and opaque user/session IDs.
2. `InputParser` uses an exact-command fast path or the parser model to produce a `UserIntent`.
3. `SessionTransitionService` locks and loads the session. `IntentResolver` converts the intent into
   an executable `SessionRequest`; `ConversationEngine` applies conversation rules.
4. When books are required, `AudiobookRecommendationWorkflow` prepares the query vector,
   `AudiobookRetrievalPlanner` creates exact filters and a retrieval plan, and the selected database
   adapter retrieves candidates.
5. `RankingService` combines available retrieval relevance, stored reading-preference similarity,
   and universal ranking rules.
6. The completed turn, active search, and queue changes commit together. Response generation then
   uses fixed wording or the response model. The response model never selects books.

Interactive requests generate query embeddings only when retrieval needs them. Catalogue embedding,
snapshot export, summary generation, migration, and CSV imports are maintenance operations. See the
[request sequence diagram](diagrams/recommendation-flow.svg).

## Core terms

| Term | Meaning |
| --- | --- |
| User intent | The parser's untrusted description of what the user meant |
| Session request | The executable action produced while the session is locked |
| Search context | The criteria and personalisation state for one recommendation search |
| Active search | The current search, displayed page, shown IDs, and queued recommendations |
| Pending question | One unfinished clarification with the context needed to resume it |
| Last outcome | The latest completed turn used by `REPEAT` |

## Session guarantees and replacement

Every change for a `(userId, sessionId)` pair goes through one `SessionRepository.update`. A session
adapter must provide:

- cross-instance exclusion for concurrent updates;
- a single-attempt commit with version/ownership validation;
- a 24-hour sliding expiry;
- atomic updates to shown IDs, queue consumption, active search, and completed outcome;
- lock renewal while long-running work and post-commit response generation continue.

Response generation runs after the state commit while the same session lease remains held. **A
response failure cannot roll back committed history and is not retried automatically.** Other sessions
remain independent. Redis uses separate lock and session keys; replacement stores must provide the
same behavior, not merely `get` and `set` operations.

Set `SESSION_STORE` to a registered replacement adapter. Selecting a non-Redis store disables the
Redis adapter and its Boot infrastructure.

| Failure | HTTP result |
| --- | --- |
| Session is already locked | `409` |
| Lock is lost or commit is rejected | `503` |
| Parser or query-embedding provider fails after retries | `503` with a retry message |
| Catalogue retrieval is unavailable | `503` with a retry message |
| Response-model failure | Committed result with built-in wording |

## Search and retrieval rules

### Retrieval and filtering

The planner chooses semantic, keyword, or hybrid retrieval. Exact author, narrator, language,
source, duration, and publication-year requirements remain mandatory database filters; they are
never relaxed to fill a page. Semantic preferences influence vectors and ranking instead.

Duration accepts hours/minutes and inclusive or strict ranges. Publication constraints use years;
relative expressions are resolved against the current UTC year. Catalogue publication metadata is
`pubdate`, while `publicationYear` is only a search criterion. Invalid or contradictory exact ranges
return `400` before retrieval and do not mutate the saved search.

Author and narrator filters accept one person per field. A conservative normalizer supports common
forward/reversed forms and initials without attempting fuzzy identity matching. Include/exclude
conflicts use the same comparison rules.

**Author- and narrator-style searches have limited accuracy.** The catalogue vectors contain names
and ordinary book descriptions, not dedicated descriptions of an author's writing style or a
narrator's performance style. Requests such as “in the style of J. K. Rowling” therefore use a name
as a semantic hint and may return weak or inconsistent matches. Improving this requires sourced
style descriptions to be stored and embedded as searchable catalogue data.

### Books similar to a reference

Similar-book requests first resolve one catalogue title through `AudiobookTitleLookup`. A unique
exact match is selected; ambiguous or partial matches ask the user to choose from at most five
title/author options. The reference author disambiguates editions but does not filter recommended
authors.

The selected book must already have a usable dense vector. **Request-time code never generates or
writes catalogue vectors.** The reference vector can be combined with positive and negative semantic
criteria, while exact constraints remain filters. The reference book and previously shown books are
excluded from results.

The session retains the original reference vector and effective query vector. Cached pages and
refills reuse them without repeating title lookup or embedding. A new standalone search replaces the
reference; clarification and cancellation follow the normal pending-question rules.

### Semantic query fields

Semantic queries use `genres`, `topics`, `authors`, and `narrators`. Topics retain complete
phrases describing subject matter, themes, moods, settings, plots, and character traits, such as
“strong female protagonist” or “documentary-style account of penguins”. The separate `keywords`
preference field has been removed; these descriptions now belong in `topics` for both positive
and negative preferences. Sparse keyword retrieval and its catalogue vector name are separate
from preference fields.

### Personalisation

Personalised retrieval combines the current positive semantic vector with the stored user-taste
vector, subtracts negative semantic evidence, and normalizes the result. Ranking then applies its
configured signal weights. If only one positive source exists, it contributes at full strength.

A first personalised request with neither stored vectors nor saved session preferences asks what the
user likes before retrieval. Exact constraints alone do not substitute for positive taste. Stored
user vectors and session preferences are frozen in the search snapshot so `MORE_RESULTS` remains
consistent; new searches and refinements load current preferences again.

Saved session preferences are bounded before an update commits: each value is at most 256 Java
characters, each semantic field holds at most 10 values, the whole preference set holds at most 100
values, and its JSON representation is at most 32 KiB. **An oversized update is rejected atomically;
existing preferences are neither truncated nor partially changed.**

### Paging, refinements, and replay

Retrieval excludes recently shown IDs and returns candidates for ranking. Ranking removes duplicates,
keeps a bounded queue, and serves one to five books. A full cached page skips preference lookup,
embedding, retrieval, and ranking. The session retains at most 500 shown IDs.

| Action | Behavior |
| --- | --- |
| `MORE_RESULTS` | Consumes the cached queue and refills with the frozen search when needed |
| `REFINE` | Inherits the active search and changes only the requested criteria |
| `REPEAT` | Replays the last completed action without applying it again |
| `REPEAT_RECOMMENDATIONS` | Replays the retained recommendation page without consuming the queue |
| `CLEAR_HISTORY` | Removes active search, shown IDs, queue, and clarification state; keeps preferences |
| `EXPLAIN_PREFERENCES` | Reports saved preferences and count; preserves search and pending questions |
| `RESET_PREFERENCES` | Clears session preferences and resets the page count; keeps search history |

Comparative refinements such as shorter, longer, newer, and older derive a boundary from the latest
displayed page. If required metadata is missing or a new range would conflict with an existing bound,
the system asks for clarification without consuming the page or queue. Explicit search-field
replacement can clear or replace a named inherited field; ordinary additions preserve other fields.

### Viewing session preferences

`EXPLAIN_PREFERENCES` reports saved session preferences and the current recommendation count
using a fixed template without AI narration or retrieval. Empty fields are skipped; an empty
preference set is stated explicitly. Reports stay within 150 words, preserve whole saved values,
and end with “Additional preferences omitted.” when necessary. This read-only action preserves
pending clarifications and the active recommendation batch. `REPEAT` replays the saved report
snapshot. The semantic category maximum is 10; the existing one-author/one-narrator rule
also applies. Existing session data is not migrated.

### Pending questions

The session stores at most one typed pending question. Short replies such as a number, title, taste,
or duration resolve that question before ordinary intent dispatch. `HELP`, `EXPLAIN_PREFERENCES`,
`REPEAT`, `UNKNOWN`, and cache reloads preserve it. Another explicit action abandons it and may create a new question; there
is no hidden question stack.

Pending and completed state use structured snapshots, not raw user messages or final response text.
Clear outdated development sessions or use a new session ID after changing the session schema.

## Conversation policy

`UserIntent` is parser output, not an executable command. `IntentResolver` and the registered
conversation handlers apply session context, pending-question priority, exact-filter validation,
and history rules inside the session transaction.

Supported intents are:

| Search | Navigation and replay | Preferences and session | Other |
| --- | --- | --- | --- |
| `RECOMMENDATION` | `MORE_RESULTS` | `UPDATE_PREFERENCES` | `EXPLAIN_RECOMMENDATION` |
| `REFINE` | `REPEAT` | `EXPLAIN_PREFERENCES` | Reports saved preferences and count; preserves search and pending questions |
| `RESET_PREFERENCES` | `HELP` |
|  | `REPEAT_RECOMMENDATIONS` | `CHANGE_COUNT` | `CANCEL` |
|  |  | `CLEAR_HISTORY` | `UNKNOWN` |

Only recommendation and refinement intents introduce new search semantics. Navigation and
explanation actions inherit committed context. Preference edits use their own addition, removal, and
replacement fields instead of masquerading as searches.

### Input parsing

`CommandParser` handles exact commands such as Help, More, repeat, page explanations, count changes,
history clearing, and cancellation. It ignores harmless casing, spacing, punctuation, and supported
courtesy wording. Commands with extra criteria go to `AiParser` instead of losing information.

The AI parser receives bounded context: the current pending question, latest action, and at most five
displayed titles. Its structured response is validated and converted to `UserIntent`; session state
is not changed during parsing. The authoritative fast-path aliases live in
[`CommandParser`](../src/main/java/com/gen3/recommenderagent/inputparser/CommandParser.java).

To add an intent:

1. extend the parser contract and validation;
2. implement or extend a conversation handler and register it;
3. reuse existing search, filter, paging, and history modules;
4. add fresh-session, follow-up, failure, and replay tests.

### Explaining recommendations

Explanations operate only on the retained displayed page. They do not retrieve, embed, rank, consume
the queue, or mark more books as shown. A request may select the whole page or books by rank, title,
or relative position. Missing or ambiguous selectors create a clarification question.

The response may mention stored reading preferences only when the selected recommendation records
that those vectors influenced retrieval or ranking. It must not infer specific favourite genres or
books from that flag. Explanation output is limited to 150 words.

Recommendation and explanation narration must mention every supplied title. If model output is
blank, fails, exceeds the explanation limit, or omits a title, built-in wording is used. This local
check verifies title coverage, not factual accuracy.

## Code organization

### Recommendation server packages

| Package | Responsibility |
| --- | --- |
| `api` | HTTP boundary and errors |
| `application` / `application.conversation` | Session transaction, intent resolution, and conversation rules |
| `application.recommendation` | Recommendation workflow and cached-page management |
| `domain.*` | Conversation, session, search, and recommendation state |
| `inputparser` / `response` | Intent parsing and response wording |
| `embedding` / `retrieval` / `ranker` | Query vectors, candidate retrieval, and scoring |
| `storage.user` / `storage.session` | User-preference and session contracts/adapters |
| `ai` / `observability` | Provider error translation, timing, and usage telemetry |

Shared catalogue code lives under `com.gen3.shared.audiobookcatalogue` and does not depend on the
recommendation server. It owns audiobook models, database ports/adapters, and local snapshots;
shared vector mathematics lives under `com.gen3.shared.vector`.

### Maintenance modules

| Package | Responsibility |
| --- | --- |
| `embeddingjob` | Generate and store catalogue vectors, then refresh the snapshot |
| `snapshotjob` | Export existing vectors to a validated local file |
| `shortdescriptionjob` | Generate and store missing or rebuilt summaries |
| `migrationjob` | Copy supported Solr catalogue data to Qdrant |
| `userpreferencejob` | Stream CSV interactions and aggregate user vectors |
| `shared.readhistory` | Bounded history contracts under `port`, PostgreSQL adapter/configuration under `postgres` |
| `shared.csv` | Reusable CSV readers, normalization and input records under `model` |
| `readhistoryjob` | Independent additive CSV history importer and launcher |
| `shared.jobruntime` | Periodic progress reporting and per-run log naming |

Jobs reuse catalogue adapters but have dedicated launchers and safety flags. The snapshot is a
derived cache, not a backup or change feed. See the [job runbooks](jobs/README.md) for writes,
prerequisites, and recovery.

### Read-book eligibility

`shared.readhistory.port.ReadHistoryRepository` is a replaceable bounded read contract. Its PostgreSQL adapter
uses an indexed user/book table and an explicit connection pool; schema setup is manual. The
independent importer reuses CSV normalization and adds pairs without altering preference vectors
or book payloads. Starting a book counts as read; exclusion matches exact catalogue IDs only.

Shared CSV readers and their input records live under `shared.csv`, so neither import job depends
on the other job's implementation. The recommendation application explicitly imports the shared
PostgreSQL configuration; it does not component-scan every shared helper or start import jobs.

When configured, `ReadHistoryExclusions` loads one current-user snapshot in `RecommendationPager`.
`ConversationDraft` holds those IDs for this request only; stored turns/sessions omit them. The
workflow merges them into retrieval/ranking exclusions and the pager checks cached/refilled books.
Repeat/explain preserve their original selection without history lookup. Solr uses term-set
exclusions rather than a large OR query; every retrieval mode shares the same filter.

Lookup failures and histories over the configured limit log a structured warning and fail open;
session shown-ID exclusions still apply. No unread-only guarantee applies in those cases. Successful
lookups never relax exclusions to fill a page. See [read history](jobs/read-history.md) for operations.

## Technology replacement guide

Difficulty describes the contract and data-migration risk, not merely the number of changed lines:

| Level | Meaning |
| --- | --- |
| Low | Configuration change with no stored-data migration |
| Medium | One adapter boundary plus focused tests |
| High | Distributed-state or stored-data contracts must be preserved or migrated |
| Very high | Cross-cutting framework and runtime change |

| Change | Difficulty | Starting points | Why / required work |
| --- | --- | --- | --- |
| Change an OpenAI chat model | Low | [`application.properties`](../src/main/resources/application.properties), [model configuration](configuration.md#ai-models-and-shared-vector-dimensions) | Change the role-specific model and verify structured parser output, narration, usage reporting, and fallbacks. No data migration. |
| Replace the chat provider | Medium | [`ChatClientConfiguration`](../src/main/java/com/gen3/recommenderagent/application/ChatClientConfiguration.java), `inputparser`, `response`, `ai` | Supply both role-specific clients and preserve parser schema, retries, provider-error translation, usage telemetry, title coverage, and response fallbacks. |
| Change the embedding model or dimensions | High | [`application.properties`](../src/main/resources/application.properties), `embeddingjob`, database vector schemas, `snapshotjob`, `userpreferencejob` | **Requires compatible schemas, a complete catalogue reindex, a new snapshot, and regenerated user vectors.** A property-only change can mix incompatible vectors. |
| Replace the short-description provider | Medium | [`ShortDescriptionGenerator`](../src/main/java/com/gen3/shortdescriptionjob/provider/ShortDescriptionGenerator.java), `shortdescriptionjob.provider`, provider tests | Preserve ten-book grouping, bounded concurrency, ID/response validation, word limits, retries, timeouts, and incremental writes. |
| Add a catalogue database | High | [`shared.audiobookcatalogue.port`](../src/main/java/com/gen3/shared/audiobookcatalogue/port), backend configuration, `storage.user`, maintenance store interfaces, integration tests | Implement search modes, exact filters, title/details/vector lookup, identity and write guarantees, user preferences, and the maintenance operations the backend supports. Document schema and migration. |
| Replace Redis session storage | High | [`SessionRepository`](../src/main/java/com/gen3/recommenderagent/storage/session/SessionRepository.java), `storage.session`, session configuration and concurrency tests | Preserve distributed exclusion, lease renewal, versioned atomic commit, sliding expiry, error mapping, and the post-commit response callback. A basic key/value adapter is insufficient. |
| Upgrade Solr, Qdrant, or Redis | Medium–High | [`compose.yaml`](../compose.yaml), [`pom.xml`](../pom.xml), schemas, container integration tests, [version table](configuration.md#supported-and-tested-versions) | Verify client/server compatibility and existing stored data. Major upgrades may require schema or data migration and a rollback backup. |
| Replace Alloy/Grafana | Low–Medium | [`compose.observability.yaml`](../compose.observability.yaml), [`observability/`](../observability), [observability guide](observability.md) | Keep application telemetry unchanged when the replacement can consume the same structured logs; otherwise update the export allowlist and field documentation. |
| Replace Spring Boot | Very high | [`pom.xml`](../pom.xml), application launchers, configuration, HTTP, dependency injection, data and test infrastructure | Cross-cutting rewrite with no dedicated replacement seam. Treat it as a platform migration. |

Before a replacement, record the current behavior, data format, rollback point, and integration tests.
Classify it as either an **implementation swap** behind an existing interface or a **data-contract
change** involving identity, schema, vectors, or sessions. The latter requires an explicit migration
and rollback plan even when the code diff is small.

## Configuration and schema

The [configuration reference](configuration.md) owns property defaults, supported/tested versions,
provider connections, model selection, schemas, and maintenance opt-ins.

Qdrant and Solr implement the same runtime catalogue and user-preference ports. Their storage
schemas differ: Qdrant uses named dense/sparse vectors and payload indexes; Solr uses configured
fields and blends lexical with dense scores. Both normalize candidate vectors for preference
ranking. Schema files under [`solr/`](../solr/) are manual external-collection definitions, not
runtime resources loaded by the application.

## Database selection and replacement

### Backend selection

`AUDIOBOOK_DATABASE` selects one backend for a server or job; Qdrant is the default. Both backends
provide candidate search, title/details lookup, exact book-vector lookup, and user-preference storage.
**Selecting a backend does not create, copy, or migrate existing data.**

Server and client versions are listed in
[Supported and tested versions](configuration.md#supported-and-tested-versions). Older Qdrant
flattened vector responses are not supported.

### Add a database adapter

1. Register implementations of `AudiobookCandidateSearch`, `AudiobookVectorRepository`,
   `AudiobookTitleLookup`, `AudiobookDetailsLookup`, and `UserPreferenceVectorRepository` under one
   backend selection.
2. Preserve semantic, keyword, hybrid, filtered search, identity, and write guarantees. Report an
   unsupported operation explicitly.
3. For snapshot generation and the embedding job's post-pass refresh, register a
   `CatalogueSnapshotSource` implementing read-only vector pagination and catalogue identity.
   Configure `<backend>.collection` alongside `audiobook.database=<backend>`.
   `SnapshotIdentity` resolves these settings for both publishing and offline preference imports;
   startup rejects a source whose backend or collection differs from that identity.
4. Implement the maintenance ports needed by the backend: `AudiobookEmbeddingStore` for embedding
   writes and `AudiobookEnrichmentStore` for short-description updates. Register adapters in the
   relevant launcher's composition without adding backend-specific branches to consumers.
5. Add configuration, schema documentation, and integration coverage. Verify snapshot pagination,
   catalogue IDs and vectors, and publish/reopen compatibility through the offline reader. Include
   launcher checks that built-in backend beans are absent for a replacement backend and that an
   identity mismatch fails startup.

Missing implementations fail startup rather than falling back to another backend. Conversation and
ranking code must remain independent of database-specific types.

An arbitrary backend name and `<backend>.collection` can identify an existing local snapshot for
offline loading. They do not provide database access: generation requires a registered
`CatalogueSnapshotSource`. Snapshot paths remain
`<snapshot-directory>/<backend>/<collection-hash>.bin`; preserve this identity and the stored
metadata when replacing an adapter so existing snapshots remain readable.

### Solr schema and record identity

Solr uses the catalogue's unique string `id` and a separate user-preference collection keyed by the
exact external user ID. Reads never create collections or schemas. Import saves replace mapped
vector sides, preserve absent or unmapped sides, and retain unrelated fields through atomic updates.
Stored vectors must be finite, nonzero, and match the configured dimension.

Solr is wired only when selected, except when it is the explicit migration source. See
[Solr configuration](configuration.md#solr) for fields, schemas, collections, and connections.

### Qdrant schema and record identity

Qdrant schema names and filter translation live inside its adapter. Reads require an existing
collection; explicit repository writes may create one, but the embedding job requires one already.
Existing collections are not automatically inspected or migrated.

The payload `id` is the catalogue identity regardless of the Qdrant point ID. Each payload ID must
match exactly one point. Lookup and writes reject duplicates rather than selecting, merging, or
deleting one automatically. Existing points keep their point IDs; new books use deterministic UUIDs,
and collisions are rejected.

Metadata and vector-only reads request only the fields they need and paginate bounded batches. Keep
the `id` keyword index on strict-mode collections. Maintenance operations still require a stable
catalogue and one writer; adapter checks cannot prevent races with external writers.

## Chat models

The parser and response generator use separate clients:

| Role | Override | Replacement bean |
| --- | --- | --- |
| Input parser | `INPUT_PARSER_CHAT_MODEL` | `inputParserChatClient` |
| Response generator | `RESPONSE_GENERATOR_CHAT_MODEL` | `responseGeneratorChatClient` |

Blank overrides inherit the provider default. Replacing one bean changes only that role and does not
change providers automatically. See [model settings](configuration.md#ai-models-and-shared-vector-dimensions).

Parser and query-embedding failures return a provider-unavailable response. Response-model failures
use built-in wording and preserve the committed session.

## Request limits

| Input | Result |
| --- | --- |
| Missing or blank text | `400`; no model or session work |
| Decoded text over 10,000 Java characters | `400` before model calls |
| HTTP body over 40,000 bytes | `413` |
| User or session ID blank or over 256 Java characters | `400` before parsing/session access |

See the [behavior tests](../src/test/java/com/gen3/recommenderagent/) for executable edge-case
coverage and the [diagrams](diagrams/README.md) for visual system views.
