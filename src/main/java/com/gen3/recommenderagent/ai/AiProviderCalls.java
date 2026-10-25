package com.gen3.recommenderagent.ai;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.util.function.Supplier;
import org.springframework.web.client.RestClientException;

/** Translates supported provider failures; programming errors pass through unchanged. */
public final class AiProviderCalls {
  private AiProviderCalls() {}

  public static <T> T call(Supplier<T> request) {
    try {
      return request.get();
    } catch (RuntimeException exception) {
      for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof RestClientException
            || cause instanceof OpenAIIoException
            || cause instanceof OpenAIServiceException) {
          throw new AiProviderException(exception);
        }
      }
      throw exception;
    }
  }
}
