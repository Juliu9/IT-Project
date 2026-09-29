package com.gen3.recommenderagent.engine;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.candidateretriever.CandidateRetriever;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectorService;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.Ranker;
import java.util.List;
import org.springframework.stereotype.Service;

/** Runs the candidate retrieval and ranking steps shared by recommendation-producing intents. */
@Service
public class AudiobookRecommendationWorkflow implements RecommendationWorkflow {

  private static final int CANDIDATE_LIMIT = 50;
  private static final int DEFAULT_RESULT_LIMIT = MAX_BOOK_COUNT;

  private final CandidateRetriever candidateRetriever;
  private final Ranker ranker;
  private final SemanticQueryVectorService semanticQueryVectorService;

  /** Receives database-independent ports so handlers do not depend on storage adapters. */
  public AudiobookRecommendationWorkflow(
      CandidateRetriever candidateRetriever,
      Ranker ranker,
      SemanticQueryVectorService semanticQueryVectorService) {
    this.candidateRetriever = candidateRetriever;
    this.ranker = ranker;
    this.semanticQueryVectorService = semanticQueryVectorService;
  }

  /** Builds retrieval text, lets the retriever select its mode, and ranks the candidates. */
  @Override
  public List<Recommendation> recommend(SessionRequest request, Session session) {
    SemanticQueryVectors vectors = semanticQueryVectorService.create(request);
    var candidates = candidateRetriever.getCandidates(request, vectors, CANDIDATE_LIMIT);
    return ranker.rank(candidates, resolveResultLimit(request), vectors);
  }

  /** Applies the API's default and maximum result limits. */
  private int resolveResultLimit(SessionRequest request) {
    if (request.getFilter() == null || request.getFilter().getCount() == null) {
      return DEFAULT_RESULT_LIMIT;
    }
    return Math.clamp(request.getFilter().getCount(), 1, MAX_BOOK_COUNT);
  }
}
