package com.gen3.recommenderagent.engine;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.PreferenceSignals;
import com.gen3.recommenderagent.ranker.PreferenceVectorService;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.candidate.CandidateRetriever;
import java.util.List;
import org.springframework.stereotype.Service;

/** Runs the candidate retrieval and ranking steps shared by recommendation-producing intents. */
@Service
public class AudiobookRecommendationWorkflow implements RecommendationWorkflow {

  private static final int CANDIDATE_LIMIT = 50;
  private static final int DEFAULT_RESULT_LIMIT = 5;
  private static final int MAX_RESULT_LIMIT = 5;

  private final CandidateRetriever candidateRetriever;
  private final Ranker ranker;
  private final PreferenceVectorService preferenceVectorService;

  /** Receives database-independent ports so handlers do not depend on storage adapters. */
  public AudiobookRecommendationWorkflow(
      CandidateRetriever candidateRetriever,
      Ranker ranker,
      PreferenceVectorService preferenceVectorService) {
    this.candidateRetriever = candidateRetriever;
    this.ranker = ranker;
    this.preferenceVectorService = preferenceVectorService;
  }

  /** Builds retrieval text, lets the retriever select its mode, and ranks the candidates. */
  @Override
  public List<Recommendation> recommend(SessionRequest request, SessionContext context) {
    PreferenceSignals signals = preferenceVectorService.create(request);
    var candidates = candidateRetriever.getCandidates(request, signals, CANDIDATE_LIMIT);
    return ranker.rank(candidates, resolveResultLimit(request), signals);
  }

  /** Applies the API's default and maximum result limits. */
  private int resolveResultLimit(SessionRequest request) {
    if (request.getFilter() == null || request.getFilter().getCount() == null) {
      return DEFAULT_RESULT_LIMIT;
    }
    return Math.clamp(request.getFilter().getCount(), 1, MAX_RESULT_LIMIT);
  }
}
