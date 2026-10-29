# User-preference import

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

Read favourites/history CSVs and aggregate existing book vectors into user vectors, without AI calls.
Both Solr and Qdrant supply the required adapters. Set `AUDIOBOOK_DATABASE=solr` or `qdrant`;
the snapshot must match that backend and catalogue, and write mode targets its user collection.
For Solr, prepare the separate preference collection and
schema described in [Solr setup](../configuration.md#solr). Solr writes at most 500 user updates per
batch and commits once per batch; earlier batches remain saved if a later batch fails.

The importer uses the last valid local book-vector snapshot, not remote catalogue lookups. It loads
and verifies that snapshot before reading CSVs or writing any profiles. There is no age-based expiry
and no fallback to remote reads. Each process pins its loaded snapshot even if a new one is published.
It groups at most 100,000 distinct book IDs by default, regardless of user count. Each user stays
whole. Profile saves and clears are batched
at 500 users. The book-ID limit is configurable under `user.preferences.import`
(see [configuration](../configuration.md#user-preference-import)).
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

## CSV input contract

Supply both files with headers. An empty side still needs its header row. Files are read as UTF-8;
standard CSV quoting is supported, extra columns are ignored, and rows whose `type` is not `book`
(case-insensitive) are skipped.

`favourites.csv`:

```csv
type,user,item
book,demo-user,rnib:book:123
```

`history.csv`:

```csv
type,user,item,created_at,updated_at
book,demo-user,rnib:book:456,2026-10-01 12:00:00,NULL
```

- `user` is the nonblank external user ID; it must match the caller's `X-User-Id` to use that profile.
- `item` follows `provider:book:id[:optional title]`, normalized to `provider_id` with the provider
  lowercased and the ID retained. For example, `rnib:book:123:Title` maps to `rnib_123`.
- Dates use `yyyy-MM-dd HH:mm:ss` interpreted as UTC. `created_at` is required; a nonblank
  `updated_at` other than `NULL` overrides it. The latest effective timestamp wins for duplicate
  history rows with the same user/book. Favourite and history evidence remain separate.
- Rows can be unordered. Duplicate favourites count once per user/book. No history is truncated to
  meet limits. Malformed input stops preparation before any profile writes.

**This is a full replacement import for each included user, not an incremental append.** Supply all
evidence intended for that user's new profile. A missing side removes that taste vector in write
mode; no usable evidence removes both vectors. Users absent from both inputs are unchanged.
Missing snapshot books are skipped, so inspect dry-run reports before authorizing writes.
The sink reports users with no usable vectors, not a per-record missing-book rate; partial evidence
can still produce a profile without a missing-user warning. Check snapshot coverage/freshness too.

## Write mode

Only run after checking a dry run against the same files and snapshot. This command changes user
profiles in the selected database:

```bash
AUDIOBOOK_DATABASE=solr USER_PREFERENCES_IMPORT_ON_STARTUP=true ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.import.mode=write --user.preferences.favourites-csv-path=/path/favourites.csv --user.preferences.history-csv-path=/path/history.csv"
```

CSV records and aggregation live in `com.gen3.userpreferencejob`. Run this separately from the server
and other maintenance jobs.

## Read-only dry run (Solr or Qdrant)

`user.preferences.import.mode` defaults to `dry-run`: use the real CSV readers, local snapshot,
group limits and aggregation without saving or clearing user profiles. Only explicit
`write` mode permits persistence, as in the command above. Any other value fails startup.
`import-on-startup=true` is still required to launch the job; it does not authorize writes by itself.

With database connection settings configured, run from the repository root:

```bash
AUDIOBOOK_DATABASE=qdrant USER_PREFERENCES_IMPORT_ON_STARTUP=true \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Xmx3g" \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.import.mode=dry-run --user.preferences.favourites-csv-path=/absolute/path/favourites.csv --user.preferences.history-csv-path=/absolute/path/history.csv"
```

Use `AUDIOBOOK_DATABASE=solr` for Solr. IDs in the CSVs must exist with compatible vectors in that
backend; IDs exported from Qdrant are not guaranteed to exist in a different Solr catalogue.
Build a snapshot first using the [snapshot runbook](snapshots.md). The dry-run storage sink validates
generated vectors and logs generated-profile and missing-vector
counts instead of database writes. Missing vectors are reported as would-be clears, never performed.
Completion logs include `mode=dry-run`, `snapshotLoadMs`, `csvReadMs` (parsing and spilling),
`sortMs` (merging and validation), `aggregationMs`, and `totalMs`, plus user and unique-row counts.
These dry-run timings exclude application startup and real profile persistence; write-mode timings
include actual saves/clears. No AI calls or remote catalogue-vector
lookups occur during daily imports. Snapshot rebuilds do read the catalogue.

Every launch of `UserPreferenceApplication` also saves application output to a unique
`logs/user-import/<epoch-milliseconds>-<uuid>.log` file, including startup errors, import progress
and completion timings. Console output is unchanged. This applies to both backends and both modes;
no extra command flags are needed. Logs are ignored by Git and retained until manually removed.
Override the destination with `--logging.file.name=/path/to/import.log` if needed. Maven output
before the application starts is not included.

## Completion, failures and retries

Look for `user.preferences.import.completed mode=dry-run|write`, the final acknowledgement,
`BUILD SUCCESS` and a zero exit status. Dry-run reports `writes=0` and `clears=0` instead of calling
the database writer. Clients may still contact the selected database during startup, such as Qdrant's
compatibility check; dry-run means no record mutations, not necessarily no network traffic.

Completion reports raw valid favourite/history row counts, deduplicated `uniqueRecords`, and distinct
users. `csvReadMs` includes parsing and sorted-run spilling; `sortMs` includes merging and full-input
validation; `aggregationMs` includes prepared-file reading, vector calculations and the selected sink.
`totalMs` also includes snapshot loading and temporary-file cleanup. Merge counters restart per group;
the user-progress counter tracks prepared users and can advance ahead of the current batch's save.
Only the completion event confirms the entire import finished.

Missing/corrupt/incompatible snapshots, malformed CSVs and oversized users fail before profile writes.
Database failures during explicit write mode can leave earlier batches saved; the import is not one
transaction. Fix the cause and rerun with complete intended user evidence. An abrupt process kill can
leave a uniquely named `user-import-*` directory; remove only a confirmed abandoned directory, not
the work parent or another running job's files. Snapshot cache memory is independent of CSV batch size.

## Local benchmark reference

On the demo machine, using the existing Qdrant snapshot and a 3 GB heap on 2026-10-29:

| Dataset | Dry-run import time |
| --- | --- |
| 100,000 users × 100 records (10 million rows) | 3m 32s |
| 50,000 users, 100–10,000 records each (20 million rows total; mean 400) | 6m 52s |

These are measurements, not guarantees: they include snapshot load and disk sorting, exclude
application startup and real writes, and use a synthetic 200,000-book pool. The variable dataset is
skewed, not uniform or derived from real usage. Generated benchmark files under `data/import-benchmarks`
and timing logs under `logs/user-import` are ignored by Git and are not included in a fresh clone.

