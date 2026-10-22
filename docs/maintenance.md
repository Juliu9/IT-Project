# Maintenance jobs

[Back to README](../README.md)

These commands write data and may incur AI-provider charges. Run each job separately, against a
stable catalogue, with one writer at a time. Set credentials and connection settings in the shell or
IDE environment.

## Catalogue embeddings

All catalogue indexing orchestration, book-to-vector conversion, and job-specific database adapters
live under `com.gen3.embeddingjob`. Search-query embedding remains in the recommendation service;
shared catalogue records and general database persistence stay in the storage packages.

Book-vector generation is disabled by default and runs only in a separate, non-web process. Choose
the database containing the existing records with `AUDIOBOOK_DATABASE=solr` or `qdrant`. The same
selected adapter reads the records and updates their vectors; neither migration nor normal request
handling starts this job. Query embeddings used by normal searches are unchanged.

For example, index up to 100 existing Solr records:

```bash
AUDIOBOOK_EMBEDDING_INDEX_ENABLED=true AUDIOBOOK_DATABASE=solr \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.embeddingjob.EmbeddingIndexApplication \
  -Dspring-boot.run.arguments="--audiobook.embedding-index.maximum-records=100"
```

Replace `solr` with `qdrant` to index the records already in Qdrant. Only the selected database
client is loaded; the job does not need Redis or the recommendation backend's user-preference
adapters. OpenAI credentials and the selected database connection settings are still required. The
Solr collection must have the configured stored vector field, or Qdrant must have the configured
named-vector schema, matching `embedding.dimensions`.

`audiobook.embedding-index.page-size` defaults to 100. `maximum-records=0` processes the full
catalogue. This explicitly regenerates existing dense vectors, incurs embedding-provider cost, and
also builds Qdrant keyword vectors. Updates preserve record IDs, metadata, custom fields, and short
descriptions. Blank records are skipped. Failed pages are not written if embedding fails; earlier
completed pages remain saved. Rerunning starts at the beginning, so use a stable catalogue and run
one indexing job at a time. The process closes its clients and exits when done.

The former `solr.embedding-index-on-startup` and `solr.embedding-index-page-size` settings are
replaced by this database-independent job. Enabling the new flag on the normal server is rejected;
use the dedicated entry point. Migration and indexing cannot run together. Another database can
implement `com.gen3.embeddingjob.store.AudiobookEmbeddingStore` and register its adapter for the
selected database.

## Solr-to-Qdrant migration

Solr-to-Qdrant migration is the sole two-database exception and requires a dedicated non-web run. It
copies the supported catalogue fields, existing short descriptions, and the configured stored Solr
dense-vector field. It never calls an embedding or summary model, normalizes existing vectors, or
generates sparse keyword vectors. Records without vectors are copied with no vectors; they need a
separate opt-in indexing step before vector search can find them. Existing destination records are
skipped so reruns preserve their summaries and vectors. The record limit counts source records read,
including skipped destination records. Source vector dimensions must match Qdrant. The embedding job
above indexes these copied records. This does not complete the selectable Solr recommendation
backend.

Run against a stable source catalogue and avoid concurrent writes to the destination during
migration:

```bash
QDRANT_MIGRATION_ENABLED=true ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.migrationjob.MigrationApplication \
  -Dspring-boot.run.arguments="--audiobook.database=qdrant"
```

## Short descriptions

Recommendations use a stored, one-sentence `short_description` when it is available. Generate these
descriptions offline through a `ShortDescriptionBatchProvider`; OpenAI Batch is the default adapter.
Normal requests never call AI for a summary. The database-independent job reads `description`
through the selected `AudiobookEnrichmentStore`, generates `shortDescription`, and uploads through
that same adapter without re-embedding books. The adapter maps those domain fields to its database
names (Qdrant uses `short_description`) and preserves existing short descriptions.

All short-description orchestration, provider transport, and summary-specific database adapters live
under `com.gen3.shortdescriptionjob`. The normal server never loads this job, even when explicitly
configured: it rejects enabled summary modes and directs you to the maintenance entry point. Choose
`AUDIOBOOK_DATABASE=solr` or `qdrant`; only that database is connected. This job uses neither Redis
nor embedding generation and does not require a complete recommendation backend.

Start with a small submission to check quality and spend:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=submit \
AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MAXIMUM_RECORDS=1000 \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

Solr requires a stored, single-valued `short_description` field (for example, type `string`) and the
standard `_version_` field. It updates only `short_description`, checks `_version_` to reject
concurrent changes, and preserves existing summaries. On a conflict, the manifest is retained; retry
import to apply remaining results. Qdrant retains its conditional missing-only payload updates.
Neither adapter rewrites book vectors. Run one submission/import process at a time and retain its
output directory.

Solr summary imports read current IDs, summaries, and versions in batches of at most 500, then apply
bounded, version-checked updates. A failed later batch retains the manifest; retrying preserves
summaries already written.

The job records the Batch ID, provider, backend/dataset identity, and opaque record references in
`data/short-descriptions/short-description-batch.json`. Import using the same backend, endpoint, and
collection; if generation is still pending, import polls and logs progress until it completes. A
mismatched provider or dataset, or a legacy manifest without identity is rejected before provider
calls or database writes, leaving the manifest intact. Legacy batches require explicit recovery with
their original implementation/configuration; these manifests are never silently converted. Import
the output:

```bash
AUDIOBOOK_DATABASE=solr AUDIOBOOK_SHORT_DESCRIPTION_BATCH_MODE=import \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.shortdescriptionjob.ShortDescriptionApplication
```

For the remaining catalogue, submit up to 100,000 records at a time and repeat after each import;
each submission selects the records still missing `short_description`. A pending manifest blocks
another submission until it is imported. An input-upload failure leaves no pending manifest, so
submission can be retried normally. Once batch submission starts, an unknown outcome retains the
manifest to prevent duplicate generation: reconcile the batch with the provider, then supply
`AUDIOBOOK_SHORT_DESCRIPTION_BATCH_ID` to import it. Manifest updates are atomic so a failed write
preserves the previous recovery data. OpenAI batch requests default to `gpt-4o-mini`
(`OPENAI_BATCH_MODEL`), group ten books per request, and ask for a spoiler-free sentence of at most
20 words. The shared job enforces that word limit for every provider. Keep the default mode
(`disabled`) for ordinary application startup.

To replace OpenAI Batch, implement `ShortDescriptionBatchProvider`, register the adapter, and set
`AUDIOBOOK_SHORT_DESCRIPTION_BATCH_PROVIDER` to its name. Its `upload` must only prepare input;
`submit` starts generation, and `completedDescriptions` returns book IDs and descriptions. The job
owns manifests, database references, safe retries, and import validation. Existing manifests without
a provider field belong to OpenAI. The replacement adapter must be conditional on its configured
name; do not switch provider while a batch is pending.

## Summary selection, progress, and timeouts

Short-description submission limits count usable records with IDs and descriptions; unusable rows
are skipped while scanning continues. Duplicate book IDs within a submission are rejected before
upload, so ambiguous Qdrant records must be corrected first.

Batch HTTP calls have a 15-minute timeout per request, including large uploads and downloads. Import
polls pending generation every 30 seconds and logs completed, failed, and total provider request
counts (each request can contain multiple books). It waits up to 24 hours before leaving the
manifest for a later import attempt. Terminal failures still retain the manifest for recovery.
During database writes, import logs processed and total update counts after each bounded batch;
processed counts include records safely skipped because they already have summaries.

## User preferences

CSV import and preference-vector aggregation run separately from the recommendation server. The
existing opt-in flag and CSV path settings are retained. This job uses the selected database
adapters; Qdrant currently provides the complete pair of audiobook-vector and user-preference
adapters. It averages existing book vectors without calling an AI model.

```bash
USER_PREFERENCES_IMPORT_ON_STARTUP=true ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.favourites-csv-path=/path/favourites.csv --user.preferences.history-csv-path=/path/history.csv"
```

Run maintenance jobs individually. The normal server rejects their enabled flags.
