package com.gen3.recommenderagent.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.response.ResponseGenerator;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalException;
import com.gen3.recommenderagent.storage.session.SessionCommitException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RequestGatewayTest {

  @Mock private InputParser inputParser;
  @Mock private SessionTransitionService sessionTransitionService;
  @Mock private ResponseGenerator responseGenerator;
  @Mock private RequestTelemetry telemetry;

  @Test
  void standardApiDoesNotBuildOrCollectDemoMetrics() throws Exception {
    var parsed = new SessionRequest();
    when(inputParser.parse("more")).thenReturn(parsed);
    when(sessionTransitionService.process("session", "user", parsed)).thenReturn(parsed);
    when(responseGenerator.generate(null, parsed)).thenReturn("No more recommendations.");
    var gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);
    MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
        .build()
        .perform(
            post("/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .contentType("text/plain")
                .content("more"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/plain"))
        .andExpect(content().string("No more recommendations."));
    var order =
        org.mockito.Mockito.inOrder(
            inputParser, sessionTransitionService, responseGenerator, telemetry);
    order.verify(telemetry).start();
    order.verify(inputParser).parse("more");
    order.verify(sessionTransitionService).process("session", "user", parsed);
    order.verify(responseGenerator).generate(null, parsed);
    order.verify(telemetry).complete(parsed);
    org.mockito.Mockito.verifyNoMoreInteractions(telemetry);
  }

  @Test
  void demoUsesTheExistingPipelineAndReturnsResolvedDetailsWithMetrics() throws Exception {
    SessionRequest parsed = new SessionRequest();
    SessionRequest resolved = new SessionRequest();
    resolved.setRequestId("request-1");
    resolved.setPersonalised(true);
    resolved.setIntent(com.gen3.recommenderagent.domain.Intent.MORE_RESULTS);
    when(inputParser.parse("more"))
        .thenAnswer(
            invocation -> {
              RequestTelemetry.ai(
                  "parse",
                  1,
                  new com.gen3.recommenderagent.observability.AiOperationObserver.AiUsage(
                      "parser", 10, 2, 12, true));
              return parsed;
            });
    when(sessionTransitionService.process("session", "user", parsed)).thenReturn(resolved);
    when(responseGenerator.generate(null, resolved)).thenReturn("No more recommendations.");
    var realTelemetry = new RequestTelemetry(new tools.jackson.databind.ObjectMapper());
    var gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, realTelemetry);

    MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
        .build()
        .perform(
            post("/api/v1/recommendations/demo")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .contentType("text/plain")
                .content("more"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.response")
                .value("No more recommendations."))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.sessionRequest.requestId")
                .value("request-1"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.sessionRequest.personalised")
                .value(true))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.metrics.stages.recommendMs")
                .isNumber())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.metrics.operations.parse.promptTokens")
                .value(10));

    MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
        .build()
        .perform(
            post("/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .contentType("text/plain")
                .content("more"))
        .andExpect(status().isOk())
        .andExpect(content().string("No more recommendations."));
  }

  @Test
  void recordsFailureAndPreservesTheOriginalException() {
    IllegalArgumentException exception = new IllegalArgumentException("invalid request");
    when(inputParser.parse("bad request")).thenThrow(exception);
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);

    assertThatThrownBy(() -> gateway.handleRequest("session", "user", "bad request"))
        .isSameAs(exception);

    verify(telemetry).start();
    verify(telemetry).fail(null);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullAndEmptySource
  @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "\n\t"})
  void returnsTheBlankInputFallbackWithoutModelOrSessionWork(String rawText) throws Exception {
    var model = mock(org.springframework.ai.chat.client.ChatClient.class);
    var realParser =
        new com.gen3.recommenderagent.inputparser.AiInputParser(
            model, com.gen3.recommenderagent.observability.AiOperationObserver.noop());
    RequestGateway gateway =
        new RequestGateway(realParser, sessionTransitionService, responseGenerator, telemetry);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    for (String endpoint :
        java.util.List.of("/api/v1/recommendations", "/api/v1/recommendations/demo")) {
      mockMvc
          .perform(
              post(endpoint)
                  .header("X-Session-Id", "session")
                  .header("X-User-Id", "user")
                  .content(rawText == null ? "" : rawText))
          .andExpect(status().isBadRequest())
          .andExpect(content().string("I didn't catch that. What would you like to listen to?"));
    }
    verify(telemetry, org.mockito.Mockito.times(2)).fail(null);
    verifyNoInteractions(model, sessionTransitionService, responseGenerator);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"/api/v1/recommendations", "/api/v1/recommendations/demo"})
  void returnsServiceUnavailableWhenCandidateRetrievalFails(String endpoint) throws Exception {
    SessionRequest parsedRequest = new SessionRequest();
    when(inputParser.parse("recommend science fiction")).thenReturn(parsedRequest);
    when(sessionTransitionService.process("session", "user", parsedRequest))
        .thenThrow(new CandidateRetrievalException("Qdrant", new RuntimeException("timed out")));
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    mockMvc
        .perform(
            post(endpoint)
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .content("recommend science fiction"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .string(
                    "I'm having trouble reaching the audiobook catalogue right now. Please try again in a moment."));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"/api/v1/recommendations", "/api/v1/recommendations/demo"})
  void returnsRetryAdviceWhenTheSessionIsBusy(String endpoint) throws Exception {
    SessionRequest parsedRequest = new SessionRequest();
    when(inputParser.parse("recommend science fiction")).thenReturn(parsedRequest);
    when(sessionTransitionService.process("session", "user", parsedRequest))
        .thenThrow(new com.gen3.recommenderagent.storage.session.SessionBusyException());
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);

    MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
        .setControllerAdvice(new RequestExceptionHandler())
        .build()
        .perform(
            post(endpoint)
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .content("recommend science fiction"))
        .andExpect(status().isConflict())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Retry-After", "1"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"/api/v1/recommendations", "/api/v1/recommendations/demo"})
  void returnsServiceUnavailableWhenTheSessionCommitFails(String endpoint) throws Exception {
    SessionRequest parsedRequest = new SessionRequest();
    when(inputParser.parse("recommend science fiction")).thenReturn(parsedRequest);
    when(sessionTransitionService.process("session", "user", parsedRequest))
        .thenThrow(new SessionCommitException());
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);

    MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
        .setControllerAdvice(new RequestExceptionHandler())
        .build()
        .perform(
            post(endpoint)
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .content("recommend science fiction"))
        .andExpect(status().isServiceUnavailable());
  }
}
