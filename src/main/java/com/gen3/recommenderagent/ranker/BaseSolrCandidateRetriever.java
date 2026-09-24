package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
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

  private final AudiobookCandidateSearch candidateSearch;
  private final EmbeddingIndexer embeddingIndexer;

  public BaseSolrCandidateRetriever(
      @Qualifier("solrAudiobookRepository") AudiobookCandidateSearch candidateSearch,
      EmbeddingIndexer embeddingIndexer) {
    this.candidateSearch = candidateSearch;
    this.embeddingIndexer = embeddingIndexer;
  }

  /** Retrieves audiobook candidates from Solr using the given query. */
  @Override
  public List<AudiobookCandidate> getCandidates(String query, int limit) {

    try {
      return candidateSearch.searchKeyword(query, AudiobookFilters.empty(), limit);

    } catch (IOException e) {
      throw new RuntimeException("Failed to retrieve candidates from Solr", e);
    }
  }

  /** Retrieves lexical and semantic candidates using the same embedding model as indexing. */
  @Override
  public List<AudiobookCandidate> getCandidates(
      String query, int limit, SessionRequest request) {
    try {
      float[] queryVector = embeddingIndexer.embedRequest(request);
      if (queryVector == null || queryVector.length == 0) {
        return getCandidates(query, limit);
      }

      return candidateSearch.searchHybrid(
          queryVector, query, AudiobookFilters.empty(), limit);
    } catch (IOException exception) {
      throw new RuntimeException("Failed to retrieve semantic candidates from Solr", exception);
    }
  }

}
