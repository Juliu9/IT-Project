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
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs the candidate retrieval and ranking steps shared by recommendation-producing intents. */
@Service
public class AudiobookRecommendationWorkflow implements RecommendationWorkflow {

  private static final int CANDIDATE_LIMIT = 50;
  private static final Logger LOGGER =
      LoggerFactory.getLogger(AudiobookRecommendationWorkflow.class);
  private final CandidateRetriever candidateRetriever;
  private final Ranker ranker;
  private final SemanticQueryVectorService semanticQueryVectorService;
  private final UserPreferenceVectorRepository userPreferenceVectorRepository;

  /** Receives database-independent ports so handlers do not depend on storage adapters. */
  public AudiobookRecommendationWorkflow(
      CandidateRetriever candidateRetriever,
      Ranker ranker,
      SemanticQueryVectorService semanticQueryVectorService,
      UserPreferenceVectorRepository userPreferenceVectorRepository) {
    this.candidateRetriever = candidateRetriever;
    this.ranker = ranker;
    this.semanticQueryVectorService = semanticQueryVectorService;
    this.userPreferenceVectorRepository = userPreferenceVectorRepository;
  }

  /** Builds retrieval text, lets the retriever select its mode, and ranks the candidates. */
  @Override
  public List<Recommendation> recommend(SessionRequest request, Session session) {
    SemanticQueryVectors vectors = semanticQueryVectorService.create(request);
    var candidates = candidateRetriever.getCandidates(request, vectors, CANDIDATE_LIMIT);
    RankingContext rankingContext =
        new RankingContext(vectors, userPreferences(session).orElse(null), shownBooks(session));
    return ranker.rank(candidates, resolveResultLimit(request, session), rankingContext);
  }

  private Optional<UserPreferenceEmbedding> userPreferences(Session session) {
    if (session == null || session.getUserId() == null || session.getUserId().isBlank()) {
      return Optional.empty();
    }
    try {
      return userPreferenceVectorRepository.findEmbeddingByUserId(session.getUserId());
    } catch (IOException exception) {
      LOGGER.warn("Could not load user preference vectors; continuing without them", exception);
      return Optional.empty();
    }
  }

  private Set<String> shownBooks(Session session) {
    if (session == null || session.getShownBooks() == null) {
      return Set.of();
    }
    return new HashSet<>(session.getShownBooks());
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
