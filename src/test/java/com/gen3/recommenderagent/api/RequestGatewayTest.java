package com.gen3.recommenderagent.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.candidateretriever.CandidateRetrievalException;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.response.ResponseGenerator;
import com.gen3.recommenderagent.storage.sessionrepository.SessionCommitException;
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

  @Test
  void returnsBadRequestForInvalidInput() throws Exception {
    when(inputParser.parse(null))
        .thenThrow(
            new InvalidRequestException("I didn't catch that. What would you like to listen to?"));
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(gateway)
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    mockMvc
        .perform(
            post("/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .content(""))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("I didn't catch that. What would you like to listen to?"));

    verify(telemetry).fail(null);
  }

  @Test
  void returnsServiceUnavailableWhenCandidateRetrievalFails() throws Exception {
    SessionRequest parsedRequest = new SessionRequest();
    when(inputParser.parse("recommend science fiction"))
        .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<>(null, parsedRequest));
    when(sessionTransitionService.process("session", "user", parsedRequest))
        .thenThrow(new CandidateRetrievalException("Qdrant", new RuntimeException("timed out")));
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(gateway)
            .setControllerAdvice(new RequestExceptionHandler())
            .build();

    mockMvc
        .perform(
            post("/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .content("recommend science fiction"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .string(
                    "I'm having trouble reaching the audiobook catalogue right now. Please try again in a moment."));
  }

  @Test
  void returnsRetryAdviceWhenTheSessionIsBusy() throws Exception {
    SessionRequest parsedRequest = new SessionRequest();
    when(inputParser.parse("recommend science fiction"))
        .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<>(null, parsedRequest));
    when(sessionTransitionService.process("session", "user", parsedRequest))
        .thenThrow(
            new com.gen3.recommenderagent.storage.sessionrepository.redis.SessionBusyException());
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);

    MockMvcBuilders.standaloneSetup(gateway)
        .setControllerAdvice(new RequestExceptionHandler())
        .build()
        .perform(
            post("/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .content("recommend science fiction"))
        .andExpect(status().isConflict())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Retry-After", "1"));
  }

  @Test
  void returnsServiceUnavailableWhenTheSessionCommitFails() throws Exception {
    SessionRequest parsedRequest = new SessionRequest();
    when(inputParser.parse("recommend science fiction"))
        .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<>(null, parsedRequest));
    when(sessionTransitionService.process("session", "user", parsedRequest))
        .thenThrow(new SessionCommitException());
    RequestGateway gateway =
        new RequestGateway(inputParser, sessionTransitionService, responseGenerator, telemetry);

    MockMvcBuilders.standaloneSetup(gateway)
        .setControllerAdvice(new RequestExceptionHandler())
        .build()
        .perform(
            post("/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .content("recommend science fiction"))
        .andExpect(status().isServiceUnavailable());
  }
}
