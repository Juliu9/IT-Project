# Book-vector snapshot job

Exports existing Solr or Qdrant book vectors to a checksummed local binary file. User-preference
imports load this file instead of repeatedly retrieving vectors from the catalogue database.

- Launcher: `com.gen3.snapshotjob.BookVectorSnapshotApplication`.
- Writes: local snapshot file only; no database records or schemas are changed.
- Requires: an existing compatible catalogue, database credentials where applicable, and writable
  persistent storage. No OpenAI credentials or Redis are needed.
- Also runs automatically after successful catalogue embedding generation.
- A failed refresh preserves the previous published snapshot. Daily imports accept the last
  compatible version regardless of age; catalogue changes require a refresh.

See the [snapshot runbook](../../../../../../docs/jobs/snapshots.md) for commands, storage format,
completion checks and recovery. The recommendation server does not read this local snapshot.

## Test data

Automated tests use small temporary vectors and a disposable Qdrant container; they do not need
the large local snapshot. Import benchmarks use synthetic users assigned books from a pool of
200,000 existing Qdrant book IDs with vectors. They exercise eight scenarios, from 10,000 to
20 million interaction records, and must run in explicit `dry-run` mode.

See [test-data scenarios and distribution](../../../../../../docs/jobs/snapshots.md#test-data)
for exact sizes, provenance, per-user record distribution and verification commands. Large datasets,
snapshots and benchmark logs are Git-ignored local artifacts, not included in a fresh clone.
