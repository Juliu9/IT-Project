# Maintenance jobs

[README](../README.md) · [Embeddings](#catalogue-embeddings) · [Migration](#solr-to-qdrant-migration) · [Summaries](#short-descriptions) · [Preferences](#user-preferences)

These are separate, non-web processes. **Normal server startup rejects enabled maintenance flags.**
Run one job at a time against a stable catalogue, with one writer. Configure credentials and
connections in the shell or IDE. AI generation incurs provider charges.
Solr scans continue after the last processed ID, so earlier insertions/deletions do not shift later
pages. This is not a frozen snapshot: records inserted behind that ID require another run. Keep the
catalogue stable when a job must cover one exact dataset.

## Automatic progress reporting

Maintenance jobs print `maintenance.progress` immediately and every 10 seconds while running.
This covers snapshot export/validation/loading, embedding generation, catalogue migration,
user-import preparation/aggregation, and short-description submit/import/recovery.

```text
maintenance.progress job=book.snapshot.rebuild stage=reading catalogue and writing snapshot processed=12500 total=unknown elapsedMs=60000 sinceProgressMs=1500
```

`stage` identifies current work or I/O, `processed` is the last completed counter update for that
stage, and `sinceProgressMs` is the time since the stage or counter changed. Snapshot counts advance
per export page (or every 1,000 vectors during local validation/loading). User aggregation reports
users processed and a known user total. CSV preparation updates counts every 10,000 rows;
embedding/migration counters advance after completed pages. Short-description database imports and
provider polling retain their existing detailed progress events.

`total=unknown` means no exact total is available; limits are not presented as totals and no ETA is
invented. A heartbeat proves the reporter is alive, not that a pending remote operation is advancing.
An increasing `sinceProgressMs` during a read, write, or provider wait makes that lack of advancement
visible. It does not trigger retries, cancel work, query the database, or declare success.
The reporter stops automatically on success or failure. Use the job's existing completion event and
`BUILD SUCCESS` as confirmation of success. Already-running jobs must finish or be restarted before
they can use new reporting code; updating files does not instrument a running JVM.

Migration, snapshot rebuilds and preference import need no AI credentials. Their launchers, and the summary launcher,
disable unused Spring AI models; the summary provider handles its own transport and credentials.

| Job | Databases | AI generation | Changes |
| --- | --- | --- | --- |
| Catalogue embeddings | Solr or Qdrant | Embeddings | Existing records' vectors, then local snapshot |
| Book-vector snapshot | Solr or Qdrant | None | Local binary file only; database is read-only |
| Migration | Solr → Qdrant | None | Copies absent destination books |
| Short descriptions | Solr or Qdrant | Batch summaries | Missing short descriptions |
| User preferences | Solr or Qdrant | None | Aggregated user vectors |

Set `AUDIOBOOK_DATABASE=solr` for the recommendation server, embedding generation, all summary modes,
and preference import. Supply the [Solr collections and schema](configuration.md#solr); only the
recommendation server also needs Redis. Migration is specifically Solr **to Qdrant** and keeps
`audiobook.database=qdrant` for its destination, as shown below; it is not a Solr-only job.

## Catalogue embeddings

**Purpose:** Read existing books and regenerate their normalized dense vectors. Qdrant indexing
also builds sparse keyword vectors. The selected database supplies both reads and writes.

**Before running:** Supply OpenAI credentials and an existing collection with a stored Solr vector
field or Qdrant named-vector schema. Its dimensions must match `embedding.dimensions`.
Redis and user-preference adapters are not required.

Index up to 100 existing Solr records; replace `solr` with `qdrant` for that database:

```bash
AUDIOBOOK_EMBEDDING_INDEX_ENABLED=true AUDIOBOOK_DATABASE=solr \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.embeddingjob.EmbeddingIndexApplication \
  -Dspring-boot.run.arguments="--audiobook.embedding-index.maximum-records=100"
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `audiobook.embedding-index.page-size` | `100` | Records read per page |
| `audiobook.embedding-index.maximum-records` | `0` | Maximum records scanned; zero processes all |

- IDs, metadata, custom fields, and short descriptions are preserved. Blank records are skipped.
- An embedding failure prevents that page's writes; earlier pages remain saved.
- Solr embedding writes require the version read before generation. A deleted or edited record
  rejects the stale update instead of recreating a partial document or attaching an outdated vector.
  Earlier updates may already be saved; rerun against the current catalogue after resolving the conflict.
- Rerunning starts at the beginning and regenerates existing vectors, incurring charges again.
- After a successful pass, a separate full-catalogue snapshot export runs. A failed export leaves
  the previous snapshot intact and fails the launcher. Retry only the [snapshot rebuild](#build-or-refresh-the-local-snapshot)
  to avoid paying for embedding generation again. Changing models requires a complete compatible
  catalogue reindex before publishing; existing records have no stored model identifier to verify.
- The process closes its clients when finished. It cannot run alongside migration or summary generation.

Implementation lives in `com.gen3.embeddingjob`. Another database implements
`AudiobookEmbeddingStore`; shared catalogue records and persistence live in `com.gen3.catalogue`.
The old `solr.embedding-index-on-startup` and `solr.embedding-index-page-size` settings are replaced
by the settings above. Live search-query embeddings are independent of this job.

## Solr-to-Qdrant migration

**Purpose:** Copy supported existing catalogue fields, short descriptions, and stored dense vectors.
This is the only job connecting to two databases. Source vectors must match the destination dimensions.

```bash
QDRANT_MIGRATION_ENABLED=true ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.migrationjob.MigrationApplication \
  -Dspring-boot.run.arguments="--audiobook.database=qdrant"
```

- Existing destination books are skipped, preserving their summaries and vectors.
- No AI calls, vector normalization, or sparse keyword generation occur.
- Books without source vectors are copied without vectors. Run the embedding job separately to
  make them available to vector search.
- The record limit counts source rows read, including skipped destination books. Configure
  `audiobook.qdrant.migration.batch-size` and `audiobook.qdrant.migration.max-records`; zero maximum
  means the full catalogue.
- Keep the source stable and avoid other destination writers during migration.

Migration copies data only; use `AUDIOBOOK_DATABASE` to select the recommendation backend.

## Short descriptions

**Purpose:** Generate spoiler-free summaries from existing `description` fields and write only
missing `short_description` values. Existing summaries and book vectors are preserved.
The job uses one selected database, no Redis, and no embedding generation.

### Prerequisites and selection

- Select `AUDIOBOOK_DATABASE=solr` or `qdrant`; a complete recommendation backend is not required.
- Solr needs a stored, single-valued `short_description` field, such as type `string`, and `_version_`.
- Keep the output directory through submission and import. It defaults to `data/short-descriptions`;
  override it with `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_OUTPUT_DIRECTORY`.
- Limits count usable records with IDs and descriptions. Invalid rows are skipped while scanning
  continues; duplicate book IDs within a submission are rejected before upload.

OpenAI Batch is the default provider. It groups ten books per request, uses `gpt-4o-mini` unless
`OPENAI_BATCH_MODEL` overrides it, and requests summaries of at most 20 words. The shared job
also enforces that word limit. The default submission maximum is 100,000 eligible records.

### 1. Submit a batch

Start small to check quality and spend:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=submit \
AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MAXIMUM_RECORDS=1000 \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

The pending file, `short-description-batch.json`, records the batch ID, provider, dataset identity,
and original database references. Another submission is blocked until import or explicit recovery.
Manifest updates are atomic, so failed replacements preserve previous recovery data.

### 2. Import results

Use the **same provider, backend, endpoint, collection, and output directory**:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=import \
./mvnw spring-boot:run \
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

### Failures and retries

| Situation | Action |
| --- | --- |
| Input upload fails | Retry submission; no generation batch or pending manifest was created. |
| Submission outcome is unknown | Keep the manifest, reconcile the batch ID with the provider, and set `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID` before import. |
| Invalid or conflicting summary output | Import refuses all writes and retains the manifest. Inspect the provider output; retrying unchanged invalid output fails again. |
| Import times out or a database write fails | Retry import with the retained manifest; already-written summaries are preserved. |
| Solr version conflict | Retry import; each write checks the current record version. |
| Provider or dataset mismatch | Restore the original configuration; import refuses to write. |
| Legacy manifest lacks dataset identity | Recover explicitly using its original implementation/configuration; automatic conversion is refused. |
| Batch failed, expired, or was cancelled | Use the recovery command below. |

### Recover a failed, expired, or cancelled batch

Use the original provider, dataset, and output directory:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=recover \
./mvnw spring-boot:run \
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

### Replace the summary provider or database

Implementation lives in `com.gen3.shortdescriptionjob`:

- `AudiobookEnrichmentStore` reads descriptions and writes summaries through the selected database.
- `ShortDescriptionBatchProvider` handles provider transport. `upload` prepares input without
  starting generation; `submit` starts it; `completedDescriptions` returns summaries by book ID.
- The shared job owns manifests, dataset validation, database references, and import rules.
- Register a conditional provider adapter and set `AUDIOBOOK_SHORT_DESCRIPTION_BATCH_PROVIDER`.
  Implement `verifyTerminalFailure` for recovery; its default refuses unsupported recovery.
- Old manifests without a provider field belong to OpenAI. Do not switch providers with a batch pending.

## User preferences

Read favourites/history CSVs and aggregate existing book vectors into user vectors, without AI calls.
Both Solr and Qdrant supply the required adapters. Set `AUDIOBOOK_DATABASE=solr` or `qdrant`;
reads and writes stay within that database. For Solr, prepare the separate preference collection and
schema described in [Solr setup](configuration.md#solr). Solr writes at most 500 user updates per
batch and commits once per batch; earlier batches remain saved if a later batch fails.

The importer uses the last valid local book-vector snapshot, not remote catalogue lookups. It loads
and verifies that snapshot before reading CSVs or writing any profiles. There is no age-based expiry
and no fallback to remote reads. Each process pins its loaded snapshot even if a new one is published.
It groups at most 100,000 distinct book IDs by default, regardless of user count. Each user stays
whole. Profile saves and clears are batched
at 500 users. The book-ID limit is configurable under `user.preferences.import`
(see [configuration](configuration.md#user-preference-import)).
Before any profile writes, every user is checked against the book-ID limit. An oversized user
stops the job with their ID and limit; no history is truncated. CSVs are streamed into sorted binary
runs, merged on disk, and fully validated before profile writes. Unordered inputs are supported;
duplicate favourites collapse and duplicate history retains the latest interaction. Whole-user
batches target 100,000 rows (`user.preferences.import.max-records-in-memory`); a single larger
user remains whole, bounded by the distinct-book limit. Temporary files live under
`user.preferences.import.work-directory` (default `./data/user-import-work`) and are cleaned on
success or failure. Allow temporary disk space comparable to the input files; a killed process
may leave its uniquely named work directory behind. The full snapshot resides in RAM and is
reused across groups. At 1,536 dimensions,
200,000 vectors alone occupy about 1.14 GiB, before ID maps, CSVs, clients and runtime overhead.
The group limit does not bound this cache. Give the dedicated importer enough heap (for example,
`-Dspring-boot.run.jvmArguments="-Xmx3g"` for the demo) and monitor actual memory use.
Database failures can leave earlier groups saved; reruns recompute and overwrite preferences.
Favourite rows are deduplicated by user and normalized book ID before averaging. Users present in
the import with no usable book vectors have both stored taste vectors cleared; users absent from
the input are unchanged. Solr batches these clears with one write and commit per group, checking
existing profiles first to avoid creating empty records. Favourites use a normalized average; history
weights halve every 180 days before the result is normalized.

```bash
AUDIOBOOK_DATABASE=solr USER_PREFERENCES_IMPORT_ON_STARTUP=true ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.import.mode=write --user.preferences.favourites-csv-path=/path/favourites.csv --user.preferences.history-csv-path=/path/history.csv"
```

CSV records and aggregation live in `com.gen3.userpreferencejob`. Run this separately from the server
and other maintenance jobs.

### Read-only dry run (Solr or Qdrant)

`user.preferences.import.mode` defaults to `dry-run`: use the real CSV readers, local snapshot,
group limits and aggregation without saving or clearing user profiles. Only explicit
`write` mode permits persistence, as in the command above. Any other value fails startup.
`import-on-startup=true` is still required to launch the job; it does not authorize writes by itself.

With database connection settings configured, run from the repository root:

```bash
AUDIOBOOK_DATABASE=qdrant USER_PREFERENCES_IMPORT_ON_STARTUP=true \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.import.mode=dry-run --user.preferences.favourites-csv-path=data/import-benchmarks/100-users-100-records/favourites.csv --user.preferences.history-csv-path=data/import-benchmarks/100-users-100-records/history.csv"
```

Use `AUDIOBOOK_DATABASE=solr` for Solr. IDs in the CSVs must exist with compatible vectors in that
backend; IDs exported from Qdrant are not guaranteed to exist in a different Solr catalogue.
Build a snapshot first using the command below. The dry-run storage sink validates generated vectors and logs generated-profile and missing-vector
counts instead of database writes. Missing vectors are reported as would-be clears, never performed.
Completion logs include `mode=dry-run`, `snapshotLoadMs`, `csvReadMs` (parsing and spilling),
`sortMs` (merging and validation), `aggregationMs`, and `totalMs`, plus user and unique-row counts.
They exclude application startup and real profile persistence. No AI calls or remote catalogue-vector
lookups occur during daily imports. Snapshot rebuilds do read the catalogue.

Every launch of `UserPreferenceApplication` also saves application output to a unique
`logs/user-import/<epoch-milliseconds>-<uuid>.log` file, including startup errors, import progress
and completion timings. Console output is unchanged. This applies to both backends and both modes;
no extra command flags are needed. Logs are ignored by Git and retained until manually removed.
Override the destination with `--logging.file.name=/path/to/import.log` if needed. Maven output
before the application starts is not included.

### Build or refresh the local snapshot

```bash
AUDIOBOOK_DATABASE=qdrant QDRANT_TIMEOUT=120s ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.snapshotjob.BookVectorSnapshotApplication \
  -Dspring-boot.run.profiles=dev
```

Use `AUDIOBOOK_DATABASE=solr` to export Solr instead. This dedicated job only reads existing vectors;
it neither generates embeddings nor changes database records. The embedding-index launcher also
runs this separate rebuild step after a successful embedding pass. A failed rebuild fails that
workflow and can be retried with this command without generating embeddings again.

Files live under `data/book-vector-snapshot/<backend>/<collection-hash>.bin`, or the configured
`BOOK_VECTOR_SNAPSHOT_DIRECTORY`. Both jobs must use the same persistent directory and catalogue
settings. A snapshot is one binary file with a versioned metadata header (backend, collection,
declared embedding model, dimensions, creation time), exact float32 vectors keyed by book ID, count,
and SHA-256 checksum. It is ignored by Git. Missing vectors are excluded; an empty export is rejected.

A rebuild streams to a separate temporary file and validates it before atomic replacement. Any
failure leaves the previous published snapshot intact; filesystems without atomic replacement fail
the rebuild rather than publishing partially. Do not run catalogue writers during export: pagination
is not a database-wide transactional snapshot. Retain the previous snapshot until a refresh succeeds.

Daily imports accept the last compatible snapshot regardless of age. They reject missing, truncated,
corrupt, dimension/model/backend/collection-incompatible snapshots before any user-profile writes.
Freshness is not inferred from a checksum: new/changed/deleted catalogue books require an explicit
refresh, including changes made outside the embedding job. Books absent from an older snapshot are
treated as unavailable; users with no available evidence can have profiles cleared in explicit write
mode. The snapshot never modifies the Solr/Qdrant record schemas.

## Solr launcher verification

The container test starts the actual embedding, summary (submit/import/recover), and preference
applications, executes their startup jobs, then starts the recommendation HTTP server with Solr and
Redis. It also checks migration reads from Solr. AI and the migration destination use test doubles;
all data writes stay in disposable containers and temporary files.

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw -Dtest=SolrApplicationLaunchIntegrationTest test
```

Docker must be running. The normal `clean verify` command includes this test too.
