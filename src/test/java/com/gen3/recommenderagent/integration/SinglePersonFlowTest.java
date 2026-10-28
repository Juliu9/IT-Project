package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.api.RequestExceptionHandler;
import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.response.ResponseGenerator;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class SinglePersonFlowTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "mustInclude.authors",
        "mustInclude.narrators",
        "mustNotInclude.authors",
        "mustNotInclude.narrators",
        "positiveSemanticQuery.authors",
        "positiveSemanticQuery.narrators",
        "negativeSemanticQuery.authors",
        "negativeSemanticQuery.narrators"
      })
  void multiplePeopleAreRejectedWithoutRetrievalOrSessionChanges(String path) throws Exception {
    var sessions = new RecommendationFlowTestSupport.InMemorySessionRepository();
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    sessions.updateSession(session);
    var json = JsonMapper.builder().build();
    String before = json.writeValueAsString(session);
    var ai = mock(AiParser.class);
    var workflow = mock(RecommendationWorkflow.class);
    var responses = mock(ResponseGenerator.class);
    var gateway =
        new RequestGateway(
            new InputParser(new CommandParser(), ai),
            ConversationTestSupport.transitions(sessions, workflow),
            responses);
    var mvc =
        MockMvcBuilders.standaloneSetup(gateway)
            .setControllerAdvice(new RequestExceptionHandler())
            .build();
    for (String wrapper : List.of("", "preferenceUpdates", "preferenceRemovals")) {
      var model = json.createObjectNode();
      model.put("intent", wrapper.isEmpty() ? "RECOMMENDATION" : "UPDATE_PREFERENCES");
      var values = wrapper.isEmpty() ? model : model.putObject(wrapper);
      String[] parts = path.split("\\.");
      values.putObject(parts[0]).putArray(parts[1]).add("Person A").add("Person B");
      when(ai.parse(anyString())).thenReturn(json.treeToValue(model, UserIntent.class));
      mvc.perform(
              post("/api/v1/recommendations")
                  .header("X-Session-Id", "session")
                  .header("X-User-Id", "user")
                  .content("Choose books using Person A and Person B"))
          .andExpect(status().isBadRequest())
          .andExpect(content().string("Please choose only one author and one narrator at a time."));
      assertThat(json.writeValueAsString(sessions.getSession("session", "user"))).isEqualTo(before);
    }
    verifyNoInteractions(workflow, responses);
  }
}
