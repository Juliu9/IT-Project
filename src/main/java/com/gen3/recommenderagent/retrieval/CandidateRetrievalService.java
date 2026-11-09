package com.gen3.recommenderagent.retrieval;

import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Executes database-independent candidate retrieval against one configured search adapter. */
public class CandidateRetrievalService implements CandidateRetriever {

  private final String backend;
  private final AudiobookCandidateSearch candidateSearch;
  private final QueryEmbeddingService embeddingIndexer;
  private final AudiobookRetrievalPlanner retrievalPlanner;

  /** Connects retrieval planning and query embeddings to the selected catalogue search adapter. */
  public CandidateRetrievalService(
      String backend,
      AudiobookCandidateSearch candidateSearch,
      QueryEmbeddingService embeddingIndexer,
      AudiobookRetrievalPlanner retrievalPlanner) {
    this.backend = backend;
    this.candidateSearch = candidateSearch;
    this.embeddingIndexer = embeddingIndexer;
    this.retrievalPlanner = retrievalPlanner;
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
      boolean semanticRetrieval =
          request != null
              && request.requiresSemanticRetrieval()
              && vectors != null
              && !vectors.isEmpty();
      List<AudiobookCandidate> candidates =
          semanticRetrieval
              ? retrieveWithVectors(plan, vectors, false)
              : switch (plan.mode()) {
                case KEYWORD ->
                    candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
                case HYBRID -> retrieveWithVectors(plan, vectors, true);
                case FILTER_ONLY -> candidateSearch.searchByFilters(plan.filters(), plan.limit());
                case NONE -> List.of();
              };
      RequestTelemetry.retrieval(
          semanticRetrieval ? "SEMANTIC" : plan.mode().name(), candidates.size());
      return candidates;
    } catch (IOException exception) {
      throw new CandidateRetrievalException(backend, exception);
    }
  }

  /** Searches with combined vector evidence, including keyword evidence for hybrid retrieval. */
  private List<AudiobookCandidate> retrieveWithVectors(
      AudiobookRetrievalPlan plan, SemanticQueryVectors vectors, boolean hybrid)
      throws IOException {
    float[] queryVector = embeddingIndexer.combineSemanticQueries(vectors);
    if (queryVector == null || queryVector.length == 0) {
      return retrieveKeywordFallback(plan);
    }
    if (!hybrid || !hasKeywords(plan)) {
      return candidateSearch.searchSemantic(queryVector, plan.filters(), plan.limit());
    }
    return candidateSearch.searchHybrid(
        queryVector, plan.keywordText(), plan.filters(), plan.limit());
  }

  /** Uses keyword retrieval only when the plan contains usable query text. */
  private List<AudiobookCandidate> retrieveKeywordFallback(AudiobookRetrievalPlan plan)
      throws IOException {
    if (!hasKeywords(plan)) {
      return List.of();
    }
    return candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
  }

  /** Reports whether the plan contains searchable keyword text. */
  private boolean hasKeywords(AudiobookRetrievalPlan plan) {
    return plan.keywordText() != null && !plan.keywordText().isBlank();
  }
}
