package com.gen3.recommenderagent.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ResponseGeneratorFailureTest {
  private final List<Recommendation> books =
      List.of(new Recommendation("book-1", 1, 0.9, "A Book"));
  private final SessionRequest request = new SessionRequest();

  @Test
  void replacementProviderFailureUsesTheExistingFallback() {
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new IllegalStateException("replacement provider unavailable"));
    assertThat(new AiResponseGenerator(client, AiOperationObserver.noop()).generate(books, request))
        .isEqualTo(new AiResponseGenerator(null).generate(books, request));
  }

  @ParameterizedTest
  @MethodSource("emptyResponses")
  void missingOrBlankModelOutputUsesTheExistingFallback(ChatResponse response) {
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(response);
    assertThat(new AiResponseGenerator(client, AiOperationObserver.noop()).generate(books, request))
        .isEqualTo(new AiResponseGenerator(null).generate(books, request));
  }

  @Test
  void errorsOutsideTheModelCallRemainVisible() {
    ChatClient client = mock(ChatClient.class);
    Recommendation broken = mock(Recommendation.class);
    when(broken.getTitle()).thenThrow(new IllegalArgumentException("invalid recommendation"));
    assertThatThrownBy(
            () ->
                new AiResponseGenerator(client, AiOperationObserver.noop())
                    .generate(List.of(broken), request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid recommendation");
    verifyNoInteractions(client);
  }

  static Stream<ChatResponse> emptyResponses() {
    return Stream.of(
        null,
        new ChatResponse(List.of()),
        mock(ChatResponse.class),
        new ChatResponse(List.of(new Generation(new AssistantMessage("")))),
        new ChatResponse(List.of(new Generation(new AssistantMessage("   ")))));
  }
}
