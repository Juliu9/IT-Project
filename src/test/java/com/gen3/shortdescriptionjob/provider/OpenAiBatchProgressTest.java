package com.gen3.shortdescriptionjob.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiBatchProgressTest {
  @Test
  void pollsUntilCompletedAndLogsCounts(CapturedOutput output) throws Exception {
    var client = mock(HttpClient.class);
    var pending =
        reply(
            "{\"status\":\"in_progress\",\"request_counts\":{\"completed\":2,\"failed\":1,\"total\":5}}");
    var completed = reply("{\"status\":\"completed\",\"output_file_id\":\"file-1\"}");
    var results =
        reply(
            "{\"response\":{\"status_code\":200,\"body\":{\"choices\":[{\"message\":{\"content\":\"{\\\"book-1\\\":\\\"Summary\\\"}\"}}]}}}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(pending, completed, results);
    var provider =
        new OpenAiShortDescriptionBatchProvider(
            new ObjectMapper(),
            client,
            "key",
            "https://example.test",
            "model",
            Duration.ofMinutes(15),
            Duration.ofMillis(1));
    assertThat(provider.completedDescriptions("batch-1")).containsEntry("book-1", "Summary");
    assertThat(output.getOut()).contains("status=in_progress completed=2 failed=1 total=5");
    var requests = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(requests.getAllValues())
        .allSatisfy(request -> assertThat(request.timeout()).contains(Duration.ofMinutes(15)));
  }

  @Test
  void propagatesTimeoutWithoutBlindlyRetryingSubmission() throws Exception {
    var client = mock(HttpClient.class);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new java.net.http.HttpTimeoutException("stalled"));
    var provider =
        new OpenAiShortDescriptionBatchProvider(
            new ObjectMapper(), client, "key", "https://example.test", "model");
    assertThatThrownBy(() -> provider.submit("input-1"))
        .isInstanceOf(java.net.http.HttpTimeoutException.class);
    verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"failed", "expired", "cancelled"})
  void recoveryAcceptsOnlyTerminalFailures(String status) throws Exception {
    var client = mock(HttpClient.class);
    var response = reply("{\"status\":\"" + status + "\"}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    new OpenAiShortDescriptionBatchProvider(
            new ObjectMapper(), client, "key", "https://example.test", "model")
        .verifyTerminalFailure("batch-1");
    verify(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "in_progress",
        "validating",
        "finalizing",
        "cancelling",
        "completed",
        "unknown",
        ""
      })
  void recoveryRefusesActiveCompletedOrUnknownBatches(String status) throws Exception {
    var client = mock(HttpClient.class);
    var response = reply("{\"status\":\"" + status + "\"}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    var provider =
        new OpenAiShortDescriptionBatchProvider(
            new ObjectMapper(), client, "key", "https://example.test", "model");
    assertThatThrownBy(() -> provider.verifyTerminalFailure("batch-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("recovery requires");
  }

  private HttpResponse<String> reply(String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(body);
    return response;
  }
}
