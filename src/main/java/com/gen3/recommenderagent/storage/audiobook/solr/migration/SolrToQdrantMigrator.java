package com.gen3.recommenderagent.storage.audiobook.solr.migration;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Copies existing Solr catalogue fields and stored vectors into Qdrant without AI generation. */
@Component
@ConditionalOnProperty(name = "audiobook.qdrant.migration.enabled", havingValue = "true")
public class SolrToQdrantMigrator implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrToQdrantMigrator.class);

  private final SolrAudiobookRepository source;
  private final QdrantAudiobookRepository destination;
  private final int batchSize;
  private final int maximumRecords;

  /** Creates an opt-in, paginated migration from the retained Solr adapter to Qdrant. */
  public SolrToQdrantMigrator(
      @Qualifier("solrAudiobookRepository") SolrAudiobookRepository source,
      @Qualifier("qdrantAudiobookRepository") QdrantAudiobookRepository destination,
      @Value("${audiobook.qdrant.migration.batch-size:100}") int batchSize,
      @Value("${audiobook.qdrant.migration.max-records:0}") int maximumRecords) {
    this.source = source;
    this.destination = destination;
    this.batchSize = Math.max(batchSize, 1);
    this.maximumRecords = Math.max(maximumRecords, 0);
  }

  /** Reads every Solr page and copies records absent from Qdrant. */
  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    destination.initialize();
    int offset = 0;
    int migrated = 0;
    while (true) {
      int remaining = maximumRecords == 0 ? batchSize : maximumRecords - offset;
      if (remaining <= 0) {
        break;
      }
      int pageLimit = Math.min(batchSize, remaining);
      List<AudiobookEmbedding> books = source.findStoredBooks(offset, pageLimit);
      if (books.isEmpty()) {
        break;
      }

      migrated += destination.importStoredBooks(books);
      offset += books.size();
      LOGGER.info("Migrated {} audiobook records from Solr to Qdrant", migrated);

      if (books.size() < pageLimit) {
        break;
      }
    }
    LOGGER.info("Solr-to-Qdrant migration complete: {} records", migrated);
  }
}
