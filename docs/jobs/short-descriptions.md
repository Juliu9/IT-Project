# Short descriptions

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Provide concise, spoiler-free descriptions that recommendation responses can reuse without
summarising full catalogue descriptions during every user request. Without this job, summaries
would need to be supplied by the catalogue provider, written manually, or generated elsewhere.

By default, find books missing `short_description`, generate summaries through regular OpenAI
Chat Completions calls, and save each completed request directly to the selected database.
Existing summaries are preserved by default; explicit `rebuild-all` replaces them.
Book vectors and other payload fields are preserved in both modes.

## Prerequisites

- Java 21 and an existing populated catalogue with usable book IDs and source `description` text.
- Select `AUDIOBOOK_DATABASE=solr` or `qdrant`, with read/write access to that catalogue.
- Solr needs a stored, single-valued `short_description` field, such as type `string`, and `_version_`.
- Configure `OPENAI_API_KEY` and database connection settings. The `dev` profile optionally loads
  the repository-root `.env` file.
- No Redis, embedding model, local vector snapshot, or running recommendation server is required.

## Selection and generation

- Only records with a usable book ID, storage reference, and nonblank source `description` qualify.
  Existing nonblank summaries are skipped in `missing-only` mode. The default run cap is
  100,000 eligible books in either mode.
- In missing-only mode, Qdrant uses its native empty-payload filter (missing/null/empty values);
  a whitespace-only string is not considered empty by that filter. Solr also treats whitespace-only
  summaries as missing.

The job groups up to **ten books per regular request**, with **five requests in flight** by default.
It uses `gpt-4o-mini` unless `OPENAI_SHORT_DESCRIPTION_MODEL` overrides it. Each summary is limited
to 20 words. All IDs and summary values in each response are validated before that group's writes.

## Run generation

Start small to check quality and spend:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_ENABLED=true \
AUDIOBOOK_SHORT_DESCRIPTION_MAXIMUM_RECORDS=1000 \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

For Qdrant, use the same command with `AUDIOBOOK_DATABASE=qdrant`.
To reduce rate-limit pressure, set `AUDIOBOOK_SHORT_DESCRIPTION_CONCURRENCY=1`.
Keep the short-description enable flag scoped to this command: the recommendation server and
other maintenance launchers reject it.

There is one workflow: **select eligible books → generate → save**.
There are no submit/import/recover modes, JSONL uploads, pending manifests, or provider batch IDs.
In the default missing-only mode, run again to fill the next set of missing summaries.
There is no dry-run mode:
**running this command incurs regular API charges and writes summaries into the selected database.**

Previously submitted OpenAI batches are not cancelled by this change. If one is still running,
reconcile it before generating the same books again to avoid duplicate charges.

## Regenerate existing descriptions

Set `AUDIOBOOK_SHORT_DESCRIPTION_MODE=rebuild-all` explicitly to select both missing and existing
summaries and replace them with newly generated text:

```bash
AUDIOBOOK_DATABASE=qdrant AUDIOBOOK_SHORT_DESCRIPTION_ENABLED=true \
AUDIOBOOK_SHORT_DESCRIPTION_MODE=rebuild-all \
AUDIOBOOK_SHORT_DESCRIPTION_MAXIMUM_RECORDS=300000 \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

The example permits up to 300,000 eligible books; choose a cap large enough for your catalogue
to regenerate it all. Books without usable source descriptions are skipped in either mode.
The cap counts eligible books, not all scanned rows.

**Rebuild-all overwrites existing summaries and incurs fresh generation charges.**
It saves incrementally, not as one atomic catalogue replacement. After a failure, previously saved
replacements remain; rerunning rebuild-all starts selection again and can regenerate/charge for
those books again. There is no resume checkpoint. Do not run concurrent summary writers during a
rebuild. Solr still uses version-checked updates, while Qdrant updates only the selected points'
`short_description` payload field.

Omit the mode variable (or set `missing-only`) to retain the safe default.
Invalid mode values fail startup before database clients are created.

## Progress and completion

While running, the job prints `maintenance.progress` immediately and every ten seconds, including
its current stage, processed count, total (once selection finishes), elapsed time, and time since
the last progress change. Selection can report an unknown total.

After each completed request is saved, it logs:

```text
short.description.progress processed=10 total=1000
```

Counts refer to books processed for writing; a concurrent writer may already have filled a summary,
in which case that record is safely skipped in missing-only mode. Rebuild-all deliberately replaces it. Other requests may still be generating during a write.
A heartbeat proves the reporter is alive, not that a pending remote call is advancing.
No separate OpenAI batch-progress command is needed.

Successful completion logs `Completed short-description generation for ... records`.
With no eligible books, `No audiobook descriptions were available ...` is a successful no-op;
there are no generation calls or description writes.
Check the process exit status and `BUILD SUCCESS` when using Maven.

## Failures and retries

- HTTP 429 and server errors receive up to three attempts with short exponential backoff.
  Regular requests have a two-minute timeout; connection setup has a 30-second timeout.
- Authentication errors, malformed/duplicate JSON, missing or unexpected IDs, blank/non-text
  summaries, refusals, and truncated generation fail the run rather than silently skipping books.
- Each completed request is saved before more work is accepted. On failure, queued/in-flight work
  is cancelled locally and the process fails. Cancellation does not guarantee that OpenAI stops
  processing a request it has already received.
- Previously saved summaries remain. Rerunning missing-only selects only remaining missing summaries;
  rerunning rebuild-all regenerates selected books again.
  Responses generated but not saved can be charged again on a later run; there is no local result cache.
- Solr uses version-checked updates in both modes; Qdrant uses conditional missing-only payload
  updates by default and point-targeted overwrite updates for rebuild-all. A write failure fails the run.
- Regular summary generation may compete with interactive recommendations for the same model's
  rate limits. Reduce concurrency or run the job outside demo hours if necessary.

## Implementation

- `ShortDescriptionEnricher` owns eligibility checks, ten-book grouping, bounded concurrency,
  response-ID validation, the word limit, incremental saves, and progress.
- `ShortDescriptionGenerator` receives catalogue records and returns summaries keyed by book ID;
  database-specific references never cross this boundary.
- `OpenAiShortDescriptionGenerator` calls `/v1/chat/completions`; it never uploads files or submits batches.
- `AudiobookEnrichmentStore` applies the selected mode to both reads and writes, updating only
  the summary field.

