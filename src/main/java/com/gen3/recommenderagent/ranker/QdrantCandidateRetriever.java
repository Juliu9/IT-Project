package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.ranker.retrieval.AudiobookRetrievalPlan;
import com.gen3.recommenderagent.ranker.retrieval.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Retrieves database-independent recommendation candidates from Qdrant. */
@Service
@ConditionalOnProperty(
    name = "audiobook.candidate-retriever",
    havingValue = "qdrant",
    matchIfMissing = true)
public class QdrantCandidateRetriever implements CandidateRetriever {

  private final AudiobookCandidateSearch candidateSearch;
  private final EmbeddingIndexer embeddingIndexer;
  private final AudiobookRetrievalPlanner retrievalPlanner;

  /** Uses the Qdrant repository and the shared normalized request-embedding pipeline. */
  public QdrantCandidateRetriever(
      @Qualifier("qdrantAudiobookRepository") AudiobookCandidateSearch candidateSearch,
      EmbeddingIndexer embeddingIndexer,
      AudiobookRetrievalPlanner retrievalPlanner) {
    this.candidateSearch = candidateSearch;
    this.embeddingIndexer = embeddingIndexer;
    this.retrievalPlanner = retrievalPlanner;
  }

  /** Embeds the supplied text and retrieves the nearest audiobook candidates. */
  @Override
  public List<AudiobookCandidate> getCandidates(String query, int limit) {
    SessionRequest request = new SessionRequest();
    request.setRawText(query);
    return getCandidates(query, limit, request);
  }

  /** Embeds the combined raw and processed user request once, then searches Qdrant. */
  @Override
  public List<AudiobookCandidate> getCandidates(
      String query, int limit, SessionRequest request) {
    try {
      AudiobookRetrievalPlan plan = retrievalPlanner.plan(query, limit, request);
      return switch (plan.mode()) {
        case SEMANTIC ->
            candidateSearch.searchSemantic(
                embeddingIndexer.embedRequest(request), plan.filters(), plan.limit());
        case KEYWORD ->
            candidateSearch.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
        case HYBRID ->
            candidateSearch.searchHybrid(
                embeddingIndexer.embedRequest(request),
                plan.keywordText(),
                plan.filters(),
                plan.limit());
        case FILTER_ONLY -> candidateSearch.searchByFilters(plan.filters(), plan.limit());
        case NONE -> List.of();
      };
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to retrieve candidates from Qdrant", exception);
    }
  }

}
