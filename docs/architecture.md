# Architecture and behavior

[README](../README.md) · [Sessions](#session-guarantees-and-replacement) · [Search rules](#search-and-retrieval-rules) · [Adapters](#database-selection-and-replacement) · [Models](#chat-models)

This document owns the request pipeline, conversation rules, and adapter guarantees.
For setup and commands, start with the [README](../README.md).

## Request pipeline

1. `RequestGateway` accepts text plus user/session IDs. `InputParser` returns a `UserIntent` through
   exact-command matching or model extraction. Exact commands never load session context; only the AI
   path receives the bounded parser context.
   This context keeps the last action and pending question separate from the retained recommendation
   page in `activeSearch.search`. Each displayed title includes its original rank, including on later
   pages. Help and single-book explanations do not replace the page supplied to the parser.
2. `SessionTransitionService` locks and loads the session. `IntentResolver` converts the stated
   intent into a `SessionRequest`, then `ConversationEngine` applies session rules, validates
   criteria, and requests a page only when needed.
   Both API endpoints pass raw text and `UserIntent` through this boundary; there is no gateway fallback
   that parses directly to an executable request. Gateway tests mock this same production signature.
3. `AudiobookRecommendationWorkflow` prepares query vectors; `AudiobookRetrievalPlanner` builds the
   mandatory exact filters and retrieval mode. `CandidateRetrievalService` queries the selected adapter.
4. `RankingService` combines available retrieval relevance, stored-user preference similarity, and
   universal rules such as recency/duration plausibility. Weights belong in
   [configuration](configuration.md#ranking-rules).
5. The session commits the completed outcome and active-search state together. Response generation then
   uses fixed wording or a separate chat client; the response model never selects the books.

Catalogue embedding, summary generation, migration and CSV imports run in separate maintenance
applications. Interactive requests only generate query embeddings when retrieval needs them.
See the [request sequence diagram](diagrams/recommendation-flow.svg) for execution order.

## Terminology

| Term | Meaning | Distinguish from |
| --- | --- | --- |
| Conversation policy | Rules governing how a request uses earlier criteria, preferences, recommendations, and history. | Intent routing or response wording |
| Conversation decision | Resolved conversational action, such as presenting recommendations, asking for clarification, or acknowledging an action. | Response text, intent, or “conversation outcome” |
| User intent | The parser's statement of what the user meant, before session rules interpret references or pending questions. | A final action or a persisted request |
| Session request | The executable request created from a user intent while the session is locked. | Parser output |
| Search context | Retained criteria and personalisation choice for the latest recommendation search. | The entire conversation or saved preferences |
| Active search | The current completed search, its bounded shown IDs, and its pending recommendation queue. | A conversation-wide history |
| Last outcome | The most recent completed turn and decision, used by repeat. | The active search |

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
ordinal, title or unique author. “The last one” selects the final reference choice; “the former”
and “the latter” select the first and last choices respectively. Partial title matches always need confirmation.
Adapters scan matching title pages while retaining bounded choices, returning exact titles when
present before falling back to partial titles. A truncated exact-match set never automatically
selects a reference. No match asks which aspects the user
liked; a taste reply keeps the original exact constraints and starts a normal semantic search.

The selected book must have a usable existing dense vector. Missing vectors produce a fixed response
and warning, asking for another title while preserving the search constraints, old search and cached
page. A bare replacement title resumes that search; “yes” first asks which title. No catalogue vectors
are generated or written at request time. Storage failures follow the normal retrieval-error contract. Title and vector lookup
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
explanation question takes priority over older title choices. Count changes abandon those choices. Title matching shares one
normaliser for case, punctuation and catalogue `$b` markers across reference and explanation flows.
Resuming a reference selection restores explicit search criteria and reapplies current preferences;
preference resets abandon unfinished searches rather than reviving inherited filters.
“None of these” asks for another reference title while retaining the search constraints.
“None of those, try The Hobbit” immediately looks up the replacement title with those constraints. Reference author
lookup and author replies use the same conservative person-name equivalence as exact filters.

Pending choices are stored once in their request snapshot; older snapshots with a duplicate choices
field remain readable.

### Outstanding questions

`Session.pendingQuestion` holds one typed question and its unfinished action's context. Question
subclasses cover search/duration/year clarifications, preferences, explanations and similar-book
selection. There is no suspended question or automatic resumption stack.
Completed history and every pending request use structured snapshots without raw user messages.
The current response still has access to its own raw input; stored criteria, references and choices
remain available when answering a question after a session reload.

HELP preserves the question and adds a reminder; REPEAT preserves its existing replay behaviour.
UNKNOWN keeps the question and says “I didn't quite catch that. Just to clarify, …” followed by the
current question and any choices. Contextual answers such as “horror”, “the second one” or “under three
hours” resolve the current question before ordinary intent dispatch.

Any other explicit action abandons the previous question and unfinished request. Preference edits,
count changes, resets, explanations and More cannot later revive old selections. The new action may
ask its own question. Completed searches, displayed books, saved preferences and cached results remain
available unless the action explicitly clears them.

Zero, negative and fractional recommendation counts immediately use the saved session count,
preserving the action and criteria. The response explains that counts must be whole numbers from
one to five. Invalid count settings leave the saved count unchanged and acknowledge its current value.
Only explicit valid count settings change the saved default; counts above five are capped at five
with a limit notice based on the resolved count, regardless of numeric or worded input. No count
question or suspended count request is created.
With no previous search, “2 more” asks for criteria and retains two through HELP/cache reloads.
“Yes” after a no-match or exhausted-results offer asks what to change without retrieving again;
the previous explicit criteria remain available until the user answers or starts another action.

Redis saves question subclasses with explicit type names. Only the current cache format is supported;
there is no legacy conversion or history-based recovery. Clear outdated development sessions or use
a new session ID after changing the cache schema.

`ConversationText` owns courtesy normalization and ordinal parsing, shared by CommandParser and
ClarificationResolver. The resolver owns session decisions; Session owns preference merging and
preference-question cleanup. For example, "please, the second
one" selects rank 2 after a question, while "could you explain number two, please" explicitly requests
an explanation. Titles and search criteria still use their original text.

### Runtime comparisons

“Make them longer” sets a minimum one minute beyond the longest displayed duration. If that
conflicts with an existing maximum, ask before extending the range. Refusal keeps the old search.
Missing durations ask for a limit. The active search question retains explicit criteria for duration
and year replies; replies reapply current session preferences.

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

Outstanding clarification questions and search-change offers are saved in session state, independently
of the latest response. HELP, REPEAT, UNKNOWN and cache reloads preserve the outstanding
question. Other explicit actions abandon the unfinished action. After no matches,
no more results or an empty explanation page, "yes" opens a search-change question and "no" declines
without removing preferences or history, including after those interruptions.

Duration replies share parsing with exact filters: "a couple of hours", "a hundred minutes",
"two thousand minutes" and "2 hours 30" retain the pending search and shorter/longer direction.
AI parsing also recognises these forms in complete requests. Vague limits such as "a few hours"
remain clarification cases.

A zero-match search still supplies context for `MORE_RESULTS`; clearing history removes it.
`MORE_RESULTS` restores saved search criteria. `REPEAT` replays the latest resolved request,
including explanations, help, clarifications, and empty results, without retrieving or applying
state changes again. Repeated REPEAT requests keep replaying the same original request.
`REPEAT_RECOMMENDATIONS` ("repeat the books", "show those recommendations again") instead
restores the retained search page, even after help or a single-book explanation. It leaves search
history, shown IDs and the pending queue untouched, while updating the latest replay snapshot so
"repeat that" afterward repeats that page. No page yields a fixed message; an empty latest MORE
repeats exhaustion rather than falling back to an older page. Clearing history removes the page.

A pending explanation-target question accepts “all of them” as the whole displayed page, including
after HELP or a cache reload. Outside that question, the reply remains context-dependent.

`Session.lastOutcome` stores one completed turn and decision for REPEAT. `Session.activeSearch`
stores the one search that can be refined, paged, or used as displayed-page context, together with
bounded shown IDs and pending recommendation snapshots. Raw input and final response text are not
stored. A repeated explanation uses the original selected books and criteria; AI wording may vary.
Clearing history removes the active search and replaces the repeat outcome with its acknowledgement.

Pending recommendations are catalogue snapshots inside `ActiveSearch`. Edits and removals affect
new batches; cached items are not rechecked on each page. The queue needs no Redis-specific keys or
additional storage-adapter operations.

### Session preference updates and search snapshots

The parser supplies `preferenceUpdates` and `preferenceRemovals`, both `SessionPreferences` values.
Likes add to the positive query; dislikes add to the negative query. Removing a like does not add a
dislike. “Avoid romance” supplies both positive removal and negative addition. Explicit replacements
name only the affected fields in `replacePreferenceFields`, for example `positiveSemanticQuery.genres`;
replacement without a new value clears that field. Other preferences remain unchanged.

An empty update creates a preference question. A short taste reply completes the update without
retrieving books. A new explicit action discards that question; there is no older question to restore.

`RESET_PREFERENCES` clears saved session preferences and the current question, and resets
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
“More recent” (including “I want books from more recent times”) is a `REFINE` that keeps
search content and sets an inclusive lower publication-year bound to the floored average of the
latest displayed page's known years. Unknown years are ignored; when every year is missing, it asks
for a starting year without consuming the previous search or queued books. A supplied starting year
resumes that search. A different refinement abandons the year question; an upper-bound reply such
as “before 2000” replaces the unfinished recency request. Any previous upper year bound remains in
force for a supplied starting year. If the start exceeds that bound, ask before extending the period:
“yes” replaces the old period with the proposed range, retaining any explicitly supplied end year;
“no” keeps the original search, and a different range replaces the
proposal. The confirmation survives HELP, REPEAT, UNKNOWN and cache reloads. An answer uses its saved
explicit criteria; preference updates, resets and count changes abandon the confirmation.
When the average would not advance the existing lower bound, advance it by one year instead. If
that exceeds the period's end, return a fixed exhaustion message without consuming the page or queue;
MORE and REPEAT_RECOMMENDATIONS remain available, while REPEAT repeats the exhaustion message.
“Older ones” mirrors the recency comparison with an upper bound one year below the floored average
of known displayed years. Keep the existing lower bound when compatible; otherwise ask before
extending the period. If all years are missing, ask for an ending year and retain the full refinement.

Explicit periods stay literal:
“1950 to the 2000s” means 1950–2009. A standalone decade, including “from the 2000s”,
means 2000–2009. Publication years must be within 1–9999; exclusive bounds outside that range
ask for a valid range before retrieval. Ordinary “recent books” still means the last five years.
Without an active search, a comparative refinement asks what kind of audiobook the user wants.
A context-dependent `REFINE` without a previous search asks for criteria without embedding or
retrieval; stored user preferences alone are not a previous search. A self-contained request can
still answer a pending preference question and save that taste for the session.

Search edits use `replaceSearchFields`, an allowlisted list of criteria paths, reusing the preference
field validation. “Make it horror instead” replaces `positiveSemanticQuery.genres`; “also mystery”
adds to the existing genres. “Make it more horror” followed by “make it more romance” adds both
genres to the existing positive semantic criteria. “More” does not assign a numerical weight.
Explicit “instead” or “replace” wording replaces the named field instead of adding to it.
A listed field with no value clears it: “any author is fine now” clears
`mustInclude.authors`, while “remove the time limit” clears `mustInclude.duration`. Omitted fields
still inherit previous values. These edits leave saved preferences unchanged. Override paths persist
with the search through cache reloads, MORE and later refinements, preventing saved preferences from
reintroducing a cleared filter or replaced genre. A new recommendation request starts fresh.

Each author/narrator field accepts at most one person, for exact filters, style criteria and saved
preferences. A newly supplied name replaces the previous choice, including “also”/“add” wording.
One author together with one narrator is supported. Multiple distinct names in the same field return
`400` asking the user to choose one, before retrieval or session commit; names are never truncated.
Equivalent catalogue spellings of the same person are deduplicated. Catalogue records retain their
existing multivalued author/narrator fields, and genres/topics/keywords still support multiple values.
For genres, topics and keywords in leading “also”/“add” instructions, supplied values remain additions even when another field is
removed in the same message; empty replacement fields still clear their filters. Explicit
“instead”/“replace” wording retains replacement behavior. Unrelated filters
and semantic criteria remain inherited for refinements.

Before queue invalidation or recommendation work, the planner checks exact include/exclude
constraints. Explicit new requirements override conflicting inherited exclusions, and vice versa.
Contradictions within the new message return `400` with a clarification question; the saved search,
queue, shown IDs and session version remain unchanged. A range conflicts only when its exclusion covers
the entire required interval. Semantic combinations, such as romantic horror, are not conflicts.
This checks logical impossibility, not whether the current catalogue happens to contain matches.

Author/narrator filters share `PersonNameNormalizer` in the catalogue model. Each database adapter
adds conservative OR variants at query time: `Suzanne Collins` also tries `Collins, Suzanne`, and
an explicit `García Márquez, Gabriel` also tries `Gabriel García Márquez`. Two-word forward names and dotted-initial prefixes with a single-word surname
(e.g. `J. K. Rowling` → `Rowling, J. K.`) are reversed; other longer forward names, suffix-bearing
names, and uncertain comma formats are not guessed. Include/exclude conflict checks use the same name-order comparison. Original request
and preference values remain unchanged, so MORE/REFINE reuse those names and regenerate variants.
Removing saved author/narrator preferences uses this same equivalence, including semantic preferences.
“Remove X from my preferences” removes matching saved values across positive and negative fields.
“I no longer like X” removes positive values only; it does not invent a dislike or alter the current batch.
This uses no dictionary, catalogue scan, AI call, new index, or migration. Generated alternatives
also tolerate a terminal catalogue period and compact/spaced dotted initials (`J.K.` / `J. K.`).
Separated single-letter initials (`J K`) match those dotted forms in both name orders; compact
undotted words (`JK`) are not assumed to be initials. This works for authors and narrators,
without expanding initials into full names or removing suffix punctuation. These alternatives
are not verified catalogue identities; typo matching, full-name aliases, arbitrary stored casing,
and complete discovery of longer-name variants remain outside this limited approach.

### Semantic criteria and personalisation

Author-only and narrator-only semantic criteria select semantic retrieval. “A book by Suzanne
Collins” sets `mustInclude.authors`; “books similar to Suzanne Collins style” sets
`positiveSemanticQuery.authors`, without an author filter or a similar-book title lookup. Narrator
requirements versus narrator-style similarity follow the same distinction. Exact names in
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
| Publication year | Four-digit year, comparisons, inclusive ranges, `around YYYY`, `period YYYY`, `recent`, `past year`, `last N years` | `after`/`before` are strict; bare year exact; relative windows include both boundary years |

The parser preserves relative publication phrases rather than guessing a calendar year. Retrieval
resolves them against the current UTC year: `recent`/`recently` means the last five years; `past year`
means one year; `last 4 years` and `last four years` mean four. Both include and exclude filters use
these rules. In 2026 these become 2021–2026, 2025–2026, and 2022–2026 respectively, excluding future
years. Zero, fractional, negative, and unrepresentably large windows return `400`.

Approximate phrases such as “around 2000” and “the period 2000” mean an inclusive ±5-year
range: 1995–2005. Bounds stay within calendar years 0–9999; a bare “2000” remains exact.

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
| `application.conversation` | Intent resolution, executable request/draft state, conversation engine and request/preference mapping |
| `application.conversation.workflow` | Dispatch, handlers, clarification, criteria merging, history and conversation rules |
| `application.recommendation` | Recommendation workflow and cached-page management |
| `domain.conversation` | Conversation actions, decisions, completed turns and explanation selectors |
| `domain.session` | Session aggregate, pending question, active search and last outcome |
| `domain.search` / `domain.recommendation` | Search criteria/value objects and recommendation limits/results |
| `inputparser` / `response` | Structured request parsing, raw-text/duration interpretation, and response wording |
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

`UserIntent` is the parser's small, untrusted statement of user meaning. Provider responses and
token usage stay inside the parser. `IntentResolver` converts it to a `SessionRequest` while the
session is locked; conversation policy then resolves pending questions, page references and search
availability. A `ConversationDraft` holds working state inside that transition. `SearchContext`
groups explicit and effective criteria with frozen reading-preference evidence. Completion creates a
`ConversationTurn` for response generation and session state; parser-only edit instructions are not
persisted in that turn. `LastOutcome` carries the completed turn and decision consumed by replay and
response generation.

Development sessions use only the current `Session` schema: `pendingQuestion`, `activeSearch`, and
`lastOutcome`. Reset Redis or use a new session ID when this schema changes.

### Input parsing

`InputParser` validates input, tries `CommandParser`, and falls back to `AiParser` when no exact
command matches. Both paths produce `UserIntent`; conversion to `SessionRequest` happens later,
inside the locked session transition. `AiParser` owns the model prompt, output schema, and provider
calls. The AI path receives only bounded reference context (the current pending question, latest
action and up to five displayed titles). Exact commands skip both the model and context load but
still use the normal session/conversation pipeline. The switch in
[`CommandParser`](../src/main/java/com/gen3/recommenderagent/inputparser/CommandParser.java)
is the authoritative alias list. It covers Help, More (including “next”), both repeat actions,
page explanations, clear history and cancellation.

Matching ignores case, extra whitespace, curly apostrophe differences, and boundary punctuation.
It removes supported polite wording such as “please”/“kindly”, opening greetings or “sorry”/“excuse me”,
and trailing “cheers”/“ta”/“many thanks”. The original input is preserved. Explicit numeric More commands such as “2 more”, “show me 2 more”
and “2 more audiobooks” preserve their count without calling AI. Settings commands such as
“only three at a time”, “3 per page”, “change count to three books”, and “4 books at a time” use
CHANGE_COUNT, including while a preference
question is pending; they do not edit saved tastes. Bare selections such as “1”,
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

Count-setting commands update the session without consuming the pending recommendation queue. An explicit count
on `MORE_RESULTS`, such as “2 more”, applies to that page without changing the saved default.
“More, make it three at a time” also uses a temporary count: each message executes one intent.
Use a separate CHANGE_COUNT request to save a new default.

`CANCEL` acknowledges cancellation and clears pending preference clarification without clearing
search history, preferences, or queued books. “Never mind” and “cancel” also end book-selection
questions. While a preference question is pending, clear declines such as “no”, “not now”,
“keep my preferences”, “nothing”, or “no changes” cancel it without changing saved preferences or
count. Declining a genre question abandons that unfinished search while preserving earlier results
and cached books. “Not now” and “leave it” use the CANCEL fast path alongside explicit cancellation commands.
Bare “no” declines any pending question; preference-specific declines remain contextual and skip AI.
Unrecognized replies no longer become personalised searches merely because a taste
question is pending.

Generic recommendation requests without criteria, such as “Recommend an audiobook” or “give me
some books”, use the
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

A personalised search with zero matches reports that the combined current search and saved
preferences found no matches. It does not blame a particular filter, echo only the latest message,
change preferences, or retry with relaxed constraints.

## Explaining recommendations

`EXPLAIN_RECOMMENDATION` carries optional `referenceRank` and `referenceTitle`, independent of
`bookCount` and semantic criteria. No selectors means the whole latest search page. A bare “explain”
automatically explains the sole book when the latest page contains one recommendation. Title matching
ignores case/punctuation and uses exact matches before unique partial matches. Missing, ambiguous,
out-of-range or conflicting references produce a ranked-title clarification. A bare ordinal is not
an explanation request on its own. While a book-selection clarification is pending, a numeric
reply such as “1”, “1.”, “first”, or “the first one” selects that rank from the retained page;
a numeric title such as “1984” matches its displayed book when it is not a valid rank.
“The last one” and “the latter” select the final displayed book; “the former” selects the first.
These relative positions also work in complete questions such as “Why the last book?”. Other invalid numbers ask again. An otherwise unrecognized reply can also identify a displayed title. HELP preserves this context; a new search replaces it. “Why the fourth book?” also resolves independently.
“Explain two more” and “explain book two more” both select book 2 for more detail, never two additional
books. Single-book explanations retain the original displayed rank.
`explanationSelection` carries literal include/exclude lists of ranks, relative positions or titles.
Empty includes mean the whole page. Multiple titles and “all except” selections use the same exact/
unique-partial matching; exclusions win, duplicates disappear, and displayed order/ranks remain intact.
An invalid or ambiguous selector asks for clarification while retaining other selections and exclusions.
Each bare reply resolves the next unresolved item. An explicit new explanation replaces that subset;
MORE/new searches clear it. Empty subsets ask which books to explain without calling AI. The existing
150-word total explanation limit also applies to subsets.

Model-provided ranks and counts accept large integers before conversion to bounded internal values:
oversized ranks clarify rather than overflow, and result counts retain the existing one-to-five cap.
Malformed model numeric fields and structured responses exceeding JSON limits return a friendly `400`
validation message. Invalid user recommendation counts use the saved session count rather than
rounding or interpreting zero as replay. Invalid settings preserve the saved default.

Bare title replies to a pending explanation question match the displayed page even when the parser
labels them RECOMMENDATION or REFINE. Explicit search commands still start a new search.

The resolved explanation copies saved criteria and selects from the page inside the session update.
It does not retrieve, embed, rank, consume the pending batch, add shown IDs or replace completed search
history. As an explicit action it abandons any unrelated pending question. No search yields a fixed onboarding message; an empty latest page
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
