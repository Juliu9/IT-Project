package com.gen3.recommenderagent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.action.RequestContextMerger;
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
    when(action.execute(any(), eq(session))).thenReturn(result);
    when(sessionService.update(any(), any(), any())).thenReturn(true);

    RequestApplicationService service =
        new RequestApplicationService(sessionService, actionRegistry, new RequestContextMerger());

    assertSame(result, service.process("session-1", "user-1", request));

    InOrder order = inOrder(sessionService, actionRegistry, action);
    order.verify(sessionService).load("session-1", "user-1");
    order.verify(actionRegistry).get(Intent.RECOMMENDATION);
    order.verify(action).execute(any(), eq(session));
    order.verify(sessionService).update(eq(session), any(), eq(result));
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
    when(action.execute(any(), eq(session))).thenAnswer(invocation -> invocation.getArgument(0));
    when(sessionService.update(any(), any(), any())).thenReturn(true);

    new RequestApplicationService(sessionService, actionRegistry, new RequestContextMerger())
        .process("session-2", "user-2", request);

    verify(actionRegistry).get(Intent.UNKNOWN);
    assertSame(Intent.UNKNOWN, request.getIntent());
  }

  @Test
  void retriesTheRequestAfterAConcurrentSessionWrite() {
    SessionService sessionService = mock(SessionService.class);
    ActionRegistry actionRegistry = mock(ActionRegistry.class);
    IntentAction action = mock(IntentAction.class);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    Session firstSession = new Session();
    Session updatedSession = new Session();

    when(sessionService.load("session-3", "user-3")).thenReturn(firstSession, updatedSession);
    when(actionRegistry.get(Intent.RECOMMENDATION)).thenReturn(action);
    when(action.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              SessionRequest attemptedRequest = invocation.getArgument(0);
              attemptedRequest.setRecommendations(List.of());
              return attemptedRequest;
            });
    when(sessionService.update(any(), any(), any())).thenReturn(false, true);

    new RequestApplicationService(sessionService, actionRegistry, new RequestContextMerger())
        .process("session-3", "user-3", request);

    verify(sessionService, org.mockito.Mockito.times(2)).load("session-3", "user-3");
    verify(action, org.mockito.Mockito.times(2)).execute(any(), any());
    verify(sessionService, org.mockito.Mockito.times(2)).update(any(), any(), any());
  }
}
