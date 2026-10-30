# Book-vector snapshots

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Keep existing book vectors in a persistent local binary file so daily user-preference imports
avoid repeated remote catalogue-vector lookups. The embedding launcher refreshes it automatically
after a successful pass; this separate job can refresh it independently or retry a failed export.
Without the standalone job, refreshing the snapshot would require running the embedding launcher
again, even when no embedding generation is needed.

## Prerequisites

- Java 21 and a reachable, populated Solr or Qdrant catalogue with existing compatible vectors.
- Database connection settings and credentials where required, plus the matching declared model,
  vector dimensions, backend, and collection settings.
- A writable persistent snapshot directory shared with the user importer. Allow disk space for
  both the published file and its temporary replacement.
- A stable catalogue with no concurrent writers during export.

No AI credentials, Redis, user-preference collection, or running recommendation server is needed.
This job reads existing vectors; it cannot generate missing ones.

## Build or refresh the local snapshot

```bash
AUDIOBOOK_DATABASE=qdrant QDRANT_TIMEOUT=120s ./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.snapshotjob.BookVectorSnapshotApplication \
  -Dspring-boot.run.profiles=dev
```

Use `AUDIOBOOK_DATABASE=solr` to export Solr instead. This dedicated job only reads existing vectors;
it neither generates embeddings nor changes database records. The embedding-index launcher also
runs this separate rebuild step after a successful embedding pass. A failed rebuild fails that
workflow and can be retried with this command without generating embeddings again.

Files live under `data/book-vector-snapshot/<backend>/<collection-hash>.bin`, or the configured
`BOOK_VECTOR_SNAPSHOT_DIRECTORY`. Both jobs must use the same persistent directory and catalogue
settings. A snapshot is one binary file with a versioned metadata header (backend, collection,
declared embedding model, dimensions, creation time), exact float32 vectors keyed by book ID, count,
and SHA-256 checksum. It is ignored by Git. Missing vectors are excluded; an empty export is rejected.
`<collection-hash>` is the deterministic name-based UUID of the collection name. The default directory
is relative to the working directory. Use an absolute shared path if launchers run from different
directories or under a scheduler. The filename does not identify the server endpoint: changing servers
while retaining the same collection name requires an explicit rebuild.

A rebuild streams to a separate temporary file and validates it before atomic replacement. Any
failure leaves the previous published snapshot intact; filesystems without atomic replacement fail
the rebuild rather than publishing partially. Do not run catalogue writers during export: pagination
is not a database-wide transactional snapshot. Retain the previous snapshot until a refresh succeeds.

Daily imports accept the last compatible snapshot regardless of age. They reject missing, truncated,
corrupt, dimension/model/backend/collection-incompatible snapshots before any user-profile writes.
Freshness is not inferred from a checksum: new/changed/deleted catalogue books require an explicit
refresh, including changes made outside the embedding job. Books absent from an older snapshot are
treated as unavailable; users with no available evidence can have profiles cleared in explicit write
mode. The snapshot never modifies the Solr/Qdrant record schemas.

## Confirm completion and recover

Expect `book.vector.snapshot.published` with backend, collection, exported book count and file path,
followed by `BUILD SUCCESS` and a zero exit status. The Spring `Started ...` message only confirms
startup; the export still runs afterward.

If export, validation or atomic publication fails, correct the connection/data/disk problem and rerun
this job. Do not regenerate embeddings merely to retry publication. Duplicate/missing IDs, invalid
vectors, incompatible dimensions and an empty export are rejected. The previous published file stays
in place, even when it is old. Normal failures clean their temporary file; an abrupt process kill
may leave `snapshot-*.tmp` files. Remove only confirmed abandoned temporary files, never the active
published `.bin`. A running importer pins its already-loaded snapshot until it exits.

This is a derived vector cache, not a full catalogue backup: it does not retain metadata or restore
database records. No age-based refresh, automatic retry or daily schedule is built in.

## Test data

There are two distinct kinds of test data: small fixtures for automated correctness checks and
large local interaction datasets for import timing. Neither requires generating new embeddings.

### Automated correctness fixtures

`BookVectorSnapshotTest` creates temporary two-dimensional vectors, such as `[1, 0]` and
`[0.6, 0.8]`, to check exact persistence, paginated exports, absent vectors, failed-refresh safety,
checksum corruption, incompatible metadata and invalid input. `SnapshotSourcesTest` checks the
Solr/Qdrant read adapters, field selection and pagination. `BookVectorSnapshotApplicationTest`
checks both backends' launcher wiring without AI models or profile writers.

`QdrantSnapshotIntegrationTest` creates a disposable Qdrant collection with a vector-bearing book
and a book without a vector. It checks paginated export and that database records remain unchanged.
These fixtures are created by the tests; no production snapshot or benchmark CSVs are needed.

Run from the repository root with Java 21 and Docker running:

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw test \
  -Dtest=BookVectorSnapshotTest,SnapshotSourcesTest,BookVectorSnapshotApplicationTest,QdrantSnapshotIntegrationTest
```

These tests do not call paid AI providers or use the remote benchmark catalogue. The integration
test writes only its disposable test collection. The standard non-live suite also includes them.

### Local benchmark datasets

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

### Run a benchmark safely

Build a compatible snapshot first, then run the user importer in explicit `dry-run` mode:

```bash
AUDIOBOOK_DATABASE=qdrant USER_PREFERENCES_IMPORT_ON_STARTUP=true \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Xmx3g" \
  -Dapplication.main-class=com.gen3.userpreferencejob.UserPreferenceApplication \
  -Dspring-boot.run.arguments="--user.preferences.import.mode=dry-run --user.preferences.favourites-csv-path=data/import-benchmarks/100-users-100-records/favourites.csv --user.preferences.history-csv-path=data/import-benchmarks/100-users-100-records/history.csv"
```

Replace both scenario paths together to select another dataset. This command assumes the ignored
local CSVs and snapshot exist. It calculates profiles without saving or clearing database records.
Do not use `write` for timing these synthetic users against a shared database. Startup clients may
still contact the selected database; no remote catalogue-vector lookup is used by the import itself.

Import timings include snapshot loading, CSV parsing/spilling, disk merge/validation and vector
aggregation. Dry-run results do not include actual database write costs and are not measurements
of snapshot export speed. See the [import runbook](user-preferences.md#local-benchmark-reference)
for measured timings, CSV columns, output logs and memory/disk requirements.

