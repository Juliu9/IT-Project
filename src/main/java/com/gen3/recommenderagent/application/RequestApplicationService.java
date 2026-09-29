package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.application.sessionservice.SessionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Service;

/** Coordinates the complete request lifecycle without containing intent-specific business logic. */
@Service
public class RequestApplicationService {

  private final SessionService sessionService;
  private final ActionRegistry actionRegistry;

  public RequestApplicationService(SessionService sessionService, ActionRegistry actionRegistry) {
    this.sessionService = sessionService;
    this.actionRegistry = actionRegistry;
  }

  public SessionRequest process(String sessionId, String userId, SessionRequest request) {
    Session session = sessionService.load(sessionId, userId);

    if (request.getIntent() == null) {
      request.setIntent(Intent.UNKNOWN);
    }
    IntentAction action = actionRegistry.get(request.getIntent());
    SessionRequest result = action.execute(request, session);

    sessionService.update(session, request, result);
    return result;
  }
}
