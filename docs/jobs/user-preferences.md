# User-preference import

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Build stored favourites and listening-history vectors from CSV interactions and a local book-vector
snapshot. **The job makes no AI calls. Run `dry-run` before every `write`.**

## Prerequisites

- Java 21 and two UTF-8 CSVs matching the [input contract](#csv-input-contract). Use a header-only
  file when one side has no records.
- A compatible local snapshot for the selected backend, collection, embedding model, and dimensions.
  Build or refresh it with the [snapshot job](snapshots.md).
- `AUDIOBOOK_DATABASE=solr` or `qdrant` and access to its separate user-preference collection. Solr
  requires the [preference schema](../configuration.md#solr).
- Heap for the full snapshot and temporary disk comparable to the CSV inputs. The documented demo
  uses `-Xmx3g`; 200,000 × 1,536 float vectors use about 1.14 GiB before object/map overhead.

OpenAI and Redis are not required. Dry-run can still contact the selected database during startup,
but it never changes profiles.

## What the import changes

The importer pins the last compatible snapshot, externally sorts unordered CSV rows, validates the
complete input, then processes whole users. Duplicate favourites collapse; duplicate history keeps
the latest interaction. Favourites use a normalized average, while history weights halve every
180 days before normalization.

| Rule | Behaviour |
| --- | --- |
| Mapped favourites/history side | Replaces that stored side |
| Missing or completely unmapped side | Preserves the existing stored side |
| User with no mapped vectors | Skipped; existing profile is unchanged |
| User absent from both files | Unchanged |
| Maximum distinct books per user/group | 100,000 by default; excess fails before writes |
| In-memory input target | About 100,000 rows; a user is never split |
| Profile write batch | 500 users; earlier batches remain if a later batch fails |

Before writing, the job calculates all full, partial, and unmapped outcomes. It aborts if unmapped
users exceed `user.preferences.import.max-unmapped-user-ratio` (10% by default). Their IDs go to an
owner-only `unmapped-users.txt`; normal logs contain counts only. Partial evidence can still produce
a profile, so also confirm snapshot coverage and freshness.

## CSV input contract

Supply both files with headers. Standard CSV quoting is supported; extra columns and non-`book`
rows are ignored.

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

- `user` is the external user ID used by `X-User-Id`: nonblank and at most 256 Java characters.
- `item` is `provider:book:id[:optional title]`; `rnib:book:123:Title` becomes `rnib_123`.
- Dates use `yyyy-MM-dd HH:mm:ss` in UTC. `created_at` is required; a nonblank `updated_at` other
  than `NULL` overrides it.
- Malformed input or an oversized user fails preparation before profile writes.

The shared readers and normalization live in `com.gen3.shared.csv`. Missing snapshot books are
ignored; they are never interpreted as instructions to clear a stored vector.

## Run safely

### 1. Dry run

Run the complete calculation without profile writes:

```bash
AUDIOBOOK_DATABASE=qdrant USER_PREFERENCES_IMPORT_ON_STARTUP=true \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Xmx3g" \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.import.mode=dry-run --user.preferences.favourites-csv-path=/absolute/path/favourites.csv --user.preferences.history-csv-path=/absolute/path/history.csv"
```

Use `AUDIOBOOK_DATABASE=solr` for Solr. Review the logged full/partial/unmapped counts and restricted
unmapped-user report. A successful dry run creates an owner-only approval manifest at
`./data/user-import-work/approved.properties` by default. It records the input and snapshot hashes,
snapshot identity, and projected outcomes.

### 2. Write

Use the **same files, snapshot, backend, and configuration**, changing only the mode:

```bash
AUDIOBOOK_DATABASE=qdrant USER_PREFERENCES_IMPORT_ON_STARTUP=true \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Xmx3g" \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.import.mode=write --user.preferences.favourites-csv-path=/absolute/path/favourites.csv --user.preferences.history-csv-path=/absolute/path/history.csv"
```

**Write mode changes profiles.** It recomputes the preflight and refuses to write unless the exact
files, snapshot, identity, and outcomes match the approved dry run. Run it separately from the
recommendation server and other maintenance jobs. Any mode other than `dry-run` or `write` fails.

## Completion and recovery

Success requires `user.preferences.import.completed`, the final acknowledgement, `BUILD SUCCESS`,
and a zero exit status. The event reports:

- mode, input rows, unique rows, users, and full/partial/unmapped outcomes;
- `snapshotLoadMs`, `csvReadMs`, `sortMs`, `aggregationMs`, and `totalMs`.

Each launch also writes `logs/user-import/<epoch-milliseconds>-<uuid>.log`. Override the path with
`--logging.file.name=/path/to/import.log`.

Invalid CSVs, incompatible snapshots, and oversized users fail before writes. A database failure in
write mode can leave earlier idempotent batches saved; fix the cause and rerun the same approved
import. An abrupt kill can leave a `user-import-*` temporary directory. Remove only a confirmed
abandoned run directory, never the work-directory root or another active run.

## Benchmark datasets and results

The eight local scenarios under `data/import-benchmarks/` contain synthetic users and seeded
assignments from a 200,000-ID Qdrant catalogue pool. Books do not repeat within one user's evidence,
but can repeat across users. Each scenario contains `favourites.csv` and `history.csv`; favourites
are one fifth of each user's records.

| Dataset | Users | Records/user | Total records |
| --- | ---: | ---: | ---: |
| `100-users-100-records` | 100 | 100 | 10,000 |
| `1000-users-100-records` | 1,000 | 100 | 100,000 |
| `10000-users-100-records` | 10,000 | 100 | 1,000,000 |
| `100-users-1000-records` | 100 | 1,000 | 100,000 |
| `1000-users-1000-records` | 1,000 | 1,000 | 1,000,000 |
| `100-users-10000-records` | 100 | 10,000 | 1,000,000 |
| `100000-users-100-records` | 100,000 | 100 | 10,000,000 |
| `50000-users-20million-records` | 50,000 | 100–10,000; mean 400 | 20,000,000 |

The final scenario is deliberately skewed: about 70.5% of users have 100–199 records, while 1.1%
have 5,000–10,000. It is not calibrated to production usage. The scenarios contain 33.21 million
records in total. Supporting manifests record exact counts and distributions; the first six use
seed `20261029` and the two large scenarios use `29102026`. **Datasets, snapshots, and logs are local
artifacts ignored by Git; a fresh clone does not include or generate them.**

The IDs came from Qdrant, so do not report Solr results unless the same IDs and compatible vectors
exist there. Benchmark only in `dry-run`; never write these synthetic users.

### Three-run command

Run on the same machine, revision, configuration, and snapshot, without competing jobs:

```bash
mkdir -p logs/user-import
datasets=(
  100-users-100-records 1000-users-100-records 10000-users-100-records
  100-users-1000-records 1000-users-1000-records 100-users-10000-records
  100000-users-100-records 50000-users-20million-records
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

Use the last successful `user.preferences.import.completed` event in each log. Average `totalMs`
from the three runs, and retain the stage timings for diagnosis.

```bash
rg "user.preferences.import.completed|book.vector.snapshot.loaded" \
  logs/user-import/benchmark-*.log
```

### Recorded dry-run results

Recorded on **2 November 2026** on the demo machine with the Qdrant snapshot and a 3 GiB heap.
Times include snapshot loading, parsing/spilling, merge/validation, aggregation, and cleanup; they
exclude startup and database writes and are not production-capacity measurements.

| Dataset | Run 1 (s) | Run 2 (s) | Run 3 (s) | Average (s) |
| --- | ---: | ---: | ---: | ---: |
| `100-users-100-records` | 44.48 | 33.74 | 34.84 | 37.69 |
| `1000-users-100-records` | 35.93 | 34.85 | 34.60 | 35.12 |
| `10000-users-100-records` | 56.23 | 49.18 | 49.96 | 51.79 |
| `100-users-1000-records` | 34.82 | 35.38 | 34.37 | 34.86 |
| `1000-users-1000-records` | 50.74 | 49.90 | 48.11 | 49.58 |
| `100-users-10000-records` | 50.00 | 48.86 | 52.39 | 50.41 |
| `100000-users-100-records` | 225.53 | 301.31 | 234.20 | 253.68 |
| `50000-users-20million-records` | 456.81 | 444.81 | 444.95 | 448.86 |
