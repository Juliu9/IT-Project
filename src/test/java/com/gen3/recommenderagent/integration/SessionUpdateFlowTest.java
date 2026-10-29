package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.response.ResponseGenerator;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionUpdateFlowTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void responseFailureLeavesRecommendationsCommitted(boolean demo) {
    var sessions = new RecommendationFlowTestSupport.InMemorySessionRepository();
    InputParser parser = mock(InputParser.class);
    UserIntent request = new UserIntent();
    request.setIntent(ConversationAction.RECOMMENDATION);
    when(parser.parseUserIntent(
            org.mockito.ArgumentMatchers.eq("recommend a book"),
            org.mockito.ArgumentMatchers
                .<java.util.function.Supplier<com.gen3.recommenderagent.inputparser.SessionContext>>
                    any()))
        .thenReturn(request);
    RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
    when(workflow.recommend(any(), any()))
        .thenReturn(List.of(new Recommendation("book-1", 1, 0.9, "First")));
    ResponseGenerator generator = mock(ResponseGenerator.class);
    when(generator.generate(anyList(), any()))
        .thenAnswer(
            invocation -> {
              assertEquals(
                  List.of("book-1"),
                  sessions.getSession("session", "user").getActiveSearch().getShownBookIds());
              throw new IllegalStateException("response failed");
            });
    RequestGateway gateway =
        new RequestGateway(
            parser, ConversationTestSupport.transitions(sessions, workflow), generator);

    assertThrows(
        IllegalStateException.class,
        () -> {
          if (demo) {
            gateway.handleDemoRequest("session", "user", "recommend a book");
          } else {
            gateway.handleRequest("session", "user", "recommend a book");
          }
        });

    var stored = sessions.getSession("session", "user");
    assertEquals(List.of("book-1"), stored.getActiveSearch().getShownBookIds());
    assertNotNull(stored.getActiveSearch().getSearch());
    assertEquals(1, stored.getVersion());
  }
}
