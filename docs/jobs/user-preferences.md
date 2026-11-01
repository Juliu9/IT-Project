# User-preference import

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Aggregate favourites and listening history into stored taste vectors using a local book-vector
snapshot. The job makes no AI calls.

## Prerequisites

- Java 21 and both favourites/history CSVs following the [CSV input contract](#csv-input-contract).
  Supply a header-only file when one side has no records, and complete intended evidence for
  every user included in a write-mode run.
- A valid local book-vector snapshot matching the selected backend, catalogue, declared model,
  and dimensions. Build it with the [snapshot job](snapshots.md) before running this import.
- `AUDIOBOOK_DATABASE=solr` or `qdrant`, with the connection settings for the selected user store.
  In write mode, provide permission to read and update imported users' taste vectors. For Solr,
  prepare the separate preference collection and schema in [Solr setup](../configuration.md#solr).
- Enough heap for the full snapshot and writable temporary disk space comparable to the CSV inputs;
  see the processing details below.

Neither OpenAI credentials nor Redis is required. Dry-run still requires a valid local snapshot;
startup clients can contact the selected database even though profiles are not written.

## Processing and storage

Solr writes at most 500 user updates per batch and commits once per batch; earlier batches remain
saved if a later batch fails. Write mode targets the selected backend's user collection.

The importer validates and pins the last compatible snapshot before reading CSVs; it has no remote
vector fallback or age check. CSVs are streamed through sorted temporary runs, so unordered input is
supported. Duplicate favourites collapse; duplicate history keeps the latest interaction.

| Limit or resource | Default behavior |
| --- | --- |
| Distinct books per user/group | 100,000; oversized users fail before writes |
| In-memory input group | About 100,000 rows; users are never split |
| Profile write batch | 500 users |
| Temporary disk | Comparable to input CSV size under `./data/user-import-work` |
| Snapshot memory | Full snapshot retained in RAM; 200,000 × 1,536 vectors are about 1.14 GiB before overhead |

Use sufficient heap (`-Xmx3g` for the documented demo). Favourites use a normalized average;
history weights halve every 180 days before normalization. **A missing input side or a side whose
book IDs have no vectors preserves that user's existing stored vector.** Included users with no
mapped vectors are skipped, not cleared; users absent from both files are unchanged.

Before write mode, the job calculates the complete outcome. It aborts before any database write when
the proportion of users with no mapped vectors exceeds
`user.preferences.import.max-unmapped-user-ratio` (default `0.10`, or 10%). Their IDs are written to
`unmapped-users.txt` in the work directory with owner-only permissions where supported; normal logs
contain counts only. Database failure can still leave earlier idempotent batches saved; rerun the
same approved import to converge.

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

- `user` is the nonblank external user ID, at most 256 Java characters; it must match the caller's
  `X-User-Id` to use that profile. Both CSV readers reject invalid IDs during preparation, before
  vector lookups or profile updates, in dry-run and write modes alike.
- `item` follows `provider:book:id[:optional title]`, normalized to `provider_id` with the provider
  lowercased and the ID retained. For example, `rnib:book:123:Title` maps to `rnib_123`.
- Dates use `yyyy-MM-dd HH:mm:ss` interpreted as UTC. `created_at` is required; a nonblank
  `updated_at` other than `NULL` overrides it. The latest effective timestamp wins for duplicate
  history rows with the same user/book. Favourite and history evidence remain separate.
- Rows can be unordered. Duplicate favourites count once per user/book. No history is truncated to
  meet limits. Malformed input stops preparation before any profile writes.

Each mapped side replaces that side's previous vector; an absent or completely unmapped side is
preserved. Users absent from both inputs are unchanged. Missing snapshot books are skipped, so
inspect the dry-run outcome and restricted unmapped-user report before authorizing writes.
The job reports users with no usable vectors, not a per-record missing-book rate; partial evidence
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

**Write mode requires a successful dry run with the exact same files and snapshot.** Dry run writes
an owner-only approval manifest (default `./data/user-import-work/approved.properties`) containing
the CSV and snapshot SHA-256 hashes, snapshot identity, and projected outcome counts. Write mode
recomputes every value after preflight and refuses to write if anything differs. Review the manifest
and logged counts before changing the mode to `write`. Run the importer separately from the server
and other maintenance jobs.

## Read-only dry run (Solr or Qdrant)

`user.preferences.import.mode` defaults to `dry-run`: use the real CSV readers, local snapshot,
group limits and aggregation without changing user profiles. Only explicit `write` mode with a
matching approval manifest permits persistence, as in the command above. Any other value fails startup.
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
Build a snapshot first using the [snapshot runbook](snapshots.md). Dry-run completes the same full
preflight and aggregation as write mode, reports projected full/partial/unmapped outcomes, writes the
approval manifest and restricted unmapped-user report, and makes no profile writes. Missing vectors
are reported as preserved/skipped, never cleared.
Completion logs include `mode=dry-run`, `snapshotLoadMs`, `csvReadMs` (parsing and spilling),
`sortMs` (merging and validation), `aggregationMs`, and `totalMs`, plus user and unique-row counts.
These dry-run timings exclude application startup and real profile persistence; write-mode timings
include actual mapped-side updates. No AI calls or remote catalogue-vector
lookups occur during daily imports. Snapshot rebuilds do read the catalogue.

Each launch writes `logs/user-import/<epoch-milliseconds>-<uuid>.log` while retaining console output.
Override it with `--logging.file.name=/path/to/import.log`. Maven output before startup is excluded.

## Completion, failures and retries

Look for `user.preferences.import.completed mode=dry-run|write`, the final acknowledgement,
`BUILD SUCCESS` and a zero exit status. Dry run reports projected full, partial, and unmapped counts
instead of calling the database writer. Clients may still contact the selected database during startup, such as Qdrant's
compatibility check; dry-run means no record mutations, not necessarily no network traffic.

Completion reports raw valid favourite/history row counts, deduplicated `uniqueRecords`, and distinct
users. `csvReadMs` includes parsing and sorted-run spilling; `sortMs` includes merging and full-input
validation; `aggregationMs` includes prepared-file reading, vector calculations and the selected sink.
`totalMs` also includes snapshot loading and temporary-file cleanup. Merge counters restart per group;
the user-progress counter tracks prepared users and can advance ahead of the current batch's save.
**Only the completion event, zero exit status, and `BUILD SUCCESS` confirm the import finished.**

Missing/corrupt/incompatible snapshots, malformed CSVs and oversized users fail before profile writes.
Database failures during explicit write mode can leave earlier batches saved; the import is not one
transaction. Fix the cause and rerun with complete intended user evidence. An abrupt process kill can
leave a uniquely named `user-import-*` directory; remove only a confirmed abandoned directory, not
the work parent or another running job's files. Snapshot cache memory is independent of CSV batch size.

## Local benchmark datasets

The interaction files under `data/import-benchmarks/` use synthetic, scenario-specific user IDs
and a pool of **200,000 unique book IDs** exported from remote Qdrant with existing dense vectors.
The pool contains the first compatible unique IDs in scroll order, not a random catalogue sample.
Exporting the IDs was read-only. User/book assignments are seeded samples without replacement
within each user; books can repeat across users.

The pool is not a snapshot-size limit: snapshot export scans the full configured catalogue and may
include additional books. Benchmark inputs contain interactions, not vector copies; the compatible
local snapshot supplies their vectors. Qdrant IDs are not guaranteed to exist in a different Solr
catalogue or snapshot.

| Dataset directory | Users | Records per user | Total records |
| --- | ---: | ---: | ---: |
| `100-users-100-records` | 100 | 100 | 10,000 |
| `1000-users-100-records` | 1,000 | 100 | 100,000 |
| `10000-users-100-records` | 10,000 | 100 | 1,000,000 |
| `100-users-1000-records` | 100 | 1,000 | 100,000 |
| `1000-users-1000-records` | 1,000 | 1,000 | 1,000,000 |
| `100-users-10000-records` | 100 | 10,000 | 1,000,000 |
| `100000-users-100-records` | 100,000 | 100 | 10,000,000 |
| `50000-users-20million-records` | 50,000 | 100–10,000; mean 400 | 20,000,000 |

The eight scenarios contain **33,210,000 records in total**. Each contains `favourites.csv` and
`history.csv`. Per user, favourites are `floor(recordCount / 5)` and history contains the remainder.
No book repeats for that user across the two files. This deliberately avoids repeated-book caching
within one user's evidence; it is not a claim that real users never revisit books.

The original six scenarios use seed `20261029` and UTC history dates within two years ending
2026-10-28. The two large scenarios use seed `29102026` and the fixed history timestamp
2026-10-01 00:00:00 UTC. The manifest records exact favourite/history and distinct-book counts.

### 50,000-user distribution

The variable scenario contains exactly 20 million records. Its per-user counts use scaled,
capped exponential-cubed random weights, a 100-record minimum and a 10,000-record maximum,
with final adjustments to meet the exact total. It is intentionally skewed toward lighter users,
not uniformly distributed between the bounds or calibrated to real usage.

| Records per user | Users | Percentage of users |
| --- | ---: | ---: |
| 100–199 | 35,253 | 70.506% |
| 200–499 | 7,558 | 15.116% |
| 500–999 | 3,246 | 6.492% |
| 1,000–1,999 | 2,048 | 4.096% |
| 2,000–4,999 | 1,333 | 2.666% |
| 5,000–10,000 | 562 | 1.124% |
| **Total** | **50,000** | **100.000%** |

Local supporting files are `manifest.json` and `book-ids.txt` at the benchmark root, plus
`user-record-counts.csv` in each large scenario. The variable scenario also has
`user-record-distribution.csv`. These datasets, snapshots and timing logs are ignored by Git;
they are not automatically generated or downloaded when cloning or running tests.

### Three-run benchmark procedure

Requirements:

- Java 21, the eight local datasets, 3 GB heap, and temporary disk comparable to the inputs;
- the same machine, Git revision, configuration, and compatible snapshot for every run;
- no competing maintenance jobs;
- Qdrant containing the benchmark IDs, or Solr containing those same IDs and compatible vectors.

The supplied IDs were exported from Qdrant, so **do not report Solr results unless all benchmark IDs
exist in the selected Solr catalogue and its snapshot**. The command below benchmarks Qdrant. Run all
scenarios sequentially in `dry-run`; each invocation starts a fresh JVM and reloads the snapshot.

```bash
mkdir -p logs/user-import
datasets=(
  100-users-100-records
  1000-users-100-records
  10000-users-100-records
  100-users-1000-records
  1000-users-1000-records
  100-users-10000-records
  100000-users-100-records
  50000-users-20million-records
)

for run in 1 2 3; do
  for dataset in "${datasets[@]}"; do
    AUDIOBOOK_DATABASE=qdrant USER_PREFERENCES_IMPORT_ON_STARTUP=true \
    ./mvnw spring-boot:run \
      -Dspring-boot.run.profiles=dev \
      -Dspring-boot.run.jvmArguments="-Xmx3g" \
      -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
      -Dspring-boot.run.arguments="--user.preferences.import.mode=dry-run --user.preferences.favourites-csv-path=data/import-benchmarks/${dataset}/favourites.csv --user.preferences.history-csv-path=data/import-benchmarks/${dataset}/history.csv --logging.file.name=logs/user-import/benchmark-${dataset}-run-${run}.log" \
      || exit 1
  done
done
```

**Do not use `write` for benchmark users.** Dry-run timings include
snapshot loading, CSV parsing and spilling, disk merge and validation, and vector aggregation. They
exclude application startup and real database writes. After all 24 successful runs, collect the
completion and snapshot identity lines:

```bash
rg "user.preferences.import.completed|book.vector.snapshot.loaded" \
  logs/user-import/benchmark-*.log
```

Use `totalMs` from `user.preferences.import.completed` for each run and calculate the arithmetic
mean of the three values. Keep `snapshotLoadMs`, `csvReadMs`, `sortMs`, and `aggregationMs` so stage
differences can be investigated rather than hidden by the average.

### Three-run results

| Dataset | Run 1 `totalMs` | Run 2 `totalMs` | Run 3 `totalMs` | Average `totalMs` |
| --- | ---: | ---: | ---: | ---: |
| `100-users-100-records` | — | — | — | — |
| `1000-users-100-records` | — | — | — | — |
| `10000-users-100-records` | — | — | — | — |
| `100-users-1000-records` | — | — | — | — |
| `1000-users-1000-records` | — | — | — | — |
| `100-users-10000-records` | — | — | — | — |
| `100000-users-100-records` | — | — | — | — |
| `50000-users-20million-records` | — | — | — | — |

Earlier single-run reference measurements on the demo machine with a 3 GB heap on 2026-10-29 were
3m 32s for 100,000 users × 100 records and 6m 52s for the variable 50,000-user dataset. These are
not substitutes for the controlled three-run averages. Generated datasets and benchmark logs are
ignored by Git and are not included in a fresh clone.

