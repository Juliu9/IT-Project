package com.gen3.recommenderagent.retrieval.strategy;

import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalStrategy;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;

/** Shares vector combination and keyword fallback between vector-based retrievers. */
abstract class VectorRetriever implements CandidateRetrievalStrategy {
  private final AudiobookCandidateSearch search;
  private final QueryEmbeddingService embeddings;
  private final KeywordRetriever keywords;

  VectorRetriever(
      AudiobookCandidateSearch search,
      QueryEmbeddingService embeddings,
      KeywordRetriever keywords) {
    this.search = search;
    this.embeddings = embeddings;
    this.keywords = keywords;
  }

  List<AudiobookCandidate> retrieveVectors(
      SemanticQueryVectors vectors, AudiobookRetrievalPlan plan, boolean hybrid)
      throws IOException {
    float[] queryVector = embeddings.combineSemanticQueries(vectors);
    boolean hasKeywords = KeywordRetriever.hasKeywords(plan);
    if (queryVector == null || queryVector.length == 0) {
      return hasKeywords ? keywords.retrieveKeywords(plan) : List.of();
    }
    if (hybrid && hasKeywords) {
      return search.searchHybrid(queryVector, plan.keywordText(), plan.filters(), plan.limit());
    }
    return search.searchSemantic(queryVector, plan.filters(), plan.limit());
  }
}
