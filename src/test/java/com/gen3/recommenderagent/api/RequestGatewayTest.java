package com.gen3.recommenderagent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.RequestApplicationService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.response.ResponseGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;

class RequestGatewayTest {

  @Test
  void demoResponseContainsFrontendPayload() {
    InputParser inputParser = mock(InputParser.class);
    RequestApplicationService applicationService = mock(RequestApplicationService.class);
    ResponseGenerator responseGenerator = mock(ResponseGenerator.class);
    RequestGateway gateway = new RequestGateway(inputParser, applicationService, responseGenerator);

    SessionRequest parsed = new SessionRequest();
    parsed.setRequestId("request-1");
    parsed.setIntent(Intent.RECOMMENDATION);
    parsed.setRawText("Recommend a mystery");
    ChatResponse chatResponse = new ChatResponse(List.of());

    when(inputParser.parse("Recommend a mystery"))
        .thenReturn(new ResponseEntity<>(chatResponse, parsed));
    when(applicationService.process("session-1", "user-1", parsed)).thenReturn(parsed);
    when(responseGenerator.generate(parsed.getRecommendations(), parsed))
        .thenReturn("Try this audiobook.");

    DemoRecommendationResponse result =
        gateway.handleDemoRequest("session-1", "user-1", "Recommend a mystery");

    assertEquals("Try this audiobook.", result.response());
    assertEquals(parsed, result.sessionRequest());
    assertNotNull(result.metrics());
    assertNotNull(result.metrics().inputParser());
    assertNotNull(result.metrics().recommendationEngine());
    assertNotNull(result.metrics().responseGenerator());
  }
}
