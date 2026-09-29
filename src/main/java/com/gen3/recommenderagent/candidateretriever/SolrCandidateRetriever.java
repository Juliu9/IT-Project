package com.gen3.recommenderagent.candidateretriever;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/*
   Gets candidates that can be used for machine learning.
*/
@Service
@ConditionalOnProperty(name = "audiobook.candidate-retriever", havingValue = "solr")
public class SolrCandidateRetriever implements CandidateRetriever {

  private final AudiobookCandidateSearch candidateSearch;
  private final CandidateRetrievalExecutor retrievalExecutor;

  public SolrCandidateRetriever(
      @Qualifier("solrAudiobookRepository") AudiobookCandidateSearch candidateSearch,
      CandidateRetrievalExecutor retrievalExecutor) {
    this.candidateSearch = candidateSearch;
    this.retrievalExecutor = retrievalExecutor;
  }

  /** Retrieves lexical and semantic candidates using the same embedding model as indexing. */
  @Override
  public List<AudiobookCandidate> getCandidates(
      SessionRequest request, SemanticQueryVectors vectors, int limit) {
    try {
      return retrievalExecutor.execute(candidateSearch, request, vectors, limit);
    } catch (IOException exception) {
      throw new RuntimeException("Failed to retrieve semantic candidates from Solr", exception);
    }
  }
}
