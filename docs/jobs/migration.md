# Solr-to-Qdrant migration

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Move an existing Solr catalogue into Qdrant without paying to regenerate its stored embeddings or
summaries. Without this job, the supported fields and vectors would need a separate export/import
process or the destination catalogue would need rebuilding from source data.

This is the only job connecting to two databases. It copies supported catalogue fields,
short descriptions, publication dates, and existing dense vectors. Every source ID is checked against
the destination payload `id`, including books already present; missing fields are filled rather than
skipping the whole book. This is a missing-data backfill, not a synchronization job: populated
Qdrant values win even when Solr has different values.

## Prerequisites

- Java 21 and reachable Solr source and Qdrant destination services.
- Both connection settings and required credentials supplied through the root dev `.env` or environment.
- An existing Solr source collection containing the records to copy. The destination collection
  may be initialized by the migration.
- Source vectors compatible with the configured destination dimensions and schema. Retain the
  existing Qdrant schema when reusing a collection.
- Existing destination books must already use payload `id` and `duration_minutes`, not the old
  field names. If needed, complete the [one-off payload rename](../qdrant-payload-migration.md)
  first. This job does not guess aliases or delete old payload keys.
- Read access to Solr, write access to Qdrant, and no other catalogue writers during migration.

Redis and AI credentials are unnecessary. This job has no dry-run mode.

## Run

```bash
QDRANT_MIGRATION_ENABLED=true QDRANT_MIGRATION_MAX_RECORDS=0 QDRANT_TIMEOUT=180s \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.migrationjob.MigrationApplication \
  -Dspring-boot.run.arguments="--audiobook.database=qdrant"
```

This command writes to Qdrant and scans every Solr record. The 180-second timeout applies to each
Qdrant call, not the whole job. On macOS, prefix the command with `caffeinate -i env` to prevent
idle sleep; keep the lid open. On other platforms, use the command as shown.

## Field selection and conflict rules

The code has a fixed field list; it does not discover or import every Solr field. Books match by
Solr `id` to Qdrant payload `id`, independently of the Qdrant point ID.

| Solr field | Qdrant payload field |
| --- | --- |
| `id`, `source`, `title`, `authors`, `description` | Same names |
| `narrators`, `language`, `duration_minutes` | Same names |
| `pubdate`, `short_description` | Same names |

| Source value | Existing Qdrant value | Action |
| --- | --- | --- |
| Usable | Missing, null, blank, or empty | Fill from Solr |
| Usable | Populated, equal or different | Preserve Qdrant |
| Missing or unusable | Populated | Preserve Qdrant |
| Missing or unusable | Missing or unusable | Leave unfilled and report unavailable |

A missing source field does not fail the record. Unlisted fields such as `all`, `rt_authors`,
`format`, `size`, `title_string`, `title_sort`, `rt_title`, `type`, and `_version_` are not copied.
If no Qdrant book matches, the job creates one using the available supported fields.

### Dates, vectors, and indexes

- Existing destination points keep their point IDs, populated metadata, summaries, dense vectors,
  sparse vectors and unrelated payload. Only missing/null/blank/empty supported fields are filled.
- There is no fallback from Solr `durationMinutes`, `publicationYear`, `rt_title`, or `rt_authors`.
  Solr must supply the supported same-named field.
- Valid Solr `pubdate` values are copied to `pubdate`. Solr date objects/timestamps are normalized to
  UTC; date-only strings remain date-only. Missing/invalid dates stay absent without blocking other
  fields. No year is guessed from descriptions or removed fields. Year-based filtering and recency
  scoring derive the year from the valid date, never a stored `publicationYear`.
- A record is skipped if there is no usable source value to add. Fields unavailable in both databases
  are reported, not fabricated; a rerun does not rewrite the record merely because those fields are absent.
- Missing destination dense vectors can be filled from compatible existing Solr embeddings. Existing
  vectors are never replaced. A missing sparse keyword vector still requires the embedding job.
  The source vector field is configured by `SOLR_VECTOR_FIELD` (default `embedding`); it maps to
  the Qdrant named vector `dense`, not a payload field. Default missing-only embedding generation
  does not repair sparse vectors on books that already have dense vectors; see the
  [embedding runbook](embeddings.md).
- The migrator prepares the new identity, runtime, and datetime payload indexes on existing collections.
- No AI calls, vector normalization, or sparse keyword generation occur.
- Books without source vectors are copied without vectors. Run the embedding job separately to
  make them available to vector search.
- The record limit counts source rows read, including skipped destination books. Configure
  `audiobook.qdrant.migration.batch-size` and `audiobook.qdrant.migration.max-records`; zero maximum
  means the full catalogue.
- Keep the source stable and avoid other destination writers during migration.

Migration copies data only; use `AUDIOBOOK_DATABASE` to select the recommendation backend.

## Completion and recovery

Expect `Solr-to-Qdrant migration complete: ... records`, `BUILD SUCCESS`, and a zero exit status.
The final count is books created or filled, with the total source rows scanned. Per-batch progress
reports `created`, `updated`, `skipped`, `unavailableFields` (counts by field name), and
`sourceMissingOrInvalidPubdate`.
A failure can leave earlier batches applied; rerunning starts the source scan again and fills only
what is still missing. Conflicting duplicate catalogue IDs require reconciliation, not repeated retries.
There is no collection-wide transaction; keep all catalogue writers stopped while migrating.
Unlike the Python rename tool, this launcher has no backup-based `--resume` option and no automatic
job-level retry. On a network error, fix connectivity and rerun the launcher; already-filled data is
preserved. Take a database backup before a shared-database run.

Migration does not refresh the destination snapshot. Before user imports, run the
[snapshot job](snapshots.md) against Qdrant; generate missing embeddings first if needed.

