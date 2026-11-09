package com.gen3.recommenderagent.retrieval.strategy;

import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalStrategy;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Searches using exact catalogue filters. */
@Component
public final class FilterOnlyRetriever implements CandidateRetrievalStrategy {
  private final AudiobookCandidateSearch search;

  public FilterOnlyRetriever(AudiobookCandidateSearch search) {
    this.search = search;
  }

  @Override
  public String name() {
    return "FILTER_ONLY";
  }

  @Override
  public int getOrder() {
    return 1003;
  }

  @Override
  public Optional<List<AudiobookCandidate>> tryRetrieve(
      SessionRequest request, SemanticQueryVectors vectors, AudiobookRetrievalPlan plan)
      throws IOException {
    return plan.filters().hasConditions()
        ? Optional.of(search.searchByFilters(plan.filters(), plan.limit()))
        : Optional.empty();
  }
}
