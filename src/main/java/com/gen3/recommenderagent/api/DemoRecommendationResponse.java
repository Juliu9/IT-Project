package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Browser-demo response with timing and available AI usage. */
public record DemoRecommendationResponse(
    String response, Metrics metrics, SessionRequest sessionRequest) {

  public record Metrics(
      long parseMs,
      long recommendMs,
      long responseMs,
      long totalMs,
      Integer inputTokens,
      Integer outputTokens) {}

  public static Metrics metrics(
      long parseMs, long recommendMs, long responseMs, long totalMs, ChatResponse parseResponse) {
    Usage usage =
        parseResponse == null || parseResponse.getMetadata() == null
            ? null
            : parseResponse.getMetadata().getUsage();
    return new Metrics(
        parseMs,
        recommendMs,
        responseMs,
        totalMs,
        usage == null ? null : usage.getPromptTokens(),
        usage == null ? null : usage.getCompletionTokens());
  }
}
