# Catalogue embeddings

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Make catalogue books usable for semantic retrieval and vector-based user preferences by generating
normalized dense vectors. Without this job, another tool would need to generate and load compatible
book vectors before those features could use them.

Fill missing vectors by default, or explicitly rebuild all vectors.
For books being embedded, Qdrant also builds sparse keyword vectors. The selected database supplies
both reads and writes; existing dense vectors are skipped in the default mode.

## Prerequisites

- Java 21, configured database connections, and OpenAI credentials.
- An existing populated collection with a stored Solr vector field or Qdrant named-vector schema;
  its dimensions must match `embedding.dimensions` and the configured model.
- Source book text suitable for embedding; unusable text is skipped.
- Write access and space for the published file and temporary replacement in the persistent
  [snapshot directory](snapshots.md).
- A stable catalogue and no concurrent migration or summary writers. Redis and user-preference
  adapters are not required.

This job has no dry-run mode: selected books incur embedding charges and receive vectors.

## Run

Fill up to 100 missing-vector Solr records; replace `solr` with `qdrant` for that database:

```bash
AUDIOBOOK_EMBEDDING_INDEX_ENABLED=true AUDIOBOOK_DATABASE=solr \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.embeddingjob.EmbeddingIndexApplication \
  -Dspring-boot.run.arguments="--audiobook.embedding-index.mode=missing-only --audiobook.embedding-index.maximum-records=100"
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `audiobook.embedding-index.mode` | `missing-only` | Fill absent dense vectors; `rebuild-all` intentionally replaces existing vectors |
| `audiobook.embedding-index.page-size` | `100` | Records read per page |
| `audiobook.embedding-index.maximum-records` | `0` | Maximum records scanned in the selected mode; zero processes all matching records |

The record limit bounds embedding generation, not the subsequent snapshot export: snapshot export
scans the entire catalogue. Keep all vectors compatible before publishing, especially after a model
or dimension change. Input text uses title, authors, narrators, language and full description.

Missing-only tests existence of the configured Solr vector field or Qdrant's named `dense` vector,
not whether any vector exists. A Qdrant book with only a sparse keyword vector is still missing its
dense vector. Existing dense vectors are not re-embedded to repair missing sparse vectors. If no
matching books exist, no generation or vector updates occur; the full snapshot export still runs.
Qdrant performs presence filtering at the database without returning existing vectors. Solr 9.6
requires a bounded scan of stored vector fields to handle segments with no indexed vectors safely;
even a no-op can therefore read the full catalogue. Neither mode is a free database scan, but
missing-only avoids embedding charges for books that already have dense vectors.

To deliberately regenerate the complete catalogue:

```bash
AUDIOBOOK_EMBEDDING_INDEX_ENABLED=true AUDIOBOOK_DATABASE=qdrant \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.embeddingjob.EmbeddingIndexApplication \
  -Dspring-boot.run.arguments="--audiobook.embedding-index.mode=rebuild-all --audiobook.embedding-index.maximum-records=0"
```

This replaces vectors and incurs generation charges for eligible books again. Mode values other
than `missing-only` and `rebuild-all` fail startup instead of silently selecting a write policy.

### Edited books and model changes

Missing-only does not detect changed text or verify the provenance of existing vectors. Use an
intentional rebuild after changing embedding input text, model, dimensions or text construction.
Database records do not store a model identifier or text hash, so this job cannot automatically
prove that existing vectors match the configured model. Do not use missing-only to switch models.
Prepare a compatible target schema/collection and complete an unbounded rebuild before publishing
or serving the changed model. A record-limited model rebuild can leave incompatible mixed vectors;
coordinate serving and import jobs during that operation.

## Writes and retry behaviour

- IDs, metadata, custom fields, and short descriptions are preserved. Blank records are skipped.
- An embedding failure prevents that page's writes; earlier pages remain saved.
- Solr embedding writes require the version read before generation. A deleted or edited record
  rejects the stale update instead of recreating a partial document or attaching an outdated vector.
  Earlier updates may already be saved; rerun against the current catalogue after resolving the conflict.
- Missing-only reruns start another filtered scan, skipping already-filled books. Rebuild-all reruns
  regenerate eligible books from the beginning and incur charges again.
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
Embedding events report `mode=MISSING_ONLY|REBUILD_ALL`, `scanned` and `indexed`. `scanned` counts
records returned by the selected mode, including unusable text; it is not always the catalogue total.
If only snapshot publication failed, use the standalone snapshot command rather than restarting
embedding generation. Earlier vector writes are not rolled back on failure.

