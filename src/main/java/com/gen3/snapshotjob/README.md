# Book-vector snapshot job

## Purpose

Exports Solr or Qdrant book vectors to a checksummed local file used by preference imports. The
standalone launcher refreshes or retries this export without rerunning embedding generation.

## Prerequisites

- Java 21, an existing compatible catalogue, and database credentials where required.
- Writable persistent storage shared with the user importer, with space for the published snapshot
  and its temporary replacement.
- Matching backend, collection, declared embedding model and dimensions, and a stable catalogue
  during export. No OpenAI credentials or Redis are needed.

## Operation

- Launcher: `com.gen3.snapshotjob.BookVectorSnapshotApplication`.
- Selection: `audiobook.database=<backend>` requires `<backend>.collection`; built-in defaults come
  from application configuration. Generation also requires the matching `CatalogueSnapshotSource`.
- Writes: local snapshot file only; no database records or schemas are changed.
- Also runs automatically after successful catalogue embedding generation.
- **A failed refresh preserves the previous snapshot.** Imports accept the last compatible version
  regardless of age; catalogue changes require an explicit refresh.

See the [snapshot runbook](../../../../../../docs/jobs/snapshots.md) for commands, storage format,
completion checks and recovery. The recommendation server does not read this local snapshot.

## Test data

Automated tests use small temporary vectors and a disposable Qdrant container; see the
[snapshot test-data instructions](../../../../../../docs/jobs/snapshots.md#test-data).
User interaction datasets and performance measurements are documented by the
[user-preference benchmark](../../../../../../docs/jobs/user-preferences.md#benchmark-datasets-and-results).
