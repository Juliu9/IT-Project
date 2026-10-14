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
    Session session =
        RequestTelemetry.measure("sessionLoadMs", () -> sessionService.load(sessionId, userId));

    if (request.getIntent() == null) {
      request.setIntent(Intent.UNKNOWN);
    }
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

    session.addRecommendationIds(recommendationIds(result == null ? request : result));
    RequestTelemetry.measure(
        "sessionWriteMs",
        () -> {
          sessionService.update(session, request, result);
          return null;
        });
    return result;
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
