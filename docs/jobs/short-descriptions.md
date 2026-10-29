# Short descriptions

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

**Purpose:** Generate spoiler-free summaries from existing `description` fields and write only
missing `short_description` values. Existing summaries and book vectors are preserved.
The job uses one selected database, no Redis, and no embedding generation.

## Prerequisites and selection

- Select `AUDIOBOOK_DATABASE=solr` or `qdrant`; a complete recommendation backend is not required.
- Solr needs a stored, single-valued `short_description` field, such as type `string`, and `_version_`.
- Keep the output directory through submission and import. It defaults to `data/short-descriptions`;
  override it with `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_OUTPUT_DIRECTORY`.
- Limits count usable records with IDs and descriptions. Invalid rows are skipped while scanning
  continues; duplicate book IDs within a submission are rejected before upload.

OpenAI Batch is the default provider. It groups ten books per request, uses `gpt-4o-mini` unless
`OPENAI_BATCH_MODEL` overrides it, and requests summaries of at most 20 words. The shared job
also enforces that word limit. The default submission maximum is 100,000 eligible records.

## 1. Submit a batch

Start small to check quality and spend:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=submit \
AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MAXIMUM_RECORDS=1000 \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

The pending file, `short-description-batch.json`, records the batch ID, provider, dataset identity,
and original database references. Another submission is blocked until import or explicit recovery.
Manifest updates are atomic, so failed replacements preserve previous recovery data.

## 2. Import results

Use the **same provider, backend, endpoint, collection, and output directory**:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=import \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

Import polls pending generation every 30 seconds, logging completed, failed, and total provider
requests; a request can contain multiple books. HTTP requests allow 15 minutes, including large
uploads/downloads. After roughly 24 hours waiting for generation, import stops and retains the
manifest for a later attempt.

Solr reads and writes in batches of at most 500 with version checks; Qdrant applies conditional
missing-only updates. Import logs processed and total update counts after each batch, including
records skipped because they already have summaries. Successful import removes the pending file.
Repeat submission/import for the remaining catalogue.

## Failures and retries

| Situation | Action |
| --- | --- |
| Input upload fails | Retry submission; no generation batch or pending manifest was created. |
| Submission outcome is unknown | Keep the manifest, reconcile the batch ID with the provider, and set `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID` before import. |
| Invalid or conflicting summary output | Import refuses all writes and retains the manifest. Inspect the provider output; retrying unchanged invalid output fails again. |
| Import times out or a database write fails | Retry import with the retained manifest; already-written summaries are preserved. |
| Solr version conflict | Retry import; each write checks the current record version. |
| Provider or dataset mismatch | Restore the original configuration; import refuses to write. |
| Batch failed, expired, or was cancelled | Use the recovery command below. |

## Recover a failed, expired, or cancelled batch

Use the original provider, dataset, and output directory:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=recover \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

Recovery verifies `failed`, `expired`, or `cancelled` status with the provider. Active, completed,
unknown, or unreachable batches leave the pending file intact. An uncertain submission without a
recorded ID requires a reconciled `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID`; an existing ID cannot be
replaced with another.

Successful recovery atomically archives the original file as
`short-description-batch-recovered-<uuid>.json`, logs the verified ID, and permits resubmission.
It neither imports partial results nor changes database records. **Resubmitting partial results
may incur generation charges again.** Run recovery separately from submission/import.

## Replace the summary provider or database

Implementation lives in `com.gen3.shortdescriptionjob`:

- `AudiobookEnrichmentStore` reads descriptions and writes summaries through the selected database.
- `ShortDescriptionBatchProvider` handles provider transport. `upload` prepares input without
  starting generation; `submit` starts it; `completedDescriptions` returns summaries by book ID.
- The shared job owns manifests, dataset validation, database references, and import rules.
- Register a conditional provider adapter and set `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_PROVIDER`.
  Implement `verifyTerminalFailure` for recovery; its default refuses unsupported recovery.
- Do not switch providers with a batch pending. Unsupported manifest formats require manual reconciliation; do not delete recovery evidence.

## Confirm completion

- Submit: `Submitted short-description batch ...`; a retained `short-description-batch.json`
  means generation/import is still outstanding, not that summaries have been saved.
  With no eligible records, `No audiobook descriptions were available ...` is a successful no-op
  and does not create a pending manifest.
- Import: `Imported ... generated short audiobook descriptions`; the pending manifest is removed.
- Recover: `Recovered ...` with the failed batch ID and an archived manifest. Recovery does not
  write summaries or automatically submit another batch.

Check `BUILD SUCCESS` and a zero exit status for the selected mode. No dry-run mode is implemented.
Submission/upload can contact the provider and submission incurs generation charges. Preserve the
output directory for recovery; it contains input/result text and should be treated as local data.

