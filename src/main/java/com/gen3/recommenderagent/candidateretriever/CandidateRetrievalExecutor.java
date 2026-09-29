package com.gen3.recommenderagent.candidateretriever;

import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlan;
import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Executes one database-independent retrieval plan against a selected storage adapter. */
@Component
public class CandidateRetrievalExecutor {

  private final EmbeddingIndexer embeddingIndexer;
  private final AudiobookRetrievalPlanner retrievalPlanner;

  public CandidateRetrievalExecutor(
      EmbeddingIndexer embeddingIndexer, AudiobookRetrievalPlanner retrievalPlanner) {
    this.embeddingIndexer = embeddingIndexer;
    this.retrievalPlanner = retrievalPlanner;
  }

  public List<AudiobookCandidate> execute(
      AudiobookCandidateSearch candidateSearch,
      SessionRequest request,
      SemanticQueryVectors vectors,
      int limit)
      throws IOException {
    AudiobookRetrievalPlan plan = retrievalPlanner.plan(limit, request);
    return switch (plan.mode()) {
      case SEMANTIC -> retrieveSemantic(candidateSearch, plan, vectors);
      case KEYWORD ->
          candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
      case HYBRID -> retrieveHybrid(candidateSearch, plan, vectors);
      case FILTER_ONLY -> candidateSearch.searchByFilters(plan.filters(), plan.limit());
      case NONE -> List.of();
    };
  }

  private List<AudiobookCandidate> retrieveSemantic(
      AudiobookCandidateSearch candidateSearch,
      AudiobookRetrievalPlan plan,
      SemanticQueryVectors vectors)
      throws IOException {
    float[] queryVector = combined(vectors);
    if (queryVector == null || queryVector.length == 0) {
      return retrieveKeywordFallback(candidateSearch, plan);
    }
    return candidateSearch.searchSemantic(queryVector, plan.filters(), plan.limit());
  }

  private List<AudiobookCandidate> retrieveHybrid(
      AudiobookCandidateSearch candidateSearch,
      AudiobookRetrievalPlan plan,
      SemanticQueryVectors vectors)
      throws IOException {
    float[] queryVector = combined(vectors);
    if (queryVector == null || queryVector.length == 0) {
      return retrieveKeywordFallback(candidateSearch, plan);
    }
    if (plan.keywordText() == null || plan.keywordText().isBlank()) {
      return candidateSearch.searchSemantic(queryVector, plan.filters(), plan.limit());
    }
    return candidateSearch.searchHybrid(
        queryVector, plan.keywordText(), plan.filters(), plan.limit());
  }

  private List<AudiobookCandidate> retrieveKeywordFallback(
      AudiobookCandidateSearch candidateSearch, AudiobookRetrievalPlan plan) throws IOException {
    if (plan.keywordText() == null || plan.keywordText().isBlank()) {
      return List.of();
    }
    return candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
  }

  private float[] combined(SemanticQueryVectors vectors) {
    return embeddingIndexer.combineSemanticQueries(vectors);
  }
}
