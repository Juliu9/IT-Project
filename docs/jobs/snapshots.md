# Book-vector snapshots

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Export book vectors to a local binary file used by preference imports. The embedding launcher
refreshes it automatically; this job refreshes or retries the export without generating embeddings.

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
AUDIOBOOK_DATABASE=qdrant \
QDRANT_COLLECTION=audiobooks_hybrid \
QDRANT_TIMEOUT=120s \
./mvnw spring-boot:run \
  -Dapplication.main-class=com.gen3.snapshotjob.BookVectorSnapshotApplication \
  -Dspring-boot.run.profiles=dev
```

For Solr, use `AUDIOBOOK_DATABASE=solr` and set `SOLR_COLLECTION` to the catalogue collection.
The built-in defaults are `audiobooks_hybrid` for Qdrant and `combinedbooks` for Solr; declaring the
collection in an operational command makes the source unambiguous. **The job reads catalogue vectors
but does not change database records.** Retry a failed post-embedding export with this command, not
the embedding job.

### How backend and collection select the source

The launcher resolves one catalogue identity from `audiobook.database` and the corresponding
`<backend>.collection` property. For example, `audiobook.database=qdrant` requires
`qdrant.collection`, while `audiobook.database=solr` requires `solr.collection`. Environment
variables use Spring's uppercase form, such as `QDRANT_COLLECTION` and `SOLR_COLLECTION`.
Defaults belong to application configuration rather than `SnapshotIdentity`, so a replacement
backend can follow the same convention without changing the identity resolver. An isolated launcher
or test configuration that does not load `application.properties` must provide its collection.

Spring then selects the matching `CatalogueSnapshotSource`. The built-in Qdrant adapter scrolls
Qdrant records; the Solr adapter queries stored vectors with pagination. Both expose the same
read-only pages of book IDs and dense vectors to the snapshot writer. Startup rejects an adapter
whose reported backend or collection differs from the configured identity. A replacement backend
must register its own `CatalogueSnapshotSource`; setting an arbitrary backend name provides snapshot
identity for offline loading but does not create database access for snapshot generation.

Files live under `data/book-vector-snapshot/<backend>/<collection-hash>.bin`, or the configured
`BOOK_VECTOR_SNAPSHOT_DIRECTORY`. Both jobs must use the same persistent directory and catalogue
settings. A snapshot is one binary file with a versioned metadata header (backend, collection,
declared embedding model, dimensions, creation time), exact float32 vectors keyed by book ID, count,
and SHA-256 checksum. It is ignored by Git. Missing vectors are excluded; an empty export is rejected.
`<collection-hash>` is the deterministic name-based UUID of the collection name. The default directory
is relative to the working directory. Use an absolute shared path if launchers run from different
directories or under a scheduler. The filename does not identify the server endpoint: changing servers
while retaining the same collection name requires an explicit rebuild.

A rebuild validates a temporary file before atomic replacement. **Failure preserves the previous
snapshot. Do not run catalogue writers during export:** pagination is not transactional.

Daily imports accept the last compatible snapshot regardless of age. They reject missing, truncated,
corrupt, dimension/model/backend/collection-incompatible snapshots before any user-profile writes.
Freshness is not inferred from a checksum: new/changed/deleted catalogue books require an explicit
refresh, including changes made outside the embedding job. Books absent from an older snapshot are
treated as unavailable. The importer preserves an existing side when none of that side's books map
to vectors and skips users with no mapped vectors; it does not clear profiles. The snapshot never
modifies the Solr/Qdrant record schemas.

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

**This is a derived vector cache, not a catalogue backup.** It has no age-based refresh, automatic
retry, or schedule.

## Test data

Snapshot tests use small fixtures for automated correctness checks. They do not require generating
new embeddings.

`BookVectorSnapshotTest` creates temporary two-dimensional vectors, such as `[1, 0]` and
`[0.6, 0.8]`, to check exact persistence, paginated exports, absent vectors, failed-refresh safety,
checksum corruption, incompatible metadata and invalid input. `SnapshotSourcesTest` checks the
Solr/Qdrant read adapters, field selection and pagination. `BookVectorSnapshotApplicationTest`
checks both backends' launcher wiring without AI models or profile writers, plus replacement-backend
publication and offline reopening, absence of built-in backend beans, and rejection of mismatched
source identity. See [adding a database adapter](../architecture.md#add-a-database-adapter) for the
snapshot source contract and collection configuration required by a new backend.

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

Synthetic interaction datasets and import-speed measurements belong to the consumer of this
snapshot. See the [user-preference benchmark](user-preferences.md#benchmark-datasets-and-results).

