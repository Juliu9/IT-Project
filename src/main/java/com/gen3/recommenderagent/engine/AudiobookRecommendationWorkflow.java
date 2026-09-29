package com.gen3.recommenderagent.engine;

import static com.gen3.recommenderagent.common.BookCountPolicy.clamp;
import static com.gen3.recommenderagent.common.BookCountPolicy.clampOrDefault;

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
    return ranker.rank(candidates, resolveResultLimit(request, session), vectors);
  }

  /** Applies the API's default and maximum result limits. */
  private int resolveResultLimit(SessionRequest request, Session session) {
    Integer requestedCount = request == null ? null : request.getBookCount();
    if (requestedCount != null) {
      return clamp(requestedCount);
    }
    Integer sessionCount = session == null ? null : session.getBookCount();
    return clampOrDefault(sessionCount);
  }
}
