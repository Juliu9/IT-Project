/* This class sits between the recommendation egine and the Solr repository */
package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.io.IOException;
import java.util.List;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/*
   Gets candidates that can be used for machine learning.
*/
@Service
public class CandidateRetriever {

  private final SolrAudiobookRepository audiobookRepository;
  private final EmbeddingService embeddingService;
  private final String vectorField;

  public CandidateRetriever(SolrAudiobookRepository audiobookRepository) {
    this.audiobookRepository = audiobookRepository;
    this.embeddingService = null;
    this.vectorField = "audiobook_vector";
  }

  @Autowired
  public CandidateRetriever(
      SolrAudiobookRepository audiobookRepository,
      EmbeddingService embeddingService,
      @Value("${solr.vector-field:audiobook_vector}") String vectorField) {
    this.audiobookRepository = audiobookRepository;
    this.embeddingService = embeddingService;
    this.vectorField = vectorField;
  }

  /** Retrieves audiobook candidates from Solr using the given query. */
  public List<SolrDocument> getCandidates(String query, int limit) {

    try {
      return audiobookRepository.search(query, limit).getResults();

    } catch (SolrServerException | IOException e) {
      throw new RuntimeException("Failed to retrieve candidates from Solr", e);
    }
  }

  /**
   * Converts the request into an embedding using EmbeddingService. /* Sends that vector to
   * SolrAudiobookRepository. /* Return the matching Solr documents.
   */
  public List<SolrDocument> getSemanticCandidates(SessionRequest request, int limit) {
    if (embeddingService == null) {
      throw new IllegalStateException("Embedding service is not configured");
    }

    try {
      return audiobookRepository
          .searchByVector(embeddingService.embedRequest(request), limit, vectorField)
          .getResults();
    } catch (SolrServerException | IOException e) {
      throw new RuntimeException("Failed to retrieve semantic candidates from Solr", e);
    }
  }
}
