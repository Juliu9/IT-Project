package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.action.RequestContextMerger;
import com.gen3.recommenderagent.application.sessionservice.SessionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.util.List;
import org.springframework.stereotype.Service;

/** Coordinates the handling of the different types of input based on INTENT. */
@Service
public class RequestApplicationService {

  private static final int SESSION_UPDATE_ATTEMPTS = 3;
  private final SessionService sessionService;
  private final ActionRegistry actionRegistry;
  private final RequestContextMerger requestContextMerger;

  public RequestApplicationService(
      SessionService sessionService,
      ActionRegistry actionRegistry,
      RequestContextMerger requestContextMerger) {
    this.sessionService = sessionService;
    this.actionRegistry = actionRegistry;
    this.requestContextMerger = requestContextMerger;
  }

  public SessionRequest process(String sessionId, String userId, SessionRequest request) {
    if (request.getIntent() == null) {
      request.setIntent(Intent.UNKNOWN);
    }
    for (int attempt = 0; attempt < SESSION_UPDATE_ATTEMPTS; attempt++) {
      Session session =
          RequestTelemetry.measure("sessionLoadMs", () -> sessionService.load(sessionId, userId));
      SessionRequest attemptRequest = copyOf(request);
      SessionRequest result = processAttempt(session, attemptRequest);

      session.addRecommendationIds(recommendationIds(result == null ? attemptRequest : result));
      boolean saved =
          RequestTelemetry.measure(
              "sessionWriteMs", () -> sessionService.update(session, attemptRequest, result));
      if (saved) {
        return result;
      }
    }
    throw new IllegalStateException("Session was updated concurrently; please retry");
  }

  private SessionRequest processAttempt(Session session, SessionRequest request) {
    if (session.isAwaitingPersonalisedPreferences()
        && (request.getIntent() == Intent.UPDATE_PREFERENCES
            || request.getIntent() == Intent.RECOMMENDATION
            || request.getIntent() == Intent.REFINE
            || request.getIntent() == Intent.MORE_RESULTS
            || request.getIntent() == Intent.UNKNOWN)) {
      requestContextMerger.updatePreferences(session, request);
      request.setIntent(Intent.RECOMMENDATION);
      request.setPersonalised(true);
    }
    IntentAction action = actionRegistry.get(request.getIntent());
    SessionRequest result = action.execute(request, session);
    return result;
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
    copy.setRecommendations(source.getRecommendations());
    return copy;
  }

  private List<String> recommendationIds(SessionRequest result) {
    if (result == null || result.getRecommendations() == null) {
      return List.of();
    }
    return result.getRecommendations().stream()
        .filter(recommendation -> recommendation != null)
        .map(recommendation -> recommendation.getBookId())
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .toList();
  }
}
