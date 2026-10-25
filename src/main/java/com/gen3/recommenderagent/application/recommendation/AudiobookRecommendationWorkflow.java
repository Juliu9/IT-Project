package com.gen3.recommenderagent.application.recommendation;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.RECOMMENDATION_BATCH_SIZE;

import com.gen3.catalogue.vector.VectorMath;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
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
  public List<Recommendation> recommend(ConversationDraft request, Session session) {
    UserPreferenceEmbedding preferences;
    if (request != null
        && request.getIntent() == Intent.MORE_RESULTS
        && request.isUserPreferencesLoaded()) {
      preferences = request.getUserPreferenceSnapshot();
    } else {
      preferences =
          request != null
                  && request.getSearchContext().getSimilarBook() != null
                  && !request.isPersonalised()
              ? null
              : userPreferences(session).orElse(null);
      if (request != null) {
        request.setUserPreferenceSnapshot(preferences);
        request.setUserPreferencesLoaded(true);
      }
    }
    boolean answeringTasteQuestion =
        session != null
            && session.isAwaitingPersonalisedPreferences()
            && request != null
            && !request.isExplicitSearch();
    boolean hasSavedPreferences =
        session != null && session.getPreferences() != null && session.getPreferences().hasValues();
    if (request != null
        && request.isPersonalised()
        && preferences == null
        && !hasSavedPreferences
        && !answeringTasteQuestion) {
      request.setPreferenceClarificationRequired(true);
      return List.of();
    }
    SemanticQueryVectors vectors =
        RequestTelemetry.measure("embedMs", () -> retrievalVectors(request, preferences));
    if (request != null && request.isPersonalised()) {
      boolean needsPreferences = vectors == null || vectors.isEmpty();
      request.setPreferenceClarificationRequired(needsPreferences);
      if (needsPreferences) {
        return List.of();
      }
    }
    Set<String> shownBookIds = new HashSet<>(shownBooks(session));
    if (request != null && request.getSearchContext().getSimilarBook() != null) {
      shownBookIds.add(request.getSearchContext().getSimilarBook().bookId());
    }
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
    List<Recommendation> recommendations =
        RequestTelemetry.measure(
            "rankMs", () -> ranker.rank(candidates, RECOMMENDATION_BATCH_SIZE, rankingContext));
    if (request != null
        && request.isPersonalised()
        && preferences != null
        && ((preferences.favouritesVector() != null && preferences.favouritesVector().length > 0)
            || (preferences.historyVector() != null && preferences.historyVector().length > 0))) {
      recommendations.forEach(book -> book.setReadingPreferencesUsed(true));
    }
    return recommendations;
  }

  private SemanticQueryVectors retrievalVectors(
      ConversationDraft request, UserPreferenceEmbedding preferences) {
    var context = request == null ? null : request.getSearchContext();
    var reference = context == null ? null : context.getSimilarBook();
    if (reference != null
        && request.getIntent() == Intent.MORE_RESULTS
        && context.getSimilarityQueryVector() != null) {
      return new SemanticQueryVectors(List.of(context.getSimilarityQueryVector()), List.of());
    }
    var current = semanticQueryVectorService.create(request);
    List<float[]> positive = new ArrayList<>(current.positive());
    if (reference != null) {
      positive.add(reference.vector());
    }
    if (request != null && request.isPersonalised() && preferences != null) {
      List<float[]> tastes = new ArrayList<>();
      addVector(tastes, preferences.favouritesVector());
      addVector(tastes, preferences.historyVector());
      // Stored taste remains one signal, independent of how many profile vectors are available.
      if (!tastes.isEmpty()) {
        positive.add(VectorMath.directionalAverage(tastes, List.of()));
      }
    }
    if (reference == null) {
      return new SemanticQueryVectors(positive, current.negative());
    }
    float[] combined = VectorMath.directionalAverage(positive, current.negative());
    context.setSimilarityQueryVector(combined);
    return new SemanticQueryVectors(List.of(combined), List.of());
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
