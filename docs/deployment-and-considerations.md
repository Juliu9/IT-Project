# Deployment guidance and future work

[README](../README.md) · [Architecture](architecture.md) · [Configuration](configuration.md) · [Observability](observability.md)

This project is suitable for controlled demonstrations and further development. **Do not expose it
directly to the public internet without adding authentication, traffic controls, and production
operations.** The items below are guidance, not features already supplied by the repository.

## Before an internet deployment

| Priority | Area | Guidance |
| --- | --- | --- |
| Critical | Identity | Authenticate callers and derive the user ID from verified identity. Do not trust a caller-supplied `X-User-Id`. Authorize access to each session instead of treating `X-Session-Id` as proof of ownership. |
| Critical | Network and secrets | Terminate TLS at a trusted gateway, keep databases on private networks, use a secret manager, and disable the `dev` profile and demo endpoint. |
| Critical | Abuse and cost | Add per-user/IP rate limits, request-size limits, timeouts, and AI usage budgets. Provider retries and public access can otherwise multiply cost. |
| High | Availability | Run health/readiness checks for Redis, the selected catalogue, and required AI providers. Use bounded shutdown so in-flight requests can finish. |
| High | Data protection | Define retention and deletion rules for sessions, imported preferences, search-history tastes, logs, snapshots, and backups. Avoid logging request text, credentials, or personal reading history. |
| High | Catalogue quality | Replace the demonstration Qdrant catalogue's fake/placeholder narrator, language, and duration metadata with sourced values before production use. |
| High | Operations | Alert on error rate, latency, lock conflicts, provider failures, and resource saturation. Test restoration of Redis and catalogue backups, not only backup creation. |

The application should sit behind an API gateway or trusted identity-aware proxy. Authentication
belongs at that boundary, while the application must still validate the verified identity and bind
sessions to it. See [session guarantees](architecture.md#session-guarantees-and-replacement).

Author- and narrator-style requests are also a known retrieval limitation. Current vectors include
names and general descriptions, but no dedicated writing- or narration-style descriptions. A future
catalogue can add sourced style descriptions to searchable and embedded text, then benchmark those
queries separately from exact-name filters.

## Concurrency and scaling

The recommendation server can be replicated because durable session state is stored in Redis and
catalogue state is external. Redis locking serializes concurrent changes to the same
`(userId, sessionId)` pair; unrelated sessions can proceed independently.

### Likely bottlenecks

| Bottleneck | Why concurrency is limited | Typical symptom | First actions |
| --- | --- | --- | --- |
| **AI chat and embedding calls** | A retrieval request can call the parser model, query-embedding model, and response model. Provider quotas, latency, retries, and cost remain shared across every application instance. | Rising request latency, `429`/provider failures, retry amplification, or unexpected spend | Measure each AI stage; set account and application concurrency limits, timeouts, rate limits, and budgets; preserve fast paths that avoid AI calls |
| **Solr or Qdrant** | Search, filtering, vector retrieval, title lookup, and preference reads share database CPU, memory, indexes, and connections. Maintenance jobs can compete with live queries. | Slow retrieval, connection exhaustion, timeouts, or degraded search during jobs | Load-test representative hybrid/filter queries, monitor database saturation, tune indexes and connection limits, and schedule maintenance away from peak traffic |
| **Redis session locking** | Only one request may change a `(userId, sessionId)` at a time. The lease remains held through state commit and response generation, so a slow response-model call extends contention. | `409` responses for rapid requests in one conversation, lock-renewal activity, or `503` after lock loss | Prevent duplicate client submissions, measure lock duration/conflicts, keep Redis latency low, and do not weaken the locking contract merely to increase throughput |
| **Redis capacity and availability** | Every instance shares Redis for session reads, writes, locks, renewal, and expiry. Scaling only the application increases Redis traffic. | Broad session latency, failed commits, or failures across otherwise independent users | Use a production Redis service sized for peak operations, monitor latency/evictions/connections, and test failover while requests are active |
| **Application memory and CPU** | Parsing, ranking, serialization, and candidate handling consume per-request resources. Virtual threads reduce blocked-thread cost but do not reduce CPU, heap, or downstream usage. | Garbage-collection pauses, high heap use, CPU saturation, or pod/container restarts | Load-test with production result sizes, set memory limits from measurements, bound candidates and queues, and scale replicas only after locating the saturated stage |
| **Outbound connections and network** | AI, Redis, and catalogue calls all depend on finite sockets, connection pools, DNS, and network bandwidth. | Connection-acquisition delays, intermittent timeouts, or synchronized failures during traffic bursts | Set explicit connection and request limits, reuse clients, monitor pool/network metrics, and apply bounded backpressure |
| **Maintenance jobs** | Snapshot scans, embedding/summary writes, migration, and preference imports consume catalogue throughput; some also consume AI quota and substantial local memory/disk. | Slower live searches, provider throttling, database write pressure, or job failure from resource exhaustion | Run one writer at a time, isolate or schedule heavy jobs, cap job resources, and monitor live-service latency during execution |

Where the selected retrieval engine supports it, vector quantisation or a comparable compressed
representation can reduce RAM use and may make comparisons faster. This applies to capacity planning
for Qdrant, Solr, and future retrieval backends rather than being an application-level guarantee.
Benchmark recall, recommendation quality, memory, and latency with the chosen engine and catalogue
before selecting a representation. **Provision enough database RAM for the active indexes, vectors,
payload indexes, and working set:** memory pressure forces more disk reads and can turn a fast
retrieval into a multi-second request, including 10 seconds or more on an undersized or slow storage
deployment. Monitor memory and disk I/O, then size from measured peak traffic rather than catalogue
size alone.

**The first scaling target should be the measured bottleneck, not the application replica count.**
Adding replicas can increase pressure on AI providers, Redis, and the catalogue without increasing
end-to-end throughput.

### Potential use of decision and task models

Specialized models may reduce the AI bottleneck for narrow, structured work. For example,
[Jev](https://jev-ai.org/docs/) accepts application state and typed questions, then returns bounded
answers with probability distributions rather than conversational text. ScaleDown offers separate
[Classify](https://docs.scaledown.ai/api-reference/classify-overview),
[Extract](https://docs.scaledown.ai/api-reference/extract-overview), and
[Summarize](https://docs.scaledown.ai/api-reference/summarize-overview) task models: Classify chooses
among defined labels with scores, Extract returns requested fields, and Summarize produces a
requested text summary.

The best candidate in this application is the AI input-parser path: a decision/classification model
could select an intent, while an extraction model could populate structured search criteria. It
would **not** replace query embeddings, database retrieval, ranking, or the response model that writes
the final user-facing explanation. Exact command fast paths should remain local because a network AI
call cannot be faster or more reliable than deterministic parsing.

Treat lower latency as a hypothesis to benchmark, not a guaranteed property of a model category.
Before changing providers:

- compare p50, p95, and p99 latency, accuracy, failure rate, throughput limits, and cost against the
  current parser on representative and adversarial audiobook requests;
- define confidence thresholds and fall back to the existing parser when a result is uncertain,
  unsupported, or invalid;
- validate every returned choice and extracted field against the existing `UserIntent` contract;
- pin model versions where possible and log the exact version so behavior changes are traceable;
- review data handling, regional hosting, retention, and availability before sending user requests;
- keep the existing response model for natural-language answers, and measure the extra complexity of
  operating multiple providers against any latency or cost improvement.

**Do not route directly from an unvalidated probability.** Model scores need calibration tests for
this workload, and low-confidence or conflicting results must have a safe fallback.

Before scaling horizontally:

- Use one shared, highly available Redis deployment for every application instance. **Do not use an
  in-memory session replacement unless it provides the same cross-instance locking, checked commit,
  lock renewal, and expiry guarantees.**
- Keep instances stateless and route requests through a load balancer. Sticky sessions are not
  required when all instances use the same Redis and catalogue services.
- Size concurrency against the slowest dependencies: AI provider limits, database connection limits,
  request latency, and Redis lock duration. More application instances cannot remove those limits.
- Load-test new sessions separately from concurrent requests to one session. The latter are
  intentionally serialized and may return `409` while the session is locked.
- Apply backpressure with bounded queues and concurrency limits. Avoid allowing an outage in an
  external provider to create an unlimited request backlog.
- Scale Redis, Solr/Qdrant, and the application independently, then verify latency and failure
  behavior under dependency restarts and partial outages.

Maintenance jobs are not horizontally scalable workers. **Run one maintenance writer at a time
against a stable catalogue.** For large scheduled workloads, add coordination, resumable checkpoints,
and idempotent partitions before attempting parallel execution. See
[maintenance safety](maintenance.md#before-running-a-job).

## Suggested product improvements

| Priority | Suggestion | Current limitation | Likely change areas |
| --- | --- | --- | --- |
| High | Fuzzy names and titles | Small spelling differences such as `Suzanne Collins` and `Suzane Collins` do not match reliably. | Parser validation, person normalization, title lookup, database indexes/queries, ambiguity prompts, and behavior tests |
| High | More reliable hard filters | Initials, punctuation, ordering, aliases, and transliteration can represent the same author or narrator. The current conservative normalization cannot cover every identity variation. | Store canonical contributor IDs or aliases during ingestion; resolve input to an identity before applying exact filters |
| Medium | Multiple authors and narrators in session preferences | Each positive, negative, required, or excluded preference currently accepts at most one author and one narrator. Users cannot save requests such as “I like books by either author A or author B” or exclude several narrators. | Define whether multiple names mean `any` or `all`; update `SessionPreferences`, parser validation, exact-filter planning, database queries, conflict handling, response wording, limits, and behavior tests |
| Medium | Navigate recommendation history | The current repeat action replays the retained current page, not any earlier recommendation page. | Session history model, previous/next intent handling, bounded retention, conversation handlers, and tests |
| Medium | Explain recommendation details | There is no `DETAILS` intent for users to ask what catalogue payload information is available for a recommendation, such as author, narrator, language, duration, publication date, source, or description. | Intent parsing, recommendation selection by rank/title, safe payload-field mapping, response generation, missing-data wording, and behavior tests |
| Medium | Acknowledge slow requests after five seconds | The current HTTP endpoint returns one synchronous final response, so a slow AI or database call can leave the user without visible feedback. | Add a client-side five-second timer and a transport that supports progress separately from the final result, such as an asynchronous request plus polling, Server-Sent Events, or WebSocket messages. Cancel the timer when the final response arrives and do not present the acknowledgement as completion. |
| Medium | User-controlled data deletion | Users need a clear way to remove their sessions, imported preference profile, and search-history taste. | Authenticated API, session repository, user-preference repository, search-history repository (`delete`), audit behavior, and retention documentation |
| Medium | Resilience controls | Provider retries exist, but production traffic also needs circuit breakers, concurrency limits, and explicit overload responses. | AI clients, catalogue adapters, API error mapping, configuration, and telemetry |
| Medium | Deployment packaging | Compose supports local development, not a production rollout. | Production container image, runtime configuration, health probes, deployment manifests, and release documentation |
| Low | Recommendation feedback | There is no explicit like/dislike feedback loop for improving later recommendations. | API contract, session/preferences domain, persistence, ranking, privacy rules, and evaluation |

Fuzzy matching should produce candidates and ask for clarification when uncertain; it should not
silently turn a hard author or narrator filter into a different person. Canonical contributor IDs
are safer than continually expanding punctuation and initials heuristics.

Recommendation history should be bounded by page count or age. Store structured recommendation
snapshots rather than generated response prose, and define whether revisiting a page changes the
active search or merely displays historical results.

## Validation before launch

At minimum, verify:

1. one user cannot read, alter, or replay another user's session;
2. rate limits and AI budgets work across all application instances;
3. instance termination does not corrupt session state;
4. Redis or catalogue interruption produces bounded failures and recovers cleanly;
5. logs and telemetry contain no credentials, raw request text, or private preference data;
6. backups can be restored and the documented rollback procedure works;
7. peak-load tests cover both many independent sessions and contention on one session.

Production readiness depends on the hosting environment, expected traffic, availability target,
data sensitivity, and budget. Record those decisions before selecting replica counts or service
tiers; there is no universally correct production size.
