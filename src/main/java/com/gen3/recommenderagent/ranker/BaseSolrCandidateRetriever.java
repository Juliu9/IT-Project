package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository;
import java.io.IOException;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/*
   Gets candidates that can be used for machine learning.
*/
@Service
@ConditionalOnProperty(
    name = "audiobook.candidate-retriever",
    havingValue = "solr")
public class BaseSolrCandidateRetriever implements CandidateRetriever {

  private final AudiobookRepository audiobookRepository;
  private final EmbeddingIndexer embeddingIndexer;

  public BaseSolrCandidateRetriever(
      @Qualifier("solrAudiobookRepository") AudiobookRepository audiobookRepository,
      EmbeddingIndexer embeddingIndexer) {
    this.audiobookRepository = audiobookRepository;
    this.embeddingIndexer = embeddingIndexer;
  }

  /** Retrieves audiobook candidates from Solr using the given query. */
  @Override
  public List<SolrDocument> getCandidates(String query, int limit) {

    try {
      List<AudiobookRecord> records = audiobookRepository.searchBooks(query, limit).records();
      return records.stream().map(this::toSolrDocument).toList();

    } catch (IOException e) {
      throw new RuntimeException("Failed to retrieve candidates from Solr", e);
    }
  }

  /** Retrieves lexical and semantic candidates using the same embedding model as indexing. */
  @Override
  public List<SolrDocument> getCandidates(String query, int limit, SessionRequest request) {
    try {
      float[] queryVector = embeddingIndexer.embedRequest(request);
      if (queryVector == null || queryVector.length == 0) {
        return getCandidates(query, limit);
      }

      return audiobookRepository.searchBooks(query, limit, queryVector).records().stream()
          .map(this::toSolrDocument)
          .toList();
    } catch (IOException exception) {
      throw new RuntimeException("Failed to retrieve semantic candidates from Solr", exception);
    }
  }

  /** Keeps the current ranker input format isolated from the repository boundary. */
  private SolrDocument toSolrDocument(AudiobookRecord record) {
    SolrDocument document = new SolrDocument();
    document.setField("id", record.id());
    document.setField("source", record.source());
    document.setField("title", record.title());
    document.setField("authors", record.authors());
    document.setField("description", record.description());
    return document;
  }
}
