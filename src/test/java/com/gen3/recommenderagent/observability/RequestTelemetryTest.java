package com.gen3.recommenderagent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
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
  void standardRequestsKeepAggregateTelemetryWithoutCollectingDemoDetails() {
    telemetry.start();
    RequestTelemetry.ai("parse", 1, new AiOperationObserver.AiUsage("parser", 10, 2, 12, true));
    assertThat(telemetry.snapshot().operations()).isEmpty();
    telemetry.complete(null);
    assertThat(lastMessage()).contains("\"inputTokens\":10");
  }

  @Test
  void snapshotsKeepPerOperationUsageAndDoNotLeakIntoTheNextRequest() {
    telemetry.startDemo();
    RequestTelemetry.ai("parse", 1, new AiOperationObserver.AiUsage("parser", 10, 2, 12, true));
    RequestTelemetry.ai(
        "embed_query", 1, new AiOperationObserver.AiUsage("embedder", 3, 0, 3, true));
    RequestTelemetry.ai(
        "embed_query", 1, new AiOperationObserver.AiUsage("embedder", 4, 0, 4, true));
    RequestTelemetry.ai(
        "generate_response", 1, new AiOperationObserver.AiUsage(null, null, null, null, false));
    var snapshot = telemetry.snapshot();
    assertThat(snapshot.operations().get("parse").promptTokens()).isEqualTo(10);
    assertThat(snapshot.operations().get("embed_query").promptTokens()).isEqualTo(7);
    assertThat(snapshot.operations().get("generate_response").available()).isFalse();
    telemetry.complete(null);
    telemetry.start();
    assertThat(telemetry.snapshot().operations()).isEmpty();
    assertThat(snapshot.operations()).hasSize(3);
    telemetry.complete(null);
  }

  @Test
  void logsTheCompleteRequestSummary() {
    ConversationDraft request = new ConversationDraft();
    request.setRequestId("request-1");
    request.setIntent(ConversationAction.RECOMMENDATION);

    telemetry.start();
    RequestTelemetry.measure("parseMs", () -> request);
    RequestTelemetry.retrievalBackend("qdrant");
    RequestTelemetry.retrieval("HYBRID", 50);
    RequestTelemetry.ai(
        "generate_response", 2, new AiOperationObserver.AiUsage("model", 10, 5, 15, true));
    telemetry.complete(ConversationDraft.toTurn(request));

    assertThat(lastMessage())
        .contains("\"event\":\"recommendation.completed\"")
        .contains("\"requestId\":\"request-1\"")
        .contains("\"outcome\":\"SUCCESS\"")
        .contains("\"backend\":\"qdrant\"")
        .contains("\"inputTokens\":10")
        .contains("\"outputTokens\":5");
  }

  @Test
  void failedModelRetriesRemainInTheSuccessfulFallbackSummary() {
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    var retry =
        new org.springframework.core.retry.RetryTemplate(
            org.springframework.core.retry.RetryPolicy.builder()
                .maxRetries(2)
                .delay(java.time.Duration.ZERO)
                .build());
    beans.registerSingleton("retryTemplate", retry);
    var observer =
        new AiOperationObserver(
            beans.getBeanProvider(org.springframework.core.retry.RetryTemplate.class));
    telemetry.start();
    RequestTelemetry.ai("parse", 1, new AiOperationObserver.AiUsage("parser", 10, 2, 12, true));
    assertThatThrownBy(
            () ->
                observer.observe(
                    "generate_response",
                    () ->
                        retry.<String>invoke(
                            () -> {
                              throw new IllegalStateException("unavailable");
                            }),
                    response -> new AiOperationObserver.AiUsage(null, null, null, null, false)))
        .isInstanceOf(RuntimeException.class);
    telemetry.complete(new ConversationTurn());
    var summary = new ObjectMapper().readTree(lastMessage());
    assertThat(summary.path("outcome").asText()).isEqualTo("SUCCESS");
    assertThat(summary.path("ai").path("calls").asInt()).isEqualTo(2);
    assertThat(summary.path("ai").path("attempts").asInt()).isEqualTo(4);
    assertThat(summary.path("ai").path("lastOperation").asText()).isEqualTo("generate_response");
    assertThat(summary.path("ai").path("usageAvailable").asBoolean()).isFalse();
    assertThat(summary.path("ai").path("inputTokens").asInt()).isEqualTo(10);
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
