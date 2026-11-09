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
              ? retrieveSemantic(plan, vectors)
              : switch (plan.mode()) {
                case KEYWORD ->
                    candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
                case HYBRID -> retrieveHybrid(plan, vectors);
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

  /** Searches with combined vector evidence, falling back to keywords when no vector is usable. */
  private List<AudiobookCandidate> retrieveSemantic(
      AudiobookRetrievalPlan plan, SemanticQueryVectors vectors) throws IOException {
    float[] queryVector = embeddingIndexer.combineSemanticQueries(vectors);
    if (queryVector == null || queryVector.length == 0) {
      return retrieveKeywordFallback(plan);
    }
    return candidateSearch.searchSemantic(queryVector, plan.filters(), plan.limit());
  }

  /** Combines vector and keyword retrieval when both kinds of evidence are available. */
  private List<AudiobookCandidate> retrieveHybrid(
      AudiobookRetrievalPlan plan, SemanticQueryVectors vectors) throws IOException {
    float[] queryVector = embeddingIndexer.combineSemanticQueries(vectors);
    if (queryVector == null || queryVector.length == 0) {
      return retrieveKeywordFallback(plan);
    }
    if (plan.keywordText() == null || plan.keywordText().isBlank()) {
      return candidateSearch.searchSemantic(queryVector, plan.filters(), plan.limit());
    }
    return candidateSearch.searchHybrid(
        queryVector, plan.keywordText(), plan.filters(), plan.limit());
  }

  /** Uses keyword retrieval only when the plan contains usable query text. */
  private List<AudiobookCandidate> retrieveKeywordFallback(AudiobookRetrievalPlan plan)
      throws IOException {
    if (plan.keywordText() == null || plan.keywordText().isBlank()) {
      return List.of();
    }
    return candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
  }
}
