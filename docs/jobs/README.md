# Job runbooks

[Repository README](../../README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

Five independently runnable jobs live in this repository. They reuse shared catalogue code rather
than maintaining separate copies. Separate launchers do not require separate repositories or a
running recommendation server. No scheduler is included; daily execution must be arranged externally.

| Runbook | Main class under `com.gen3.` | Writes / cost |
| --- | --- | --- |
| [Embeddings](embeddings.md) | `embeddingjob.EmbeddingIndexApplication` | Replaces catalogue vectors, then publishes a snapshot; paid embedding calls |
| [Snapshots](snapshots.md) | `snapshotjob.BookVectorSnapshotApplication` | Local file only; catalogue is read-only; no AI calls |
| [User preferences](user-preferences.md) | `userpreferencejob.UserPreferenceApplication` | `dry-run` by default; explicit `write` replaces/clears imported users' taste vectors; no AI calls |
| [Migration](migration.md) | `migrationjob.MigrationApplication` | Copies absent books from Solr to Qdrant; no AI calls |
| [Short descriptions](short-descriptions.md) | `shortdescriptionjob.ShortDescriptionApplication` | Paid batch submission; import fills missing summaries; recovery archives a local manifest |

## Execution order

1. Populate the selected catalogue. Migration is optional and specifically copies Solr to Qdrant;
   Docker startup alone does not populate books.
2. Generate embeddings if missing or intentionally changing them. A successful embedding launcher
   automatically rebuilds the snapshot. Even a record-limited embedding run exports the full
   catalogue snapshot afterward; changing models requires a fully compatible catalogue first.
3. If suitable vectors already exist, run only the snapshot job. Also use it after catalogue changes
   made outside the embedding workflow or to retry failed snapshot publication without paying for
   embeddings again.
4. Run user import in `dry-run`, inspect counts and missing-vector reports, then use explicit `write`
   only when intended. Daily imports reuse the last compatible snapshot without rebuilding it.

Summary generation is a separate submit/import/recover workflow. It does not refresh book embeddings
or snapshots. The embedding text uses title, authors, narrators, language and full description, not
`short_description`, so generating summaries alone does not require re-embedding. Changes to the
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
