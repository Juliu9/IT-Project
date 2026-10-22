package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.application.RecommendationWorkflow;
import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.response.ResponseGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ResponseEntity;

class SessionUpdateFlowTest {

  @Test
  void responseFailureLeavesRecommendationsCommitted() {
    var sessions = new RecommendationFlowTestSupport.InMemorySessionRepository();
    InputParser parser = mock(InputParser.class);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    when(parser.parse("recommend a book")).thenReturn(new ResponseEntity<>(null, request));
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    when(workflow.recommend(any(), any()))
        .thenReturn(List.of(new Recommendation("book-1", 1, 0.9, "First")));
    ResponseGenerator generator = mock(ResponseGenerator.class);
    when(generator.generate(anyList(), any()))
        .thenAnswer(
            invocation -> {
              assertEquals(
                  List.of("book-1"), sessions.getSession("session", "user").getShownBooks());
              throw new IllegalStateException("response failed");
            });
    RequestGateway gateway =
        new RequestGateway(parser, new SessionTransitionService(sessions, workflow), generator);

    assertThrows(
        IllegalStateException.class,
        () -> gateway.handleRequest("session", "user", "recommend a book"));

    var stored = sessions.getSession("session", "user");
    assertEquals(List.of("book-1"), stored.getShownBooks());
    assertEquals(1, stored.getRequests().size());
    assertEquals(1, stored.getVersion());
  }
}
