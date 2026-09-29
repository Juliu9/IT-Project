package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.sessionservice.SessionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RequestApplicationServiceTest {

  @Test
  void processesRequestInStrictApplicationOrder() {
    SessionService sessionService = mock(SessionService.class);
    ActionRegistry actionRegistry = mock(ActionRegistry.class);
    IntentAction action = mock(IntentAction.class);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    SessionRequest result = new SessionRequest();
    result.setRecommendations(
        List.of(
            new Recommendation("book-1", 1, 0.9, "First"),
            new Recommendation("book-2", 2, 0.8, "Second")));
    Session session = new Session();

    when(sessionService.load("session-1", "user-1")).thenReturn(session);
    when(actionRegistry.get(Intent.RECOMMENDATION)).thenReturn(action);
    when(action.execute(request, session)).thenReturn(result);

    RequestApplicationService service =
        new RequestApplicationService(sessionService, actionRegistry);

    assertSame(result, service.process("session-1", "user-1", request));

    InOrder order = inOrder(sessionService, actionRegistry, action);
    order.verify(sessionService).load("session-1", "user-1");
    order.verify(actionRegistry).get(Intent.RECOMMENDATION);
    order.verify(action).execute(request, session);
    order.verify(sessionService).update(session, request, result);
    assertEquals(List.of("book-1", "book-2"), session.getShownBooks());
  }

  @Test
  void normalizesMissingIntentToUnknown() {
    SessionService sessionService = mock(SessionService.class);
    ActionRegistry actionRegistry = mock(ActionRegistry.class);
    IntentAction action = mock(IntentAction.class);
    SessionRequest request = new SessionRequest();
    Session session = new Session();

    when(sessionService.load("session-2", "user-2")).thenReturn(session);
    when(actionRegistry.get(Intent.UNKNOWN)).thenReturn(action);
    when(action.execute(request, session)).thenReturn(request);

    new RequestApplicationService(sessionService, actionRegistry)
        .process("session-2", "user-2", request);

    verify(actionRegistry).get(Intent.UNKNOWN);
    assertSame(Intent.UNKNOWN, request.getIntent());
  }
}
