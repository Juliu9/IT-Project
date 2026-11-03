# Maintenance operations

[README](../README.md) · [Job index](jobs/README.md) · [Configuration](configuration.md)

Individual commands, prerequisites, writes, completion checks and retry instructions live in the
[job runbooks](jobs/README.md). This page owns shared execution and progress rules.

## Before running a job

Run from the repository root with Java 21. Jobs are non-web, exit when finished, and do not schedule
themselves. **Run one maintenance writer at a time against a stable catalogue.** Pagination is not a
transactional snapshot, and jobs compete with interactive traffic.

Runbook examples use `-Dspring-boot.run.profiles=dev` to load the optional root `.env`. For
shell/IDE-supplied credentials, omit that argument. The dev website only exists in the recommendation
server; selecting the profile does not turn a job into a web server. See
[configuration loading](configuration.md#loading-and-overriding-settings). Do not put secrets in
command arguments. **Never commit `.env`, generated datasets, snapshots, manifests, or logs.**

The selected backend is Qdrant unless `AUDIOBOOK_DATABASE=solr` is supplied. Migration is the sole
job connecting to both databases. Redis is required for the server, not for these jobs. Only
embedding generation and summary-provider operations need AI credentials or incur AI charges.
The snapshot launcher's main class is its opt-in. **Only user import has a dry-run mode; treat every
other job as a real operation.**

The [read-history importer](jobs/read-history.md) writes additive user/book pairs to PostgreSQL.
It needs no catalogue, preference vectors, Redis, or AI credentials and can run during serving.

## Automatic progress reporting

Maintenance jobs print `maintenance.progress` immediately and every 10 seconds while running.
This covers snapshot export/validation/loading, embedding generation, catalogue migration,
user-import preparation/aggregation, and short-description selection/generation/writes.
Read-history import reports its processed CSV rows after each committed batch.

```text
maintenance.progress job=book.snapshot.rebuild stage=reading catalogue and writing snapshot processed=12500 total=unknown elapsedMs=60000 sinceProgressMs=1500
```

`stage` is the current work, `processed` is the last completed count, and `sinceProgressMs` is time
since the stage or count changed. `total=unknown` means no exact total is available. A heartbeat
proves only that the reporter is alive; it does not retry, cancel, or prove success. **Only the job's
completion event, zero exit status, and `BUILD SUCCESS` confirm success.**

## Local log files

Every maintenance launcher keeps console output and also writes the application's output to a
unique `logs/<job>/<epoch-milliseconds>-<uuid>.log` file. The job directories are `embeddings`,
`short-descriptions`, `migration`, `snapshot`, `user-import`, and `read-history`. Logs are retained until manually
removed and are ignored by Git. Override the destination for any launch with
`--logging.file.name=/path/to/job.log`. Maven output produced before the application starts is not
included.

## Solr launcher verification

The container test starts the actual embedding, snapshot, summary generation, and preference
applications, executes their startup jobs, then starts the recommendation HTTP server with Solr and
Redis. It also checks migration reads from Solr. AI and the migration destination use test doubles;
all data writes stay in disposable containers and temporary files.

```bash
OPENAI_API_KEY=ci-placeholder ./mvnw -Dtest=SolrApplicationLaunchIntegrationTest test
```

Docker must be running. The normal `clean verify` command includes this test too.
