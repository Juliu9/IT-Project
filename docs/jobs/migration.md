# Solr-to-Qdrant migration

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

**Purpose:** Copy supported existing catalogue fields, short descriptions, and stored dense vectors.
This is the only job connecting to two databases. Source vectors must match the destination dimensions.

## Prerequisites and run

Configure both Solr source and Qdrant destination connections and credentials in the root `.env`
or environment. Redis and AI credentials are unnecessary. The source collection must already exist;
the destination may be initialized by the migration. Set the intended target dimensions and retain
the existing Qdrant schema when reusing a collection. This job has no dry-run mode.

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

