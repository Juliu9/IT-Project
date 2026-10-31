# Job runbooks

[Repository README](../../README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Keep catalogue preparation and user-profile imports outside the interactive recommendation process,
so expensive scans, AI generation, and bulk writes can run independently. Without these launchers,
the same work would need external scripts or would have to be coordinated inside the server.

Five independently runnable jobs reuse shared catalogue code rather than maintaining separate
copies. Separate launchers do not require separate repositories or a running recommendation server.
No scheduler is included; daily execution must be arranged externally.

## Prerequisites

- Java 21 and the repository's Maven wrapper; run documented commands from the repository root.
- The selected job's input data, database access, credentials, and storage described in its runbook.
  AI credentials are required only for embedding and short-description generation; Redis is not
  required by these jobs.
- A stable catalogue and an execution window that avoids overlapping catalogue writers with
  migration or snapshot export. Jobs do not take the database offline, but compete for resources.
- Docker when using the documented local database setup or running container integration tests;
  it is not required merely to connect a job to an existing remote database.

## Available jobs

| Runbook | Main class under `com.gen3.` | Writes / cost |
| --- | --- | --- |
| [Embeddings](embeddings.md) | `embeddingjob.EmbeddingIndexApplication` | Fills missing dense vectors by default; explicit `rebuild-all` replaces existing vectors; then publishes a full snapshot; paid embedding calls |
| [Snapshots](snapshots.md) | `snapshotjob.BookVectorSnapshotApplication` | Local vector file for user imports; catalogue read-only; no AI calls |
| [User preferences](user-preferences.md) | `userpreferencejob.UserPreferenceApplication` | `dry-run` by default; explicit `write` replaces/clears imported users' taste vectors; no AI calls |
| [Migration](migration.md) | `migrationjob.MigrationApplication` | Creates absent books and fills missing same-named fields from Solr to Qdrant; no AI calls |
| [Short descriptions](short-descriptions.md) | `shortdescriptionjob.ShortDescriptionApplication` | Paid regular API calls; fills missing summaries by default; explicit `rebuild-all` replaces them |

## Execution order

1. Populate the selected catalogue. Migration is optional and specifically copies Solr to Qdrant;
   Docker startup alone does not populate books.
2. Generate embeddings using default `missing-only` or explicitly `rebuild-all` when replacing them.
   Missing-only does not detect text/model changes. A successful embedding launcher
   automatically rebuilds the snapshot. Even a record-limited embedding run exports the full
   catalogue snapshot afterward; changing models requires a fully compatible catalogue first.
3. If suitable vectors already exist, run only the snapshot job. Also use it after catalogue changes
   made outside the embedding workflow or to retry failed snapshot publication without paying for
   embeddings again.
4. Run user import in `dry-run`, inspect counts and missing-vector reports, then use explicit `write`
   only when intended. Daily imports reuse the **last compatible** snapshot without rebuilding it.

*Summary generation is a separate generate-and-save workflow using regular API calls. It does not
refresh book embeddings or snapshots. The embedding text uses title, authors, narrators, language
and full description, not `short_description`, so generating summaries alone does not require
re-embedding. Changes to the
embedding input fields require intentional regeneration. Do not overlap catalogue writers with
snapshot export.

Snapshot failure does not undo successfully written embeddings. The previous published snapshot
remains available. Distinguish embedding completion from completion of the whole launcher.

## Shared rules and verification

Read [Maintenance operations](../maintenance.md) for configuration loading, progress fields,
concurrency and completion signals. Job-specific defaults are in the
[configuration reference](../configuration.md#maintenance-jobs-explicit-opt-in).

The [standard non-live verification](../../README.md#verification-commands) includes disposable
database integration tests. It does not run production maintenance jobs. Operational examples below
are real commands, not a simulation; do not execute write-mode examples against shared data casually.
