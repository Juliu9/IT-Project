package com.gen3.recommenderagent.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.RequestApplicationService;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.response.ResponseGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequestGatewayTest {

  @Mock private InputParser inputParser;
  @Mock private RequestApplicationService requestApplicationService;
  @Mock private ResponseGenerator responseGenerator;
  @Mock private RequestTelemetry telemetry;

  @Test
  void recordsFailureAndPreservesTheOriginalException() {
    IllegalArgumentException exception = new IllegalArgumentException("invalid request");
    when(inputParser.parse("bad request")).thenThrow(exception);
    RequestGateway gateway =
        new RequestGateway(inputParser, requestApplicationService, responseGenerator, telemetry);

    assertThatThrownBy(() -> gateway.handleRequest("session", "user", "bad request"))
        .isSameAs(exception);

    verify(telemetry).start();
    verify(telemetry).fail(null);
  }
}
