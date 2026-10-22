package com.gen3.recommenderagent.observability;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Emits one safe, structured summary for each recommendation request. */
@Component
public class RequestTelemetry {

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestTelemetry.class);
  // shortcut: request telemetry is synchronous; use OpenTelemetry context propagation for async
  // work.
  private static final ThreadLocal<RequestState> CURRENT = new ThreadLocal<>();
  private final ObjectMapper objectMapper;

  public RequestTelemetry(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void start() {
    CURRENT.set(new RequestState(false));
  }

  public void startDemo() {
    CURRENT.set(new RequestState(true));
  }

  public static <T> T measure(String stage, Supplier<T> action) {
    RequestState state = CURRENT.get();
    if (state == null) {
      return action.get();
    }
    Instant startedAt = Instant.now();
    try {
      return action.get();
    } finally {
      state.stages.put(stage, Duration.between(startedAt, Instant.now()).toMillis());
    }
  }

  public static void responseMode(String responseMode) {
    RequestState state = CURRENT.get();
    if (state != null) {
      state.responseMode = responseMode;
    }
  }

  public static void retrieval(String mode, int candidates) {
    RequestState state = CURRENT.get();
    if (state != null) {
      state.retrieval.put("mode", mode);
      state.retrieval.put("candidateCount", candidates);
    }
  }

  public static void retrievalBackend(String backend) {
    RequestState state = CURRENT.get();
    if (state != null) {
      state.retrieval.put("backend", backend);
    }
  }

  public static void ai(String operation, int attempts, AiOperationObserver.AiUsage usage) {
    RequestState state = CURRENT.get();
    if (state == null) {
      return;
    }
    if (state.operations != null) {
      state.operations.merge(
          operation,
          usage,
          (previous, next) ->
              new AiOperationObserver.AiUsage(
                  next.model(),
                  value(previous.promptTokens()) + value(next.promptTokens()),
                  value(previous.completionTokens()) + value(next.completionTokens()),
                  value(previous.totalTokens()) + value(next.totalTokens()),
                  previous.available() && next.available()));
    }
    state.aiCalls++;
    state.aiAttempts += attempts;
    state.usageAvailable &= usage.available();
    state.ai.put("calls", state.aiCalls);
    state.ai.put("attempts", state.aiAttempts);
    state.ai.put("lastOperation", operation);
    state.ai.put("model", usage.model());
    state.ai.put("usageAvailable", state.usageAvailable);
    if (usage.available()) {
      state.inputTokens += value(usage.promptTokens());
      state.outputTokens += value(usage.completionTokens());
      state.ai.put("inputTokens", state.inputTokens);
      state.ai.put("outputTokens", state.outputTokens);
    }
  }

  public Snapshot snapshot() {
    RequestState state = CURRENT.get();
    return state == null
        ? new Snapshot(0, Map.of(), Map.of(), Map.of())
        : new Snapshot(
            state.durationMillis(),
            Map.copyOf(state.stages),
            state.operations == null ? Map.of() : Map.copyOf(state.operations),
            Map.copyOf(state.retrieval));
  }

  public record Snapshot(
      long totalMs,
      Map<String, Long> stages,
      Map<String, AiOperationObserver.AiUsage> operations,
      Map<String, Object> retrieval) {}

  public void complete(SessionRequest request) {
    finish(request, "SUCCESS");
  }

  public void fail(SessionRequest request) {
    finish(request, "FAILURE");
  }

  private void finish(SessionRequest request, String outcome) {
    RequestState state = CURRENT.get();
    if (state == null) {
      return;
    }
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("event", "recommendation.completed");
    event.put("requestId", request == null ? null : request.getRequestId());
    event.put("outcome", outcome);
    event.put("durationMs", state.durationMillis());
    event.put(
        "intent",
        request == null || request.getIntent() == null ? "UNKNOWN" : request.getIntent().name());
    event.put("responseMode", state.responseMode);
    event.put("stages", state.stages);
    event.put("retrieval", state.retrieval);
    event.put("ai", state.ai);
    event.put(
        "recommendationCount",
        request == null || request.getRecommendations() == null
            ? 0
            : request.getRecommendations().size());
    log(event);
    CURRENT.remove();
  }

  private void log(Map<String, Object> event) {
    try {
      LOGGER.info("{}", objectMapper.writeValueAsString(event));
    } catch (JacksonException exception) {
      LOGGER.info("recommendation.completed requestId={}", event.get("requestId"));
    }
  }

  private static int value(Integer tokenCount) {
    return tokenCount == null ? 0 : tokenCount;
  }

  private static final class RequestState {
    private final Instant startedAt = Instant.now();
    private final Map<String, AiOperationObserver.AiUsage> operations;

    private RequestState(boolean demo) {
      operations = demo ? new LinkedHashMap<>() : null;
    }

    private final Map<String, Long> stages = new LinkedHashMap<>();
    private final Map<String, Object> retrieval = new LinkedHashMap<>();
    private final Map<String, Object> ai = new LinkedHashMap<>();
    private String responseMode = "UNKNOWN";
    private int aiCalls;
    private int aiAttempts;
    private int inputTokens;
    private int outputTokens;
    private boolean usageAvailable = true;

    private long durationMillis() {
      return Duration.between(startedAt, Instant.now()).toMillis();
    }
  }
}
