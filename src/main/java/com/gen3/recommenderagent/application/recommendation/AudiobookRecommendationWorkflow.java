package com.gen3.recommenderagent.application.recommendation;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.RECOMMENDATION_BATCH_SIZE;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.retrieval.CandidateRetriever;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import com.gen3.shared.vector.VectorMath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final SearchHistoryPreferences searchHistoryPreferences;

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
    // Callers that don't pass search history (like the older tests) get it switched off.
    this.searchHistoryPreferences = new SearchHistoryPreferences(Optional.empty(), 0.2, 3);
  }

  @Autowired
  public AudiobookRecommendationWorkflow(
      CandidateRetriever candidateRetriever,
      Ranker ranker,
      SemanticQueryVectorService semanticQueryVectorService,
      UserPreferenceVectorRepository userPreferenceVectorRepository,
      SearchHistoryPreferences searchHistoryPreferences) {
    this.candidateRetriever = candidateRetriever;
    this.ranker = ranker;
    this.semanticQueryVectorService = semanticQueryVectorService;
    this.userPreferenceVectorRepository = userPreferenceVectorRepository;
    this.searchHistoryPreferences = searchHistoryPreferences;
  }

  /** Builds retrieval text, lets the retriever select its mode, and ranks the candidates. */
  @Override
  public List<Recommendation> recommend(ConversationDraft request, Session session) {
    UserPreferenceEmbedding preferences;
    float[] searchTaste;
    if (request != null
        && request.getIntent() == ConversationAction.MORE_RESULTS
        && request.isUserPreferencesLoaded()) {
      preferences = request.getUserPreferenceSnapshot();
      searchTaste = request.getSearchContext().getSearchHistorySnapshot();
    } else {
      preferences =
          request != null
                  && request.getSearchContext().getSimilarBook() != null
                  && !request.isPersonalised()
              ? null
              : userPreferences(session).orElse(null);
      searchTaste =
          request != null
                  && request.getSearchContext().getSimilarBook() != null
                  && !request.isPersonalised()
              ? null
              : searchTaste(request, session);
      if (request != null) {
        request.setUserPreferenceSnapshot(preferences);
        request.getSearchContext().setSearchHistorySnapshot(searchTaste);
        request.setUserPreferencesLoaded(true);
      }
    }
    boolean answeringTasteQuestion = request != null && request.isPreferenceAnswer();
    boolean hasSavedPreferences =
        session != null && session.getPreferences() != null && session.getPreferences().hasValues();
    if (request != null
        && request.isPersonalised()
        && request.getIntent() != ConversationAction.MORE_RESULTS
        && preferences == null
        && searchTaste == null
        && !hasSavedPreferences
        && !answeringTasteQuestion) {
      request.setPreferenceClarificationRequired(true);
      return List.of();
    }
    SemanticQueryVectors vectors =
        RequestTelemetry.measure(
            "embedMs", () -> retrievalVectors(request, preferences, searchTaste));
    recordSearch(request, session, vectors);
    if (request != null && request.isPersonalised()) {
      boolean needsPreferences = vectors == null || vectors.isEmpty();
      request.setPreferenceClarificationRequired(needsPreferences);
      if (needsPreferences) {
        return List.of();
      }
    }
    Set<String> shownBookIds = new HashSet<>(shownBooks(session));
    if (request != null) {
      shownBookIds.addAll(request.getReadBookIds());
    }
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
    RankingContext rankingContext =
        new RankingContext(preferences, shownBookIds).withSearchHistoryTaste(searchTaste);
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

  /** Builds retrieval evidence from explicit semantics, stored taste, and any reference book. */
  private SemanticQueryVectors retrievalVectors(
      ConversationDraft request, UserPreferenceEmbedding preferences, float[] searchTaste) {
    var context = request == null ? null : request.getSearchContext();
    var reference = context == null ? null : context.getSimilarBook();
    if (reference != null
        && request.getIntent() == ConversationAction.MORE_RESULTS
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
      addVector(tastes, searchTaste);
      // Stored taste remains one signal, independent of how many profile vectors are available.
      if (!tastes.isEmpty()) {
        positive.add(VectorMath.directionalAverage(tastes, List.of()));
      }
    } else if (request != null && request.isPersonalised() && searchTaste != null) {
      positive.add(searchTaste);
    }
    if (reference == null) {
      return new SemanticQueryVectors(positive, current.negative());
    }
    float[] combined = VectorMath.directionalAverage(positive, current.negative());
    context.setSimilarityQueryVector(combined);
    return new SemanticQueryVectors(List.of(combined), List.of());
  }

  /** Adds nonempty vector evidence to the supplied collection. */
  private void addVector(List<float[]> vectors, float[] vector) {
    if (vector != null && vector.length > 0) {
      vectors.add(vector);
    }
  }

  private float[] searchTaste(ConversationDraft request, Session session) {
    if (session == null) {
      return null;
    }
    String requestId = request == null ? null : request.getRequestId();
    return searchHistoryPreferences.load(session.getUserId(), requestId).orElse(null);
  }

  // Only fresh, explicit searches teach the taste: not "more", vague requests or "like this book".
  private void recordSearch(
      ConversationDraft request, Session session, SemanticQueryVectors vectors) {
    if (request == null
        || session == null
        || (request.getIntent() != ConversationAction.RECOMMENDATION
            && request.getIntent() != ConversationAction.REFINE)
        || request.isPersonalised()
        || request.getSearchContext().getSimilarBook() != null
        || vectors == null
        || vectors.positive().isEmpty()) {
      return;
    }
    searchHistoryPreferences.record(
        session.getUserId(),
        request.getRequestId(),
        VectorMath.directionalAverage(vectors.positive(), vectors.negative()));
  }

  /** Loads stored user taste once per search and retains its snapshot for later pages. */
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

  /** Collects book IDs already shown or excluded from the active search. */
  private Set<String> shownBooks(Session session) {
    if (session == null || session.getActiveSearch().getShownBookIds() == null) {
      return Set.of();
    }
    return new HashSet<>(session.getActiveSearch().getShownBookIds());
  }
}
