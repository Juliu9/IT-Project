package com.gen3.recommenderagent.storage.audiobook.solr;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.ranker.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.candidate.CandidateRetriever;
import com.gen3.recommenderagent.ranker.candidate.retrieval.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
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
public class BaseSolrCandidateRetriever implements CandidateRetriever {

  private final AudiobookCandidateSearch candidateSearch;
  private final EmbeddingIndexer embeddingIndexer;
  private final AudiobookRetrievalPlanner retrievalPlanner;

  public BaseSolrCandidateRetriever(
      @Qualifier("solrAudiobookRepository") AudiobookCandidateSearch candidateSearch,
      EmbeddingIndexer embeddingIndexer,
      AudiobookRetrievalPlanner retrievalPlanner) {
    this.candidateSearch = candidateSearch;
    this.embeddingIndexer = embeddingIndexer;
    this.retrievalPlanner = retrievalPlanner;
  }

  /** Retrieves lexical and semantic candidates using the same embedding model as indexing. */
  @Override
  public List<AudiobookCandidate> getCandidates(
      SessionRequest request, SemanticQueryVectors vectors, int limit) {
    try {
      String query =
          retrievalPlanner.lexicalText(
              request == null ? null : request.getPositiveSemanticQuery(), "");
      float[] queryVector = embeddingIndexer.combineSemanticQueries(vectors);
      if (queryVector == null || queryVector.length == 0) {
        return candidateSearch.searchKeyword(query, AudiobookFilters.empty(), limit);
      }

      return candidateSearch.searchHybrid(queryVector, query, AudiobookFilters.empty(), limit);
    } catch (IOException exception) {
      throw new RuntimeException("Failed to retrieve semantic candidates from Solr", exception);
    }
  }
}
