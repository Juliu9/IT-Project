# Job runbooks

[Repository README](../../README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Six dedicated jobs keep scans, AI generation, and bulk writes outside the recommendation server.
They share catalogue code but run independently. **No scheduler is included.**

## Prerequisites

- Java 21 and the repository's Maven wrapper; run documented commands from the repository root.
- The selected job's input data, database access, credentials, and storage described in its runbook.
  AI credentials are required only for embedding and short-description generation; Redis is not
  required by these jobs.
- A stable catalogue and an execution window without overlapping writers. Jobs remain online but
  compete with interactive traffic.
- Docker when using the documented local database setup or running container integration tests;
  it is not required merely to connect a job to an existing remote database.

## Available jobs

| Runbook | Main class under `com.gen3.` | Writes / cost |
| --- | --- | --- |
| [Embeddings](embeddings.md) | `embeddingjob.EmbeddingIndexApplication` | Fills missing dense vectors by default; explicit `rebuild-all` replaces existing vectors; then publishes a full snapshot; paid embedding calls |
| [Snapshots](snapshots.md) | `snapshotjob.BookVectorSnapshotApplication` | Local vector file for user imports; catalogue read-only; no AI calls |
| [User preferences](user-preferences.md) | `userpreferencejob.UserPreferenceApplication` | `dry-run` by default; explicit `write` replaces mapped sides, preserves absent/unmapped sides, and skips users with no mapped vectors; no AI calls |
| [Migration](migration.md) | `migrationjob.MigrationApplication` | Creates absent books and fills missing same-named fields from Solr to Qdrant; no AI calls |
| [Short descriptions](short-descriptions.md) | `shortdescriptionjob.ShortDescriptionApplication` | Paid regular API calls; fills missing summaries by default; explicit `rebuild-all` replaces them |
| [Read history](read-history.md) | `readhistoryjob.ReadHistoryApplication` | Adds unique user/book pairs to PostgreSQL; no AI or catalogue access |

## Execution order

1. Populate the selected catalogue. Migration is optional and specifically copies Solr to Qdrant;
   Docker startup alone does not populate books.
2. Generate embeddings with `missing-only`, or explicitly use `rebuild-all` after text/model changes.
   A successful run exports the full catalogue snapshot, even when embedding generation is limited.
3. If suitable vectors already exist, run only the snapshot job. Also use it after catalogue changes
   made outside the embedding workflow or to retry failed snapshot publication without paying for
   embeddings again.
4. Run user import in `dry-run`; inspect it before explicitly selecting `write`. Imports reuse the
   **last compatible snapshot**.

Summary generation is independent and does not refresh embeddings or snapshots. Summaries are not
part of embedding text. **Do not overlap catalogue writers with snapshot export.**

Read-history import is independent of those steps. Prepare its PostgreSQL schema explicitly, import
the history CSV, then enable `READ_HISTORY_ENABLED` on the server. It can run while recommendations
are active; completed batches become visible to later history lookups.

Snapshot failure does not undo successfully written embeddings. The previous published snapshot
remains available. Distinguish embedding completion from completion of the whole launcher.

## Shared rules and verification

Read [Maintenance operations](../maintenance.md) for configuration loading, progress fields,
concurrency and completion signals. Job-specific defaults are in the
[configuration reference](../configuration.md#maintenance-jobs-explicit-opt-in).

The [standard non-live verification](../../README.md#verification-commands) includes disposable
database tests but does not run production jobs. **Runbook commands are real operations, not
simulations.** Verify the backend, mode, and limits before using shared data.
