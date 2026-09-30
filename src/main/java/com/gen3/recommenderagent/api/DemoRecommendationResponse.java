package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** JSON response consumed by the bundled browser demo. */
public record DemoRecommendationResponse(
    String response, Metrics metrics, SessionRequest sessionRequest) {

  public record Metrics(
      StageMetric inputParser,
      StageMetric recommendationEngine,
      StageMetric responseGenerator,
      long totalDurationMs,
      Integer totalTokens,
      Double totalEstimatedCostUsd) {}

  public record StageMetric(long durationMs, UsageMetric usage) {}

  public record UsageMetric(
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Double estimatedCostUsd) {

    public static UsageMetric from(ChatResponse response) {
      if (response == null || response.getMetadata() == null) {
        return null;
      }

      Usage usage = response.getMetadata().getUsage();
      if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() == 0) {
        return null;
      }

      return new UsageMetric(
          usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(), null);
    }
  }
}
