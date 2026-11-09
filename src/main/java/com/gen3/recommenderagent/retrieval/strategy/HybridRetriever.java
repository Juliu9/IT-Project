package com.gen3.recommenderagent.retrieval.strategy;

import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Searches using semantic content and any available keywords. */
@Component
public final class HybridRetriever extends VectorRetriever {
  public HybridRetriever(
      AudiobookCandidateSearch search,
      QueryEmbeddingService embeddings,
      KeywordRetriever keywords) {
    super(search, embeddings, keywords);
  }

  @Override
  public String name() {
    return "HYBRID";
  }

  @Override
  public int getOrder() {
    return 1001;
  }

  @Override
  public Optional<List<AudiobookCandidate>> tryRetrieve(
      SessionRequest request, SemanticQueryVectors vectors, AudiobookRetrievalPlan plan)
      throws IOException {
    return plan.hasSemanticContent()
        ? Optional.of(retrieveVectors(vectors, plan, true))
        : Optional.empty();
  }
}
