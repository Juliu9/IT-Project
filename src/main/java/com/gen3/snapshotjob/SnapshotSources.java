package com.gen3.snapshotjob;

import com.gen3.shared.audiobookcatalogue.qdrant.QdrantPointMapper;
import com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot;
import com.gen3.shared.audiobookcatalogue.solr.SolrAudiobookRepository;
import io.qdrant.client.QdrantClient;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Read-only scans of existing catalogue vectors; never generates embeddings. */
final class SnapshotSources {
  /** Prevents instantiation of this utility class. */
  private SnapshotSources() {}

  /** Creates a paginated snapshot source backed by existing Solr catalogue vectors. */
  static CatalogueSnapshotSource solr(SolrAudiobookRepository repository, String collection) {
    return identified(
        "solr",
        collection,
        (cursor, limit) -> {
          var page = repository.findStoredBooks(cursor, limit);
          return new BookVectorSnapshot.Page(
              page.stream()
                  .map(book -> new BookVectorSnapshot.Entry(book.record().id(), book.vector()))
                  .toList(),
              page.size() < limit ? null : page.getLast().record().id());
        });
  }

  /** Creates a paginated snapshot source backed by existing Qdrant catalogue vectors. */
  static CatalogueSnapshotSource qdrant(QdrantClient client, String collection) {
    return identified(
        "qdrant",
        collection,
        (cursor, limit) -> {
          var request =
              io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                  .setCollectionName(collection)
                  .setLimit(limit)
                  .setWithPayload(
                      io.qdrant.client.WithPayloadSelectorFactory.include(List.of("id")))
                  .setWithVectors(
                      io.qdrant.client.WithVectorsSelectorFactory.include(
                          List.of(QdrantPointMapper.DENSE_VECTOR)));
          if (cursor != null) {
            request.setOffset(
                io.qdrant.client.grpc.Common.PointId.parseFrom(Base64.getDecoder().decode(cursor)));
          }
          try {
            var page = client.scrollAsync(request.build()).get();
            var entries = new java.util.ArrayList<BookVectorSnapshot.Entry>();
            for (var point : page.getResultList()) {
              var dense =
                  point
                      .getVectors()
                      .getVectors()
                      .getVectorsMap()
                      .get(QdrantPointMapper.DENSE_VECTOR);
              float[] vector = null;
              if (dense != null && dense.hasDense()) {
                var values = dense.getDense().getDataList();
                vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) {
                  vector[i] = values.get(i);
                }
              }
              entries.add(
                  new BookVectorSnapshot.Entry(
                      point
                          .getPayloadMap()
                          .getOrDefault(
                              "id", io.qdrant.client.grpc.JsonWithInt.Value.getDefaultInstance())
                          .getStringValue(),
                      vector));
            }
            return new BookVectorSnapshot.Page(
                entries,
                page.hasNextPageOffset()
                    ? Base64.getEncoder().encodeToString(page.getNextPageOffset().toByteArray())
                    : null);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading Qdrant snapshot source", exception);
          } catch (ExecutionException exception) {
            throw new IOException("Could not read Qdrant snapshot source", exception.getCause());
          }
        });
  }

  /** Wraps a vector source with the backend and collection identity required by snapshot wiring. */
  private static CatalogueSnapshotSource identified(
      String backend, String collection, BookVectorSnapshot.Source source) {
    return new CatalogueSnapshotSource() {
      /** Returns the backend identifier used to match a snapshot source to its configuration. */
      public String backend() {
        return backend;
      }

      /** Returns the collection identifier used to match a snapshot source to its configuration. */
      public String collection() {
        return collection;
      }

      /** Reads the next bounded vector page from the wrapped catalogue source. */
      public BookVectorSnapshot.Page page(String cursor, int limit) throws IOException {
        return source.page(cursor, limit);
      }
    };
  }
}
