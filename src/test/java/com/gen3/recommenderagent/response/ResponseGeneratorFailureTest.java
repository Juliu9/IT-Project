package com.gen3.recommenderagent.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
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
  private final ConversationDraft request = new ConversationDraft();

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = com.gen3.recommenderagent.domain.conversation.ConversationAction.class,
      names = {"RECOMMENDATION", "EXPLAIN_RECOMMENDATION"})
  void incompleteNarrationFallsBackToEverySuppliedBook(
      com.gen3.recommenderagent.domain.conversation.ConversationAction action) {
    var page =
        java.util.stream.IntStream.rangeClosed(1, 5)
            .mapToObj(
                rank ->
                    new Recommendation(
                        "internal-" + rank,
                        rank,
                        0.9,
                        "Title " + rank,
                        "A story about friendship."))
            .toList();
    request.setIntent(action);
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("Here are five books: Title 1 and Title 2.")))));
    String response =
        new ConversationResponseGenerator(
                new AiResponseGenerator(client, AiOperationObserver.noop()))
            .generate(page, ConversationDraft.toTurn(request));
    for (int rank = 1; rank <= 5; rank++) {
      assertThat(response).contains("Number " + rank + ", Title " + rank);
    }
    assertThat(response).contains("friendship").doesNotContain("internal-");
    assertThat(ResponseBudget.wordCount(response)).isLessThanOrEqualTo(150);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "Try THE QUEEN’S NECKLACE and Cafe: A Story.",
        "Try The queen's necklace, followed by Café — A Story."
      })
  void completeNarrationAllowsCasePunctuationAndAccentChanges(String text) {
    var page =
        List.of(
            new Recommendation("one", 1, 0.9, "The queen's necklace"),
            new Recommendation("two", 2, 0.8, "Café $b A Story"));
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    assertThat(
            new ConversationResponseGenerator(
                    new AiResponseGenerator(client, AiOperationObserver.noop()))
                .generate(page, ConversationDraft.toTurn(request)))
        .isEqualTo(text);
  }

  @Test
  void titleInsideALongerWordDoesNotCountAsCoverage() {
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Try Dunes.")))));
    assertThat(
            new ConversationResponseGenerator(
                    new AiResponseGenerator(client, AiOperationObserver.noop()))
                .generate(
                    List.of(new Recommendation("one", 1, 0.9, "Dune")),
                    ConversationDraft.toTurn(request)))
        .contains("Number 1, Dune.")
        .doesNotContain("Try Dunes");
  }

  @Test
  void replacementProviderFailureUsesTheExistingFallback() {
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(
            new org.springframework.web.client.ResourceAccessException(
                "replacement provider unavailable"));
    assertThat(
            new ConversationResponseGenerator(
                    new AiResponseGenerator(client, AiOperationObserver.noop()))
                .generate(books, ConversationDraft.toTurn(request)))
        .isEqualTo(
            new ConversationResponseGenerator(new AiResponseGenerator(null))
                .generate(books, ConversationDraft.toTurn(request)));
  }

  @ParameterizedTest
  @MethodSource("emptyResponses")
  void missingOrBlankModelOutputUsesTheExistingFallback(ChatResponse response) {
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(response);
    assertThat(
            new ConversationResponseGenerator(
                    new AiResponseGenerator(client, AiOperationObserver.noop()))
                .generate(books, ConversationDraft.toTurn(request)))
        .isEqualTo(
            new ConversationResponseGenerator(new AiResponseGenerator(null))
                .generate(books, ConversationDraft.toTurn(request)));
  }

  @Test
  void errorsOutsideTheModelCallRemainVisible() {
    ChatClient client = mock(ChatClient.class);
    Recommendation broken = mock(Recommendation.class);
    when(broken.getTitle()).thenThrow(new IllegalArgumentException("invalid recommendation"));
    assertThatThrownBy(
            () ->
                new ConversationResponseGenerator(
                        new AiResponseGenerator(client, AiOperationObserver.noop()))
                    .generate(List.of(broken), ConversationDraft.toTurn(request)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid recommendation");
    verifyNoInteractions(client);
  }

  @Test
  void programmingErrorsInsideTheModelCallRemainVisible() {
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    var failure = new IllegalStateException("broken adapter");
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(failure);
    assertThatThrownBy(
            () ->
                new ConversationResponseGenerator(
                        new AiResponseGenerator(client, AiOperationObserver.noop()))
                    .generate(books, ConversationDraft.toTurn(request)))
        .isSameAs(failure);
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
