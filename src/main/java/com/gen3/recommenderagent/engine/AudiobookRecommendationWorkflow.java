package com.gen3.recommenderagent.engine;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.CandidateRetriever;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RecommendationQueryBuilder;
import java.util.List;
import org.springframework.stereotype.Service;

/** Runs the candidate retrieval and ranking steps shared by recommendation-producing intents. */
@Service
public class AudiobookRecommendationWorkflow {

  private static final int CANDIDATE_LIMIT = 50;
  private static final int DEFAULT_RESULT_LIMIT = 5;
  private static final int MAX_RESULT_LIMIT = 5;

  private final CandidateRetriever candidateRetriever;
  private final Ranker ranker;
  private final RecommendationQueryBuilder queryBuilder;

  /** Receives database-independent ports so handlers do not depend directly on Qdrant or Solr. */
  public AudiobookRecommendationWorkflow(
      CandidateRetriever candidateRetriever,
      Ranker ranker,
      RecommendationQueryBuilder queryBuilder) {
    this.candidateRetriever = candidateRetriever;
    this.ranker = ranker;
    this.queryBuilder = queryBuilder;
  }

  /** Builds retrieval text, lets the retriever select its mode, and ranks the candidates. */
  public List<Recommendation> recommend(SessionRequest request) {
    String query = queryBuilder.build(request);
    var candidates = candidateRetriever.getCandidates(query, CANDIDATE_LIMIT, request);
    return ranker.rank(candidates, resolveResultLimit(request), request.isPersonalised());
  }

  /** Applies the API's default and maximum result limits. */
  private int resolveResultLimit(SessionRequest request) {
    if (request.getConstraints() == null || request.getConstraints().getCount() == null) {
      return DEFAULT_RESULT_LIMIT;
    }
    return Math.clamp(request.getConstraints().getCount(), 1, MAX_RESULT_LIMIT);
  }
}
