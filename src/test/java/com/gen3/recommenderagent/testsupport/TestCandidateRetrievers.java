package com.gen3.recommenderagent.testsupport;

import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalService;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.retrieval.strategy.FilterOnlyRetriever;
import com.gen3.recommenderagent.retrieval.strategy.HybridRetriever;
import com.gen3.recommenderagent.retrieval.strategy.KeywordRetriever;
import com.gen3.recommenderagent.retrieval.strategy.SemanticRetriever;
import com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch;
import java.util.List;

/** Assembles the production strategies for tests that do not start Spring. */
public final class TestCandidateRetrievers {
  private TestCandidateRetrievers() {}

  public static CandidateRetrievalService create(
      String backend, AudiobookCandidateSearch search, QueryEmbeddingService embeddings) {
    var keywords = new KeywordRetriever(search);
    return new CandidateRetrievalService(
        backend,
        new AudiobookRetrievalPlanner(),
        List.of(
            new SemanticRetriever(search, embeddings, keywords),
            new HybridRetriever(search, embeddings, keywords),
            keywords,
            new FilterOnlyRetriever(search)));
  }
}
