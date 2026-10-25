package com.gen3.recommenderagent.retrieval;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.port.AudiobookCandidateSearch;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Executes database-independent candidate retrieval against one configured search adapter. */
public class CandidateRetrievalService implements CandidateRetriever {

  private final String backend;
  private final AudiobookCandidateSearch candidateSearch;
  private final QueryEmbeddingService embeddingIndexer;
  private final AudiobookRetrievalPlanner retrievalPlanner;

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

  @Override
  public List<AudiobookCandidate> getCandidates(
      SessionRequest request,
      SemanticQueryVectors vectors,
      int limit,
      Set<String> excludedBookIds) {
    try {
      RequestTelemetry.retrievalBackend(backend);
      AudiobookRetrievalPlan plan = retrievalPlanner.plan(limit, request, excludedBookIds);
      boolean personalised =
          request != null && request.isPersonalised() && vectors != null && !vectors.isEmpty();
      List<AudiobookCandidate> candidates =
          personalised
              ? retrieveSemantic(plan, vectors)
              : switch (plan.mode()) {
                case SEMANTIC -> retrieveSemantic(plan, vectors);
                case KEYWORD ->
                    candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
                case HYBRID -> retrieveHybrid(plan, vectors);
                case FILTER_ONLY -> candidateSearch.searchByFilters(plan.filters(), plan.limit());
                case NONE -> List.of();
              };
      RequestTelemetry.retrieval(personalised ? "SEMANTIC" : plan.mode().name(), candidates.size());
      return candidates;
    } catch (IOException exception) {
      throw new CandidateRetrievalException(backend, exception);
    }
  }

  private List<AudiobookCandidate> retrieveSemantic(
      AudiobookRetrievalPlan plan, SemanticQueryVectors vectors) throws IOException {
    float[] queryVector = embeddingIndexer.combineSemanticQueries(vectors);
    if (queryVector == null || queryVector.length == 0) {
      return retrieveKeywordFallback(plan);
    }
    return candidateSearch.searchSemantic(queryVector, plan.filters(), plan.limit());
  }

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

  private List<AudiobookCandidate> retrieveKeywordFallback(AudiobookRetrievalPlan plan)
      throws IOException {
    if (plan.keywordText() == null || plan.keywordText().isBlank()) {
      return List.of();
    }
    return candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
  }
}
