package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    result.setRecommendations(
        CONVERSATION_STATE.isRecommendationIntent(result.getIntent())
            ? recommendationWorkflow.recommend(result, session)
            : List.of());
    applyTransition(session, result, originalIntent(original));
    return result;
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
    session.addRecommendationIds(recommendationIds(request));
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

  private List<String> recommendationIds(SessionRequest request) {
    return request.getRecommendations().stream()
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
