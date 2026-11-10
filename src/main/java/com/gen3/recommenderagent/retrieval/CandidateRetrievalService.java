package com.gen3.recommenderagent.retrieval;

import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Executes database-independent candidate retrieval against one configured search adapter. */
public class CandidateRetrievalService implements CandidateRetriever {

  private final String backend;
  private final AudiobookRetrievalPlanner retrievalPlanner;
  private final List<CandidateRetrievalStrategy> strategies;

  /** Connects planning to the ordered search strategies for the selected backend. */
  public CandidateRetrievalService(
      String backend,
      AudiobookRetrievalPlanner retrievalPlanner,
      List<CandidateRetrievalStrategy> strategies) {
    this.backend = backend;
    this.retrievalPlanner = retrievalPlanner;
    this.strategies = List.copyOf(strategies);
  }

  /** {@inheritDoc} */
  @Override
  public List<AudiobookCandidate> getCandidates(
      SessionRequest request,
      SemanticQueryVectors vectors,
      int limit,
      Set<String> excludedBookIds) {
    try {
      RequestTelemetry.retrievalBackend(backend);
      AudiobookRetrievalPlan plan = retrievalPlanner.plan(limit, request, excludedBookIds);
      for (var strategy : strategies) {
        var result = strategy.tryRetrieve(request, vectors, plan);
        if (result.isPresent()) {
          var candidates = result.get();
          RequestTelemetry.retrieval(strategy.name(), candidates.size());
          return candidates;
        }
      }
      RequestTelemetry.retrieval("NONE", 0);
      return List.of();
    } catch (IOException exception) {
      throw new CandidateRetrievalException(backend, exception);
    }
  }
}
