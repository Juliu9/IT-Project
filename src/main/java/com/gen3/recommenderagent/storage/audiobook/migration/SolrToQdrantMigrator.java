package com.gen3.recommenderagent.storage.audiobook.migration;

import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCatalogueRepository;
import com.gen3.recommenderagent.storage.audiobook.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookVectorIndexer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Copies the complete Solr audiobook catalogue and its normalized embeddings into Qdrant. */
@Component
@ConditionalOnProperty(name = "audiobook.qdrant.migration.enabled", havingValue = "true")
public class SolrToQdrantMigrator implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrToQdrantMigrator.class);

  private final AudiobookCatalogueRepository source;
  private final AudiobookVectorIndexer destination;
  private final EmbeddingIndexer embeddingIndexer;
  private final int batchSize;
  private final int maximumRecords;

  /** Creates an opt-in, paginated migration from the retained Solr adapter to Qdrant. */
  public SolrToQdrantMigrator(
      @Qualifier("solrAudiobookRepository") AudiobookCatalogueRepository source,
      @Qualifier("qdrantAudiobookRepository") AudiobookVectorIndexer destination,
      EmbeddingIndexer embeddingIndexer,
      @Value("${audiobook.qdrant.migration.batch-size:100}") int batchSize,
      @Value("${audiobook.qdrant.migration.max-records:0}") int maximumRecords) {
    this.source = source;
    this.destination = destination;
    this.embeddingIndexer = embeddingIndexer;
    this.batchSize = Math.max(batchSize, 1);
    this.maximumRecords = Math.max(maximumRecords, 0);
  }

  /** Reads every Solr page and idempotently upserts each book into Qdrant. */
  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    destination.initialize();
    int offset = 0;
    int migrated = 0;
    while (true) {
      int remaining = maximumRecords == 0 ? batchSize : maximumRecords - migrated;
      if (remaining <= 0) {
        break;
      }
      int pageLimit = Math.min(batchSize, remaining);
      List<AudiobookRecord> books = source.findAllBooks(offset, pageLimit);
      if (books.isEmpty()) {
        break;
      }

      List<AudiobookEmbedding> batch = embeddingIndexer.ensureAudiobookEmbeddings(books);
      destination.indexEmbeddings(batch);
      migrated += batch.size();
      offset += books.size();
      LOGGER.info("Migrated {} audiobook vectors from Solr to Qdrant", migrated);

      if (books.size() < pageLimit) {
        break;
      }
    }
    LOGGER.info("Solr-to-Qdrant migration complete: {} vectors", migrated);
  }
}
