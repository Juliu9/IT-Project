package com.gen3.recommenderagent.observability;

import com.openai.errors.OpenAIException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;

/** Records the retries already performed by Spring AI without replacing its retry policy. */
@Component
public class AiOperationObserver {

  private static final Logger LOGGER = LoggerFactory.getLogger(AiOperationObserver.class);
  private static final ThreadLocal<Operation> CURRENT_OPERATION = new ThreadLocal<>();
  private final boolean enabled;

  @Autowired
  public AiOperationObserver(ObjectProvider<RetryTemplate> retryTemplateProvider) {
    this.enabled = true;
    RetryTemplate retryTemplate = retryTemplateProvider.getIfAvailable();
    if (retryTemplate != null) {
      RetryListener delegate = retryTemplate.getRetryListener();
      retryTemplate.setRetryListener(new LoggingRetryListener(delegate));
    }
  }

  private AiOperationObserver() {
    this.enabled = false;
  }

  public static AiOperationObserver noop() {
    return new AiOperationObserver();
  }

  public <T> T observe(String operation, Supplier<T> call, Function<T, AiUsage> usageExtractor) {
    if (!enabled) {
      return call.get();
    }
    Operation previous = CURRENT_OPERATION.get();
    Operation current = new Operation(operation, Instant.now());
    CURRENT_OPERATION.set(current);
    try {
      T response = call.get();
      logCompletion(current, usageExtractor.apply(response));
      return response;
    } catch (OpenAIException exception) {
      LOGGER.warn(
          "ai.operation.failed operation={} attempts={} durationMs={} lastAttemptDurationMs={}"
              + " exception={}",
          operation,
          current.attempts(),
          current.durationMillis(),
          current.attemptDurationMillis(),
          exception.getClass().getSimpleName());
      throw exception;
    } finally {
      if (previous == null) {
        CURRENT_OPERATION.remove();
      } else {
        CURRENT_OPERATION.set(previous);
      }
    }
  }

  private void logCompletion(Operation operation, AiUsage usage) {
    RequestTelemetry.ai(operation.name(), operation.attempts(), usage);
    LOGGER.info(
        "ai.operation.completed operation={} attempts={} durationMs={} lastAttemptDurationMs={}"
            + " model={} promptTokens={}"
            + " completionTokens={} totalTokens={} usageAvailable={}",
        operation.name(),
        operation.attempts(),
        operation.durationMillis(),
        operation.attemptDurationMillis(),
        usage.model(),
        usage.promptTokens(),
        usage.completionTokens(),
        usage.totalTokens(),
        usage.available());
  }

  private static final class LoggingRetryListener implements RetryListener {

    private final RetryListener delegate;

    private LoggingRetryListener(RetryListener delegate) {
      this.delegate = delegate;
    }

    @Override
    public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
      if (delegate != null) {
        delegate.beforeRetry(policy, retryable, state);
      }
      Operation operation = CURRENT_OPERATION.get();
      if (operation != null) {
        LOGGER.warn(
            "ai.operation.retry operation={} attempt={} durationMs={} usageAvailable=false",
            operation.name(),
            operation.attempts(),
            operation.attemptDurationMillis());
        operation.recordRetry();
      }
    }

    @Override
    public void onRetryPolicyExhaustion(
        RetryPolicy policy,
        Retryable<?> retryable,
        org.springframework.core.retry.RetryException exception) {
      if (delegate != null) {
        delegate.onRetryPolicyExhaustion(policy, retryable, exception);
      }
      Operation operation = CURRENT_OPERATION.get();
      if (operation != null) {
        LOGGER.warn(
            "ai.operation.retries_exhausted operation={} attempts={}",
            operation.name(),
            operation.attempts());
      }
    }
  }

  private static final class Operation {

    private final String name;
    private final Instant startedAt;
    private Instant attemptStartedAt;
    private int retries;

    private Operation(String name, Instant startedAt) {
      this.name = name;
      this.startedAt = startedAt;
      this.attemptStartedAt = startedAt;
    }

    private String name() {
      return name;
    }

    private void recordRetry() {
      retries++;
      attemptStartedAt = Instant.now();
    }

    private int attempts() {
      return retries + 1;
    }

    private long durationMillis() {
      return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private long attemptDurationMillis() {
      return Duration.between(attemptStartedAt, Instant.now()).toMillis();
    }
  }

  public record AiUsage(
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      boolean available) {

    public static AiUsage from(ChatResponse response) {
      if (response == null || response.getMetadata() == null) {
        return unavailable();
      }
      return from(response.getMetadata().getModel(), response.getMetadata().getUsage());
    }

    public static AiUsage from(EmbeddingResponse response) {
      if (response == null || response.getMetadata() == null) {
        return unavailable();
      }
      return from(response.getMetadata().getModel(), response.getMetadata().getUsage());
    }

    private static AiUsage from(String model, Usage usage) {
      if (usage == null) {
        return unavailable();
      }
      return new AiUsage(
          model,
          usage.getPromptTokens(),
          usage.getCompletionTokens(),
          usage.getTotalTokens(),
          true);
    }

    private static AiUsage unavailable() {
      return new AiUsage(null, null, null, null, false);
    }
  }
}
