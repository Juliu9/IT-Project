package com.gen3.recommenderagent.storage.audiobook.migration;

import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Copies the complete Solr audiobook catalogue and its normalized embeddings into Qdrant. */
@Component
@ConditionalOnProperty(name = "audiobook.qdrant.migration.enabled", havingValue = "true")
public class SolrToQdrantMigrator implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrToQdrantMigrator.class);

  private final SolrAudiobookRepository source;
  private final QdrantAudiobookRepository destination;
  private final EmbeddingIndexer embeddingIndexer;
  private final int batchSize;

  /** Creates an opt-in, paginated migration from the retained Solr adapter to Qdrant. */
  public SolrToQdrantMigrator(
      SolrAudiobookRepository source,
      QdrantAudiobookRepository destination,
      EmbeddingIndexer embeddingIndexer,
      @Value("${audiobook.qdrant.migration.batch-size:100}") int batchSize) {
    this.source = source;
    this.destination = destination;
    this.embeddingIndexer = embeddingIndexer;
    this.batchSize = Math.max(batchSize, 1);
  }

  /** Reads every Solr page and idempotently upserts each book into Qdrant. */
  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    destination.ensureCollection();
    int offset = 0;
    int migrated = 0;
    while (true) {
      List<AudiobookRecord> books = source.findAllBooks(offset, batchSize);
      if (books.isEmpty()) {
        break;
      }

      List<AudiobookEmbedding> batch = new ArrayList<>();
      for (AudiobookRecord book : books) {
        float[] vector = embeddingIndexer.ensureAudiobookEmbedding(book);
        if (vector.length > 0) {
          batch.add(new AudiobookEmbedding(book, vector));
        }
      }
      destination.indexEmbeddings(batch);
      migrated += batch.size();
      offset += books.size();
      LOGGER.info("Migrated {} audiobook vectors from Solr to Qdrant", migrated);

      if (books.size() < batchSize) {
        break;
      }
    }
    LOGGER.info("Solr-to-Qdrant migration complete: {} vectors", migrated);
  }
}
