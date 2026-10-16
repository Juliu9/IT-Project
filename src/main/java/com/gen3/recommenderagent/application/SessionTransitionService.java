package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.sessionservice.SessionCommitException;
import com.gen3.recommenderagent.application.sessionservice.SessionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owns one ordered, all-or-nothing transition of a user's conversational session. */
@Service
public class SessionTransitionService {

  private final SessionService sessionService;
  private final RequestContextMerger requestContextMerger;
  private final RecommendationWorkflow recommendationWorkflow;

  public SessionTransitionService(
      SessionService sessionService,
      RequestContextMerger requestContextMerger,
      RecommendationWorkflow recommendationWorkflow) {
    this.sessionService = sessionService;
    this.requestContextMerger = requestContextMerger;
    this.recommendationWorkflow = recommendationWorkflow;
  }

  public SessionRequest process(String sessionId, String userId, SessionRequest request) {
    if (request.getIntent() == null) {
      request.setIntent(Intent.UNKNOWN);
    }
    return RequestTelemetry.measure(
        "sessionLockMs",
        () ->
            sessionService.withSessionLock(
                sessionId, userId, () -> processLocked(sessionId, userId, request)));
  }

  private SessionRequest processLocked(String sessionId, String userId, SessionRequest original) {
    Session session =
        RequestTelemetry.measure("sessionLoadMs", () -> sessionService.load(sessionId, userId));
    SessionRequest result = copyOf(original);
    prepare(result, session);
    result.setRecommendations(
        recommendationIntent(result.getIntent())
            ? recommendationWorkflow.recommend(result, session)
            : List.of());
    applyTransition(session, result);
    boolean saved = RequestTelemetry.measure("sessionWriteMs", () -> sessionService.save(session));
    if (!saved) {
      throw new SessionCommitException();
    }
    return result;
  }

  private void prepare(SessionRequest request, Session session) {
    if (session.isAwaitingPersonalisedPreferences() && acceptsPreferences(request.getIntent())) {
      if (request.getIntent() == Intent.UPDATE_PREFERENCES) {
        session.setPreferences(requestContextMerger.mergedPreferences(request, session));
      }
      requestContextMerger.applyPreferences(request, session);
      request.setIntent(Intent.RECOMMENDATION);
      request.setPersonalised(true);
    }
    if (request.getIntent() == Intent.REFINE || request.getIntent() == Intent.MORE_RESULTS) {
      requestContextMerger.applyPreviousSearch(request, session);
    }
    if (recommendationIntent(request.getIntent())) {
      requestContextMerger.applyPreferences(request, session);
      request.setBookCount(requestContextMerger.resolveBookCount(request, session));
    }
  }

  private void applyTransition(Session session, SessionRequest request) {
    if (request.getIntent() == Intent.UPDATE_PREFERENCES) {
      session.setPreferences(requestContextMerger.mergedPreferences(request, session));
    }
    if (request.getIntent() == Intent.CHANGE_COUNT) {
      session.setBookCount(requestContextMerger.resolveBookCount(request, session));
    }
    if (request.getIntent() == Intent.CLEAR_HISTORY) {
      session.clearHistory();
    }
    complete(request);
    session.addRecommendationIds(recommendationIds(request));
    session.addRequest(historyEntry(request));
  }

  private boolean acceptsPreferences(Intent intent) {
    return intent == Intent.UPDATE_PREFERENCES
        || intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS
        || intent == Intent.UNKNOWN;
  }

  private boolean recommendationIntent(Intent intent) {
    return intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS;
  }

  private void complete(SessionRequest request) {
    if (request.getRequestId() == null) {
      request.setRequestId(UUID.randomUUID().toString());
    }
    if (request.getCreatedAt() == null) {
      request.setCreatedAt(Instant.now());
    }
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

  private SessionRequest copyOf(SessionRequest source) {
    SessionRequest copy = new SessionRequest();
    copy.setRequestId(source.getRequestId());
    copy.setRawText(source.getRawText());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setIntent(source.getIntent());
    copy.setPositiveSemanticQuery(source.getPositiveSemanticQuery());
    copy.setNegativeSemanticQuery(source.getNegativeSemanticQuery());
    copy.setMustInclude(source.getMustInclude());
    copy.setMustNotInclude(source.getMustNotInclude());
    copy.setBookCount(source.getBookCount());
    copy.setPersonalised(source.isPersonalised());
    copy.setPreferenceClarificationRequired(source.isPreferenceClarificationRequired());
    return copy;
  }
}
