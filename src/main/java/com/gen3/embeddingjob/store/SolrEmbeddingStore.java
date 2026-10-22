package com.gen3.embeddingjob.store;

import com.gen3.recommenderagent.storage.audiobook.model.StoredAudiobook;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Solr catalogue pagination and vector-only writes for the maintenance job. */
@Component
@ConditionalOnProperty(name = "audiobook.database", havingValue = "solr")
public class SolrEmbeddingStore implements AudiobookEmbeddingStore {
  private final SolrAudiobookRepository repository;

  public SolrEmbeddingStore(SolrAudiobookRepository repository) {
    this.repository = repository;
  }

  @Override
  public Page embeddingPage(String cursor, int limit) throws IOException {
    int offset = cursor == null ? 0 : Integer.parseInt(cursor);
    var records = repository.findAllBooks(offset, limit);
    return new Page(
        records.stream().map(record -> new StoredAudiobook(record, record.id())).toList(),
        records.size() < limit ? null : Integer.toString(offset + records.size()));
  }

  @Override
  public void updateEmbeddings(List<Update> updates) throws IOException {
    repository.indexEmbeddings(updates.stream().map(Update::embedding).toList());
  }
}
