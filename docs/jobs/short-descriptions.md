# Short descriptions

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Generate concise, spoiler-free catalogue descriptions for recommendation responses.

The default fills missing `short_description` values through regular OpenAI calls. `rebuild-all`
replaces existing summaries. Other catalogue fields and vectors are preserved.

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

The workflow is **select → generate → save**; it does not use the Batch API or manifests. Run
missing-only again to continue with remaining books. **There is no dry-run: this command incurs API
charges and writes to the selected database.**

If a legacy OpenAI batch is still running, reconcile it first to avoid duplicate charges.

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

The cap counts eligible books, not scanned rows; unusable source descriptions are skipped.

**Rebuild-all overwrites existing summaries and incurs fresh generation charges.**
It saves incrementally, not as one atomic catalogue replacement. After a failure, previously saved
replacements remain; rerunning rebuild-all starts selection again and can regenerate/charge for
those books again. There is no resume checkpoint. Do not run concurrent summary writers during a
rebuild. Solr still uses version-checked updates, while Qdrant updates only the selected points'
`short_description` payload field.

Omit the mode variable (or set `missing-only`) to retain the safe default.
Invalid mode values fail startup before database clients are created.

## Progress and completion

The shared `maintenance.progress` heartbeat reports stage, counts, elapsed time, and time since
progress. Selection can have an unknown total.

After each completed request is saved, it logs:

```text
short.description.progress processed=10 total=1000
```

Counts advance after completed writes. Missing-only safely skips a summary filled concurrently;
rebuild-all replaces it. Other requests may still be in flight.

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

Implementation is isolated behind `ShortDescriptionGenerator` and `AudiobookEnrichmentStore`;
database references do not cross the generator boundary.

