# Catalogue embeddings

[Job index](README.md) · [Shared operation and progress](../maintenance.md) · [Configuration](../configuration.md)

## Purpose

Generate normalized catalogue vectors for semantic retrieval and user preferences. The default fills
missing dense vectors; `rebuild-all` replaces them. Qdrant also creates sparse vectors for processed
books.

## Prerequisites

- Java 21, configured database connections, and OpenAI credentials.
- An existing populated collection with a stored Solr vector field or Qdrant named-vector schema;
  its dimensions must match `embedding.dimensions` and the configured model.
- Source book text suitable for embedding; unusable text is skipped.
- Write access and space for the published file and temporary replacement in the persistent
  [snapshot directory](snapshots.md).
- A stable catalogue and no concurrent migration or summary writers. Redis and user-preference
  adapters are not required.

**There is no dry-run: selected books incur embedding charges and receive vectors.**

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

**The record limit bounds embedding generation only; the subsequent snapshot scans the full
catalogue.** Input text uses title, authors, narrators, language, and full description.

Missing-only checks the configured Solr field or Qdrant `dense` vector. Sparse-only Qdrant records
still qualify; existing dense vectors are not rebuilt merely to add sparse vectors. A no-op still
exports the snapshot, and Solr may scan stored vector fields. Missing-only avoids generation charges
for existing dense vectors, not database reads.

To deliberately regenerate the complete catalogue:

```bash
AUDIOBOOK_EMBEDDING_INDEX_ENABLED=true AUDIOBOOK_DATABASE=qdrant \
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dapplication.main-class=com.gen3.embeddingjob.EmbeddingIndexApplication \
  -Dspring-boot.run.arguments="--audiobook.embedding-index.mode=rebuild-all --audiobook.embedding-index.maximum-records=0"
```

**This replaces vectors and incurs generation charges again.** Invalid modes fail startup.

### Edited books and model changes

Missing-only cannot detect changed text or model provenance because records store no model ID or text
hash. **After changing text construction, model, or dimensions, prepare a compatible target and run
an unbounded rebuild.** A limited rebuild can leave incompatible mixed vectors.

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

Live query embeddings are independent of this job.

## Confirm completion

Expect `catalogue.embedding.completed`, then `book.vector.snapshot.published`, and finally
`BUILD SUCCESS` with a zero exit status. **The embedding event alone is not launcher success.**
Embedding events report `mode=MISSING_ONLY|REBUILD_ALL`, `scanned` and `indexed`. `scanned` counts
records returned by the selected mode, including unusable text; it is not always the catalogue total.
If only snapshot publication failed, use the standalone snapshot command rather than restarting
embedding generation. Earlier vector writes are not rolled back on failure.

