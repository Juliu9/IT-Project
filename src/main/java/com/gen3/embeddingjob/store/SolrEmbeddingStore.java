package com.gen3.embeddingjob.store;

import com.gen3.embeddingjob.EmbeddingMode;
import com.gen3.shared.audiobookcatalogue.solr.SolrAudiobookRepository;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Solr catalogue pagination and vector-only writes for the maintenance job. */
@Component
@ConditionalOnProperty(name = "audiobook.database", havingValue = "solr")
public class SolrEmbeddingStore implements AudiobookEmbeddingStore {
  private final SolrAudiobookRepository repository;

  /** Creates an embedding store backed by the configured Solr catalogue repository. */
  public SolrEmbeddingStore(SolrAudiobookRepository repository) {
    this.repository = repository;
  }

  /** {@inheritDoc} */
  @Override
  public Page embeddingPage(String cursor, int limit, EmbeddingMode mode) throws IOException {
    var records =
        repository.findEmbeddingRecords(cursor, limit, mode == EmbeddingMode.MISSING_ONLY);
    return new Page(records, records.size() < limit ? null : records.getLast().record().id());
  }

  /** {@inheritDoc} */
  @Override
  public void updateEmbeddings(List<Update> updates) throws IOException {
    var versions = new java.util.HashMap<String, Long>();
    for (var update : updates) {
      versions.put(update.embedding().record().id(), Long.parseLong(update.storageReference()));
    }
    repository.updateExistingEmbeddings(updates.stream().map(Update::embedding).toList(), versions);
  }
}
