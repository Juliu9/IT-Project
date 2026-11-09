# Read-history import and exclusion

[Job index](README.md) · [Configuration](../configuration.md) · [User preferences](user-preferences.md)

## Purpose

This job is **only a utility for populating the read-history database from a CSV**. Recommendation
exclusion does not depend on this importer: it works as long as the configured database contains
the user's read book IDs and its adapter implements the documented read contract.

The supplied importer is one way to populate that database and will likely not be used in
production. A production system would normally write read IDs from its existing listening-history
or event pipeline instead. Requests load only the current user's IDs, never all users' history at
startup. Without this store, preference vectors describe taste but cannot identify which individual
books to exclude.

Every valid history row counts as read, including a just-started book. Exclusion matches the exact
catalogue ID, not other editions of the same work. Favourites alone do not mark books as read.

## Prerequisites

- Java 21, PostgreSQL, and read/write credentials for the importer.
- Explicitly apply [read-history-schema.sql](../../postgres/read-history-schema.sql) as a schema
  owner. Neither the server nor importer creates tables or indexes automatically.
- A history CSV using the existing [CSV contract](user-preferences.md#csv-input-contract):
  `type,user,item,created_at,updated_at`. Favourites are not required by this job.
- Configure `READ_HISTORY_JDBC_URL`, `READ_HISTORY_USERNAME`, and `READ_HISTORY_PASSWORD` through
  the environment or dev `.env`. Do not put passwords in command arguments.

For example, with PostgreSQL's `PGHOST`, `PGPORT`, `PGDATABASE`, and `PGUSER` configured, run:

```bash
psql -v ON_ERROR_STOP=1 -f postgres/read-history-schema.sql
```

Use PostgreSQL's password prompt or a protected password file. This command writes the schema to
the selected history database. The recommendation role needs `SELECT` only; the importer role needs
`INSERT` as well. Grant table/schema access through your database administrator's usual process.

The importer needs no OpenAI credentials, Redis, Solr, Qdrant, embeddings, or vector snapshot.

### Optional local PostgreSQL

For development, add `READ_HISTORY_USERNAME=read_history` and a strong `READ_HISTORY_PASSWORD` to
the root `.env`. The default JDBC URL already points to this database. Start the separate local
service, then apply the schema explicitly:

```bash
docker compose -f compose.history.yaml up -d
docker compose -f compose.history.yaml exec -T history \
  psql -U read_history -d read_history -v ON_ERROR_STOP=1 < postgres/read-history-schema.sql
```

The data volume persists across container restarts. Do not delete it to restart the service.
The development account owns the database; use separate read-only/import roles in deployments.

## Import (writes history)

**This optional CSV import is a population tool, not a required part of the production request
path.**

Run from the repository root:

```bash
READ_HISTORY_IMPORT_ENABLED=true ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.readhistoryjob.ReadHistoryApplication \
  -Dspring-boot.run.arguments="--read-history.import.csv-path=/absolute/path/history.csv"
```

This is an additive import, with no dry-run mode. Each `(user_id, book_id)` pair is unique; repeated
rows, listening updates, rereads, and reimports do not create duplicate exclusions. Normalization
reuses the existing reader: `rnib:book:123:Title` becomes `rnib_123`. Timestamps are validated using
the existing CSV contract, but are not stored or used as exclusion identities. Missing catalogue
books are retained without looking them up. Rows with another `type` are ignored.

Rows absent from the CSV remain stored. Importing a partial file cannot erase previous history.
Deletion/correction of an incorrectly recorded read book is not provided by this job.

## Progress and recovery

The shared ten-second heartbeat reports job `read.history.import`. Each successful batch also logs
`read.history.import.progress committedRows=...`. The default batch size is 1,000 CSV book rows.
The counter includes duplicate rows successfully processed; it is not the number of new table rows.
There is no percentage/ETA because the whole CSV is not counted first.

Expect `read.history.import.completed ... mode=ADDITIVE`, `BUILD SUCCESS`, and a zero exit status.
Malformed required values fail with the CSV record number. Each batch is transactional; earlier
batches remain committed after a later parsing/database failure. Fix the cause and rerun the file;
unique keys make reimport safe. A lost commit acknowledgement may mean that batch committed already.
The importer keeps only the current batch in memory; it does not sort or load the entire file.

Recommendations can run during this import. A request sees history committed when its lookup runs;
later commits affect subsequent requests. Do not run other maintenance launchers in this process.

## Enable recommendation exclusions

Set `READ_HISTORY_ENABLED=true` for the recommendation server and supply the history database
connection. It is disabled by default until that database is prepared. The JDBC pool is lazy and
bounded (default five connections); no history is loaded at startup and there is no Redis history
cache. Redis still owns conversation sessions only.

Each recommendation-producing request loads one bounded user-history snapshot. Retrieval excludes
read IDs together with session shown IDs and any similarity reference. This applies to all retrieval
modes, including non-personalised searches. Cached “More” pages are checked too, and refill when
unread cached books cannot fill the requested count. Read IDs are not persisted in session turns or
sent to the AI parser. Repeat/explain retain their original recommendations without history lookup.
If a successful exclusion leaves no matches, the API returns no recommendations; it does not relax
the rule to include read books.

Lookup failure logs `read.history.exclusion.skipped` with `requestId` and `reason=STORE_UNAVAILABLE`,
then continues without history exclusions for that request. Histories above the configurable initial
10,000-book limit log `HISTORY_LIMIT_EXCEEDED` and also fail open. They are never silently truncated.
Session shown-book exclusions still apply. Therefore this feature does **not** guarantee unread-only
results during outages or for users above the configured limit. Lookup fetches at most limit + 1 IDs
to detect oversized users without loading their entire history. It does not discard stored entries.

## Replace the client's database adapter

Implement `com.gen3.shared.readhistory.port.ReadHistoryRepository` as a Spring bean. Its bounded lookup returns
up to the requested number of distinct catalogue book IDs; unknown users return an empty set and
storage failures throw `IOException`. The supplied PostgreSQL reader/pool backs off when a custom
reader is registered. The client need not adopt the CSV importer or write interface.

`ReadHistoryWriter` is the separate additive write contract. Register a writer component in
`com.gen3.readhistoryjob.writer` to replace the standalone importer's PostgreSQL writer. The
default writer and its connection pool back off when a replacement is present. The import
updates user/book pairs only.

Both contracts live in `shared.readhistory.port`; PostgreSQL persistence and pool/configuration
live in `shared.readhistory.postgres`. The launcher/importer remain in `readhistoryjob`. Both this
job and preference-vector import reuse `shared.csv` readers, input records, and normalization.

## Verification and scale

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw test \
  -Dtest=ReadHistoryPostgresIntegrationTest,ReadHistoryExclusionsTest,ReadHistoryPagingTest,ReadHistoryFlowTest,ReadHistoryConfigurationTest
```

Docker is required for the disposable PostgreSQL/Solr/Qdrant tests. No shared database or paid AI
calls are used. Integration checks include imports, reruns, partial failures, actual launcher startup,
and history/filter benchmarks at 100, 1,000, and 10,000 IDs. Benchmark output reports indexed history
lookup and catalogue-filter timings separately. Its small catalogue tests transport/filter overhead,
not production throughput for 100,000 users or a 10–100-million-row history table. Size storage and
load-test real concurrent traffic before claiming production capacity.
