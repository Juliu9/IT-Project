package com.gen3.recommenderagent.ai;

/** A known failure at an AI provider boundary, independent of the provider SDK. */
public class AiProviderException extends RuntimeException {
  public AiProviderException(Throwable cause) {
    super("AI provider request failed", cause);
  }
}
