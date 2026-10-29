# Catalogue embeddings

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

**Purpose:** Read existing books and regenerate their normalized dense vectors. Qdrant indexing
also builds sparse keyword vectors. The selected database supplies both reads and writes.

**Before running:** Supply OpenAI credentials and an existing collection with a stored Solr vector
field or Qdrant named-vector schema. Its dimensions must match `embedding.dimensions`.
Redis and user-preference adapters are not required.
Provide write access to the persistent [snapshot directory](snapshots.md). This job has no dry-run
mode: the command below incurs embedding charges and replaces vectors for the scanned records.

## Run

Index up to 100 existing Solr records; replace `solr` with `qdrant` for that database:

```bash
AUDIOBOOK_EMBEDDING_INDEX_ENABLED=true AUDIOBOOK_DATABASE=solr \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.embeddingjob.EmbeddingIndexApplication \
  -Dspring-boot.run.arguments="--audiobook.embedding-index.maximum-records=100"
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `audiobook.embedding-index.page-size` | `100` | Records read per page |
| `audiobook.embedding-index.maximum-records` | `0` | Maximum records scanned; zero processes all |

The record limit bounds embedding generation, not the subsequent snapshot export: snapshot export
scans the entire catalogue. Keep all vectors compatible before publishing, especially after a model
or dimension change. Input text uses title, authors, narrators, language and full description.

## Writes and retry behaviour

- IDs, metadata, custom fields, and short descriptions are preserved. Blank records are skipped.
- An embedding failure prevents that page's writes; earlier pages remain saved.
- Solr embedding writes require the version read before generation. A deleted or edited record
  rejects the stale update instead of recreating a partial document or attaching an outdated vector.
  Earlier updates may already be saved; rerun against the current catalogue after resolving the conflict.
- Rerunning starts at the beginning and regenerates existing vectors, incurring charges again.
- After a successful pass, a separate full-catalogue snapshot export runs. A failed export leaves
  the previous snapshot intact and fails the launcher. Retry only the [snapshot rebuild](snapshots.md#build-or-refresh-the-local-snapshot)
  to avoid paying for embedding generation again. Changing models requires a complete compatible
  catalogue reindex before publishing; existing records have no stored model identifier to verify.
- The process closes its clients when finished. It cannot run alongside migration or summary generation.

Implementation lives in `com.gen3.embeddingjob`; shared catalogue records and persistence live in
`com.gen3.catalogue`. Adding a backend requires an `AudiobookEmbeddingStore` adapter and snapshot
source/identity wiring; the current snapshot configuration accepts only Solr and Qdrant.
Live search-query embeddings are independent of this job.

## Confirm completion

Expect `catalogue.embedding.completed`, then `book.vector.snapshot.published`, and finally
`BUILD SUCCESS` with a zero exit status. The first event alone does not mean the launcher succeeded.
If only snapshot publication failed, use the standalone snapshot command rather than restarting
embedding generation. Earlier vector writes are not rolled back on failure.

