package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RequestApplicationServiceTest {

  @Test
  void processesRequestInStrictApplicationOrder() {
    SessionService sessionService = mock(SessionService.class);
    ActionRegistry actionRegistry = mock(ActionRegistry.class);
    IntentAction action = mock(IntentAction.class);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.NEW_RECOMMENDATION);
    SessionRequest result = new SessionRequest();
    SessionContext context = new SessionContext(new Session());

    when(sessionService.load("session-1", "user-1")).thenReturn(context);
    when(actionRegistry.get(Intent.NEW_RECOMMENDATION)).thenReturn(action);
    when(action.execute(request, context)).thenReturn(result);

    RequestApplicationService service =
        new RequestApplicationService(sessionService, actionRegistry);

    assertSame(result, service.process("session-1", "user-1", request));

    InOrder order = inOrder(sessionService, actionRegistry, action);
    order.verify(sessionService).load("session-1", "user-1");
    order.verify(actionRegistry).get(Intent.NEW_RECOMMENDATION);
    order.verify(action).execute(request, context);
    order.verify(sessionService).update(context, request, result);
  }

  @Test
  void normalizesMissingIntentToUnknown() {
    SessionService sessionService = mock(SessionService.class);
    ActionRegistry actionRegistry = mock(ActionRegistry.class);
    IntentAction action = mock(IntentAction.class);
    SessionRequest request = new SessionRequest();
    SessionContext context = new SessionContext(new Session());

    when(sessionService.load("session-2", "user-2")).thenReturn(context);
    when(actionRegistry.get(Intent.UNKNOWN)).thenReturn(action);
    when(action.execute(request, context)).thenReturn(request);

    new RequestApplicationService(sessionService, actionRegistry)
        .process("session-2", "user-2", request);

    verify(actionRegistry).get(Intent.UNKNOWN);
    assertSame(Intent.UNKNOWN, request.getIntent());
  }
}
