package com.gen3.recommenderagent.application;

import static com.gen3.recommenderagent.common.BookCountPolicy.clampOrDefault;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** Owns one ordered, all-or-nothing transition of a user's conversational session. */
@Service
public class SessionTransitionService {

  private static final ConversationState CONVERSATION_STATE = new ConversationState();

  private final SessionRepository sessions;
  private final RecommendationWorkflow recommendationWorkflow;

  public SessionTransitionService(
      SessionRepository sessions, RecommendationWorkflow recommendationWorkflow) {
    this.sessions = sessions;
    this.recommendationWorkflow = recommendationWorkflow;
  }

  public SessionRequest process(String sessionId, String userId, SessionRequest request) {
    return sessions.update(sessionId, userId, session -> apply(session, request));
  }

  private SessionRequest apply(Session session, SessionRequest original) {
    SessionRequest result = CONVERSATION_STATE.resolve(session, original);
    if (invalidatesBatch(original)) {
      session.setPendingRecommendations(List.of());
    }
    result.setRecommendations(
        CONVERSATION_STATE.isRecommendationIntent(result.getIntent())
            ? recommendationPage(result, session)
            : List.of());
    applyTransition(session, result, originalIntent(original));
    return result;
  }

  private boolean invalidatesBatch(SessionRequest request) {
    Intent intent = originalIntent(request);
    return intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.UPDATE_PREFERENCES
        || intent == Intent.CLEAR_HISTORY
        || ConversationState.hasSearchCriteria(request);
  }

  private List<Recommendation> recommendationPage(SessionRequest request, Session session) {
    int limit = clampOrDefault(request.getBookCount());
    List<Recommendation> page = takePending(session, limit);
    if (page.size() < limit) {
      // Exclude the cached tail too when refilling; all changes commit together or not at all.
      session.addRecommendationIds(recommendationIds(page));
      session.setPendingRecommendations(recommendationWorkflow.recommend(request, session));
      page.addAll(takePending(session, limit - page.size()));
    } else {
      RequestTelemetry.retrieval("SESSION_CACHE", page.size());
    }
    return IntStream.range(0, page.size())
        .mapToObj(
            index -> {
              Recommendation item = page.get(index);
              return new Recommendation(
                  item.getBookId(),
                  index + 1,
                  item.getScore(),
                  item.getTitle(),
                  item.getShortDescription());
            })
        .toList();
  }

  private List<Recommendation> takePending(Session session, int limit) {
    List<Recommendation> page = new ArrayList<>();
    Set<String> seen =
        new HashSet<>(session.getShownBooks() == null ? List.of() : session.getShownBooks());
    List<Recommendation> pending = session.getPendingRecommendations();
    while (!pending.isEmpty() && page.size() < limit) {
      Recommendation item = pending.removeFirst();
      if (item != null
          && item.getBookId() != null
          && !item.getBookId().isBlank()
          && seen.add(item.getBookId())) {
        page.add(item);
      }
    }
    return page;
  }

  private void applyTransition(Session session, SessionRequest request, Intent originalIntent) {
    if (originalIntent == Intent.UPDATE_PREFERENCES) {
      session.setPreferences(CONVERSATION_STATE.mergedPreferences(session, request));
    }
    if (originalIntent == Intent.CHANGE_COUNT) {
      session.setBookCount(CONVERSATION_STATE.resolveBookCount(request, session));
    }
    if (originalIntent == Intent.CLEAR_HISTORY) {
      session.clearHistory();
    }
    complete(request);
    session.addRecommendationIds(recommendationIds(request.getRecommendations()));
    session.addRequest(historyEntry(request));
  }

  private void complete(SessionRequest request) {
    if (request.getRequestId() == null) {
      request.setRequestId(UUID.randomUUID().toString());
    }
    if (request.getCreatedAt() == null) {
      request.setCreatedAt(Instant.now());
    }
  }

  private Intent originalIntent(SessionRequest request) {
    return request == null || request.getIntent() == null ? Intent.UNKNOWN : request.getIntent();
  }

  private List<String> recommendationIds(List<Recommendation> recommendations) {
    return recommendations.stream()
        .filter(recommendation -> recommendation != null)
        .map(Recommendation::getBookId)
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .toList();
  }

  private SessionRequest historyEntry(SessionRequest request) {
    SessionRequest entry = new SessionRequest();
    entry.setRequestId(request.getRequestId());
    entry.setCreatedAt(request.getCreatedAt());
    entry.setIntent(request.getIntent());
    entry.setPersonalised(request.isPersonalised());
    entry.setPreferenceClarificationRequired(request.isPreferenceClarificationRequired());
    entry.setPositiveSemanticQuery(request.getPositiveSemanticQuery());
    entry.setNegativeSemanticQuery(request.getNegativeSemanticQuery());
    entry.setMustInclude(request.getMustInclude());
    entry.setMustNotInclude(request.getMustNotInclude());
    entry.setBookCount(request.getBookCount());
    entry.setRecommendations(request.getRecommendations());
    return entry;
  }
}
