package com.gen3.recommenderagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.RequestApplicationService;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.response.ResponseGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;

class RequestGatewayTest {

  @Test
  void demoResponseContainsTheDisplayedMetrics() {
    InputParser inputParser = mock(InputParser.class);
    RequestApplicationService applicationService = mock(RequestApplicationService.class);
    ResponseGenerator responseGenerator = mock(ResponseGenerator.class);
    SessionRequest request = new SessionRequest();
    ChatResponse parseResponse = new ChatResponse(List.of());
    when(inputParser.parse("Recommend a mystery"))
        .thenReturn(new ResponseEntity<>(parseResponse, request));
    when(applicationService.process("session-1", "user-1", request)).thenReturn(request);
    when(responseGenerator.generate(request.getRecommendations(), request)).thenReturn("Try this.");

    DemoRecommendationResponse response =
        new RequestGateway(inputParser, applicationService, responseGenerator)
            .handleDemoRequest("session-1", "user-1", "Recommend a mystery");

    assertThat(response.response()).isEqualTo("Try this.");
    assertThat(response.sessionRequest()).isSameAs(request);
    assertThat(response.metrics().parseMs()).isGreaterThanOrEqualTo(0);
    assertThat(response.metrics().recommendMs()).isGreaterThanOrEqualTo(0);
    assertThat(response.metrics().responseMs()).isGreaterThanOrEqualTo(0);
    assertThat(response.metrics().totalMs()).isGreaterThanOrEqualTo(0);
  }
}
