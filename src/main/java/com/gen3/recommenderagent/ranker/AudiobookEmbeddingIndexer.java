/* This class handles embedding and saving audiobook records
/* This class is intended to be used during audiobook importing or re-indexing
*/
package com.gen3.recommenderagent.ranker;

import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
// Generates and stores the vector when audiobook data is imported into Solr.
public class AudiobookEmbeddingIndexer {

  private final EmbeddingService embeddingService;
  private final SolrAudiobookRepository audiobookRepository;
  private final String vectorField;

  public AudiobookEmbeddingIndexer(
      EmbeddingService embeddingService,
      SolrAudiobookRepository audiobookRepository,
      @Value("${solr.vector-field:audiobook_vector}") String vectorField) {
    this.embeddingService = embeddingService;
    this.audiobookRepository = audiobookRepository;
    this.vectorField = vectorField;
  }

  public void index(SolrDocument audiobook) throws Exception {
    // Validate that the audiobook is not null
    if (audiobook == null) {
      throw new IllegalArgumentException("Audiobook must not be null");
    }

    // Create a new SolrInputDocument
    SolrInputDocument document = new SolrInputDocument();
    // Copy the audiobook fields into the Solr input document
    audiobook.forEach(document::addField);
    // Generate an embedding through EmbeddingService
    // Sends document and vector to SolrAudiobookRepository
    audiobookRepository.indexWithEmbedding(
        document, embeddingService.embedAudiobook(audiobook), vectorField);
  }

  public int reindexAll(int batchSize) throws Exception {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("Batch size must be greater than zero");
    }

    int start = 0;
    int indexed = 0;
    while (true) {
      var results = audiobookRepository.findAudiobooks(start, batchSize).getResults();
      if (results == null || results.isEmpty()) {
        return indexed;
      }

      List<org.apache.solr.common.SolrInputDocument> updates = new ArrayList<>();
      for (SolrDocument audiobook : results) {
        Object id = audiobook.getFieldValue("id");
        if (id == null) {
          continue;
        }
        updates.add(
            audiobookRepository.vectorUpdate(
                id.toString(), embeddingService.embedAudiobook(audiobook), vectorField));
      }

      if (!updates.isEmpty()) {
        audiobookRepository.updateEmbeddings(updates);
        indexed += updates.size();
      }

      start += results.size();
      if (results.size() < batchSize) {
        return indexed;
      }
    }
  }
}
