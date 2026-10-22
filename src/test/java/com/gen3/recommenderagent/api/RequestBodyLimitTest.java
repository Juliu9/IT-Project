package com.gen3.recommenderagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.response.ResponseGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RequestBodyLimitTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void boundsReadingWithOrWithoutDeclaredLength(boolean declaredLength) throws Exception {
    var advice = new RequestBodyLimit();
    var oversized = new org.springframework.mock.http.MockHttpInputMessage(new byte[40001]);
    if (declaredLength) {
      oversized.getHeaders().setContentLength(40001);
    }
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    oversized,
                    null,
                    String.class,
                    org.springframework.http.converter.StringHttpMessageConverter.class))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var allowed = new org.springframework.mock.http.MockHttpInputMessage(new byte[40000]);
    if (declaredLength) {
      allowed.getHeaders().setContentLength(40000);
    }
    assertThat(
            advice
                .beforeBodyRead(
                    allowed,
                    null,
                    String.class,
                    org.springframework.http.converter.StringHttpMessageConverter.class)
                .getBody()
                .readAllBytes())
        .hasSize(40000);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/recommendations", "/api/v1/recommendations/demo"})
  void endpointRejectsOversizedBodiesBeforeParsing(String endpoint) throws Exception {
    var parser = mock(InputParser.class);
    var transitions = mock(SessionTransitionService.class);
    var response = mock(ResponseGenerator.class);
    var gateway = new RequestGateway(parser, transitions, response);
    var mvc =
        MockMvcBuilders.standaloneSetup(gateway, new DemoWebsite(gateway))
            .setControllerAdvice(new RequestBodyLimit(), new RequestExceptionHandler())
            .build();
    var request =
        post(endpoint)
            .header("X-User-Id", "user")
            .header("X-Session-Id", "session")
            .contentType("text/plain")
            .content("x".repeat(40001));
    mvc.perform(request).andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(parser, transitions, response);
  }
}
