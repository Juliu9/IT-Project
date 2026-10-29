# Solr-to-Qdrant migration

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Move an existing Solr catalogue into Qdrant without paying to regenerate its stored embeddings or
summaries. Without this job, the supported fields and vectors would need a separate export/import
process or the destination catalogue would need rebuilding from source data.

This is the only job connecting to two databases. It copies supported catalogue fields,
short descriptions, and existing dense vectors, skipping books already present at the destination.

## Prerequisites

- Java 21 and reachable Solr source and Qdrant destination services.
- Both connection settings and required credentials supplied through the root dev `.env` or environment.
- An existing Solr source collection containing the records to copy. The destination collection
  may be initialized by the migration.
- Source vectors compatible with the configured destination dimensions and schema. Retain the
  existing Qdrant schema when reusing a collection.
- Read access to Solr, write access to Qdrant, and no other catalogue writers during migration.

Redis and AI credentials are unnecessary. This job has no dry-run mode.

## Run

```bash
QDRANT_MIGRATION_ENABLED=true ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
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

## Completion and recovery

Expect `Solr-to-Qdrant migration complete: ... records`, `BUILD SUCCESS`, and a zero exit status.
The count is newly copied books, not every source row scanned. A failure can leave earlier batches
copied; rerunning starts the scan again and skips existing destination books. Conflicting duplicate
book IDs require reconciliation, not repeated retries. The job does not overwrite existing books.

Migration does not refresh the destination snapshot. Before user imports, run the
[snapshot job](snapshots.md) against Qdrant; generate missing embeddings first if needed.

