package com.gen3.recommenderagent.application;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.RECOMMENDATION_BATCH_SIZE;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.retrieval.CandidateRetriever;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.io.IOException;
import java.util.ArrayList;
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
    UserPreferenceEmbedding preferences = userPreferences(session).orElse(null);
    SemanticQueryVectors vectors =
        RequestTelemetry.measure("embedMs", () -> retrievalVectors(request, preferences));
    if (request != null && request.isPersonalised()) {
      boolean needsPreferences = vectors == null || vectors.isEmpty();
      request.setPreferenceClarificationRequired(needsPreferences);
      if (needsPreferences) {
        return List.of();
      }
    }
    Set<String> shownBookIds = shownBooks(session);
    long retrievalStartedAt = System.nanoTime();
    var candidates =
        RequestTelemetry.measure(
            "retrieveMs",
            () ->
                candidateRetriever.getCandidates(request, vectors, CANDIDATE_LIMIT, shownBookIds));
    LOGGER.info(
        "candidate.retrieval.completed durationMs={} candidateCount={}",
        (System.nanoTime() - retrievalStartedAt) / 1_000_000,
        candidates.size());
    RankingContext rankingContext = new RankingContext(preferences, shownBookIds);
    return RequestTelemetry.measure(
        "rankMs", () -> ranker.rank(candidates, RECOMMENDATION_BATCH_SIZE, rankingContext));
  }

  private SemanticQueryVectors retrievalVectors(
      SessionRequest request, UserPreferenceEmbedding preferences) {
    if (request == null || !request.isPersonalised()) {
      return semanticQueryVectorService.create(request);
    }
    if (preferences != null) {
      List<float[]> positive = new ArrayList<>();
      addVector(positive, preferences.favouritesVector());
      addVector(positive, preferences.historyVector());
      float[] combined = VectorMath.directionalAverage(positive, List.of());
      SemanticQueryVectors current = semanticQueryVectorService.create(request);
      List<float[]> blended = new ArrayList<>(current.positive());
      // Treat stored taste as one signal, so it and the current query have equal shares.
      blended.add(combined);
      return new SemanticQueryVectors(blended, current.negative());
    }
    return semanticQueryVectorService.createPersonalised(request);
  }

  private void addVector(List<float[]> vectors, float[] vector) {
    if (vector != null && vector.length > 0) {
      vectors.add(vector);
    }
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
}
