package com.gen3.recommenderagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

class RequestTelemetryTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(RequestTelemetry.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final RequestTelemetry telemetry = new RequestTelemetry(new ObjectMapper());

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  @Test
  void logsTheCompleteRequestSummary() {
    SessionRequest request = new SessionRequest();
    request.setRequestId("request-1");
    request.setIntent(Intent.RECOMMENDATION);

    telemetry.start();
    RequestTelemetry.measure("parseMs", () -> request);
    RequestTelemetry.retrievalBackend("qdrant");
    RequestTelemetry.retrieval("HYBRID", 50);
    RequestTelemetry.ai(
        "generate_response", 2, new AiOperationObserver.AiUsage("model", 10, 5, 15, true));
    telemetry.complete(request);

    assertThat(lastMessage())
        .contains("\"event\":\"recommendation.completed\"")
        .contains("\"requestId\":\"request-1\"")
        .contains("\"outcome\":\"SUCCESS\"")
        .contains("\"backend\":\"qdrant\"")
        .contains("\"inputTokens\":10")
        .contains("\"outputTokens\":5");
  }

  @Test
  void logsACompletionEventForFailures() {
    telemetry.start();

    telemetry.fail(null);

    assertThat(lastMessage()).contains("\"outcome\":\"FAILURE\"");
  }

  @Test
  void doesNotCarryStageDataIntoTheNextRequest() {
    telemetry.start();
    RequestTelemetry.measure("parseMs", () -> null);
    telemetry.complete(null);

    telemetry.start();
    telemetry.fail(null);

    assertThat(lastMessage()).contains("\"stages\":{}");
  }

  private String lastMessage() {
    return appender.list.getLast().getFormattedMessage();
  }
}
