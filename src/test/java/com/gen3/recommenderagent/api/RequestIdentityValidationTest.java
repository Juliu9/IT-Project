package com.gen3.recommenderagent.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.catalogue.port.AudiobookDetailsLookup;
import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.inputparser.SessionContext;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.response.ResponseGenerator;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RequestIdentityValidationTest {
  private final InputParser parser = mock(InputParser.class);
  private final SessionTransitionService sessions = mock(SessionTransitionService.class);
  private final ResponseGenerator responses = mock(ResponseGenerator.class);
  private final RequestGateway gateway = new RequestGateway(parser, sessions, responses);

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidIdsAreRejectedBeforeAnyWorkForBothEntryPoints(boolean demo) {
    for (String invalid : new String[] {null, "", " ", "\t\n", "x".repeat(257)}) {
      for (boolean user : new boolean[] {false, true}) {
        String sessionId = user ? "session" : invalid;
        String userId = user ? invalid : "user";
        assertThatThrownBy(
                () -> {
                  if (demo) {
                    gateway.handleDemoRequest(sessionId, userId, "help");
                  } else {
                    gateway.handleRequest(sessionId, userId, "help");
                  }
                })
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining(user ? "X-User-Id" : "X-Session-Id");
      }
    }
    verifyNoInteractions(parser, sessions, responses);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/recommendations", "/api/v1/recommendations/demo"})
  void missingBlankAndOversizedHeadersReturn400(String endpoint) throws Exception {
    var catalogue = mock(AudiobookDetailsLookup.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway, catalogue))
            .setControllerAdvice(new RequestExceptionHandler())
            .build();
    for (String header : new String[] {"X-User-Id", "X-Session-Id"}) {
      String other = header.equals("X-User-Id") ? "X-Session-Id" : "X-User-Id";
      mvc.perform(post(endpoint).header(other, "valid").content("help"))
          .andExpect(status().isBadRequest());
      for (String invalid : new String[] {"", " ", "x".repeat(257)}) {
        mvc.perform(post(endpoint).header(other, "valid").header(header, invalid).content("help"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(header + " must be nonblank and at most 256 characters."));
      }
    }
    verifyNoInteractions(parser, sessions, responses, catalogue);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void opaqueIdsAtTheLimitArePassedThroughUnchanged(boolean demo) {
    String userId = "external/user:" + "u".repeat(242);
    String sessionId = "s".repeat(256);
    var intent = new UserIntent();
    when(parser.parseUserIntent(
            eq("help"), org.mockito.ArgumentMatchers.<Supplier<SessionContext>>any()))
        .thenReturn(intent);
    when(sessions.process(eq(sessionId), eq(userId), eq("help"), eq(intent), any()))
        .thenAnswer(
            call ->
                call.<Function<ConversationTurn, Object>>getArgument(4)
                    .apply(new ConversationTurn()));
    if (demo) {
      gateway.handleDemoRequest(sessionId, userId, "help");
    } else {
      gateway.handleRequest(sessionId, userId, "help");
    }
    verify(sessions).process(eq(sessionId), eq(userId), eq("help"), eq(intent), any());
  }
}
