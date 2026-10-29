# Maintenance operations

[README](../README.md) · [Job index](jobs/README.md) · [Configuration](configuration.md)

Individual commands, prerequisites, writes, completion checks and retry instructions live in the
[job runbooks](jobs/README.md). This page owns shared execution and progress rules.

## Before running a job

Run from the repository root with Java 21. These are dedicated non-web processes: they exit when
finished and do not schedule themselves. Normal recommendation-server startup rejects enabled
maintenance flags. Keep unrelated job flags disabled and run one maintenance writer at a time
against a stable catalogue. Jobs do not take the database offline, but scans and writes compete
with interactive requests for resources. Catalogue pagination is not a transactional snapshot;
changes behind a scan cursor may require another run.

Runbook examples use `-Dspring-boot.run.profiles=dev` to load the optional root `.env`. For
shell/IDE-supplied credentials, omit that argument. The dev website only exists in the recommendation
server; selecting the profile does not turn a job into a web server. See
[configuration loading](configuration.md#loading-and-overriding-settings). Do not put secrets in
command arguments or commit `.env`, generated datasets, snapshots, manifests, or logs.

The selected backend is Qdrant unless `AUDIOBOOK_DATABASE=solr` is supplied. Migration is the sole
job connecting to both databases. Redis is required for the server, not for these jobs. Only
embedding generation and summary-provider operations need AI credentials or incur AI charges.
The snapshot launcher needs no enable flag: choosing its dedicated main class is the opt-in.
Only user import has a `dry-run` mode; the other jobs must be treated as real operations.

## Automatic progress reporting

Maintenance jobs print `maintenance.progress` immediately and every 10 seconds while running.
This covers snapshot export/validation/loading, embedding generation, catalogue migration,
user-import preparation/aggregation, and short-description selection/generation/writes.

```text
maintenance.progress job=book.snapshot.rebuild stage=reading catalogue and writing snapshot processed=12500 total=unknown elapsedMs=60000 sinceProgressMs=1500
```

`stage` identifies current work or I/O, `processed` is the last completed counter update for that
stage, and `sinceProgressMs` is the time since the stage or counter changed. Snapshot counts advance
per export page (or every 1,000 vectors during local validation/loading). User aggregation reports
users processed and a known user total. CSV preparation updates counts every 10,000 rows;
embedding/migration counters advance after completed pages. Short-description writes log a
processed-book count after each completed request.

`total=unknown` means no exact total is available; limits are not presented as totals and no ETA is
invented. A heartbeat proves the reporter is alive, not that a pending remote operation is advancing.
An increasing `sinceProgressMs` during a read, write, or provider wait makes that lack of advancement
visible. It does not trigger retries, cancel work, query the database, or declare success.
The reporter stops automatically on success or failure. Use the job's existing completion event and
`BUILD SUCCESS` as confirmation of success. Already-running jobs must finish or be restarted before
they can use new reporting code; updating files does not instrument a running JVM.

## Catalogue embeddings

See [Catalogue embeddings](jobs/embeddings.md).

## Solr-to-Qdrant migration

See [Migration](jobs/migration.md).

## Short descriptions

See [Short descriptions](jobs/short-descriptions.md).

## User preferences

See [User-preference import](jobs/user-preferences.md).

### Read-only dry run (Solr or Qdrant)

See [Dry-run import](jobs/user-preferences.md#read-only-dry-run-solr-or-qdrant).

### Build or refresh the local snapshot

See [Snapshot rebuild](jobs/snapshots.md#build-or-refresh-the-local-snapshot).

## Solr launcher verification

The container test starts the actual embedding, snapshot, summary generation, and preference
applications, executes their startup jobs, then starts the recommendation HTTP server with Solr and
Redis. It also checks migration reads from Solr. AI and the migration destination use test doubles;
all data writes stay in disposable containers and temporary files.

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw -Dtest=SolrApplicationLaunchIntegrationTest test
```

Docker must be running. The normal `clean verify` command includes this test too.
