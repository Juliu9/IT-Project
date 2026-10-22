package com.gen3.embeddingjob;

import com.gen3.embeddingjob.store.AudiobookEmbeddingStore;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit maintenance job; never registered in the recommendation application. */
public class CatalogueEmbeddingJob {
  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogueEmbeddingJob.class);
  private final AudiobookEmbeddingStore store;
  private final AudiobookEmbeddingIndexer indexer;
  private final int pageSize;
  private final int maximumRecords;

  public CatalogueEmbeddingJob(
      AudiobookEmbeddingStore store,
      AudiobookEmbeddingIndexer indexer,
      int pageSize,
      int maximumRecords) {
    if (pageSize < 1 || maximumRecords < 0) {
      throw new IllegalArgumentException(
          "Embedding page size must be positive and maximum records nonnegative");
    }
    this.store = store;
    this.indexer = indexer;
    this.pageSize = pageSize;
    this.maximumRecords = maximumRecords;
  }

  public void run() throws Exception {
    String cursor = null;
    int scanned = 0;
    int indexed = 0;
    do {
      int limit = maximumRecords == 0 ? pageSize : Math.min(pageSize, maximumRecords - scanned);
      if (limit == 0) {
        break;
      }
      var page = store.embeddingPage(cursor, limit);
      var valid =
          page.books().stream()
              .filter(
                  book ->
                      book.record() != null
                          && book.record().id() != null
                          && !book.record().id().isBlank())
              .filter(book -> !indexer.buildAudiobookText(book.record()).isBlank())
              .toList();
      if (!valid.isEmpty()) {
        var embeddings =
            indexer.embedAudiobooks(valid.stream().map(book -> book.record()).toList());
        if (embeddings.size() != valid.size()) {
          throw new IllegalStateException("Embedding batch does not match the source records");
        }
        var updates = new ArrayList<AudiobookEmbeddingStore.Update>();
        for (int i = 0; i < valid.size(); i++) {
          updates.add(
              new AudiobookEmbeddingStore.Update(
                  valid.get(i).storageReference(), embeddings.get(i)));
        }
        store.updateEmbeddings(updates);
        indexed += updates.size();
      }
      scanned += page.books().size();
      cursor = page.nextCursor();
      LOGGER.info("catalogue.embedding.progress scanned={} indexed={}", scanned, indexed);
      if (page.books().isEmpty()) {
        break;
      }
    } while (cursor != null);
    LOGGER.info("catalogue.embedding.completed scanned={} indexed={}", scanned, indexed);
  }
}
