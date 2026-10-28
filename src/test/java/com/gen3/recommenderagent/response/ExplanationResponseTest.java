package com.gen3.recommenderagent.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ExplanationResponseTest {
  @Test
  void explanationPromptUsesSavedCriteriaAndActualPreferenceEvidence() {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("An explanation.")))));
    org.mockito.Mockito.clearInvocations(client.prompt());
    var request = request();
    var books = List.of(book(true), book(false));
    var generator =
        new ConversationResponseGenerator(
            new AiResponseGenerator(client, AiOperationObserver.noop()));
    assertThat(generator.generate(books, ConversationDraft.toTurn(request)))
        .isEqualTo("An explanation.");
    var system = ArgumentCaptor.forClass(String.class);
    verify(client.prompt()).system(system.capture());
    assertThat(system.getValue())
        .contains(
            "Explain why",
            "150 words TOTAL",
            "complete conversational sentences",
            "Vary transitions",
            "Never renumber selected books from 1",
            "only when readingPreferencesUsed=true",
            "Never infer reasons from numerical scores");
    String prompt =
        new AiResponseGenerator(null).compilePrompt(books, ConversationDraft.toTurn(request), "");
    assertThat(prompt)
        .contains("fantasy", "readingPreferencesUsed=true", "readingPreferencesUsed=false");
  }

  @Test
  void aSingleSelectedBookKeepsItsDisplayedRankInThePromptAndFallback() {
    var selected = book(false);
    selected.setRank(2);
    var request = request();
    request.setReferenceRank(2);
    var snapshot = ConversationDraft.toTurn(request);
    var generator = new AiResponseGenerator(null);
    assertThat(generator.compilePrompt(List.of(selected), snapshot, ""))
        .contains("rank=2 title=\"Harry Potter\"")
        .doesNotContain("rank=1 ");
    assertThat(new ConversationResponseGenerator(generator).generate(List.of(selected), snapshot))
        .startsWith("Number 2, Harry Potter");
  }

  @Test
  void unavailableModelFallsBackWithoutInventingPreferencesOrMissingDescription() {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new org.springframework.web.client.ResourceAccessException("offline"));
    var known = book(true);
    var unknown = book(false);
    unknown.setShortDescription(null);
    var generator =
        new ConversationResponseGenerator(
            new AiResponseGenerator(client, AiOperationObserver.noop()));
    String response =
        generator.generate(List.of(known, unknown), ConversationDraft.toTurn(request()));
    assertThat(response)
        .contains(
            "I used your reading preferences to help choose this book",
            "school of magic",
            "enough detail in this book's description");
    assertThat(
            new ConversationResponseGenerator(new AiResponseGenerator(null))
                .generate(List.of(unknown), ConversationDraft.toTurn(request())))
        .doesNotContain("I used your reading preferences to help choose this book");
  }

  @Test
  void removesMarkdownAndParentheticalRankSyntaxFromModelExplanations() {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "4. **Harry Potter** (rank 4): A school of magic fits your fantasy request.")))));
    String result =
        new ConversationResponseGenerator(
                new AiResponseGenerator(client, AiOperationObserver.noop()))
            .generate(List.of(book(false)), ConversationDraft.toTurn(request()));
    assertThat(result).isEqualTo("4. Harry Potter A school of magic fits your fantasy request.");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {"RECOMMENDATION", "EXPLAIN_RECOMMENDATION"})
  void oversizedModelOutputUsesABoundedFallbackThatStillIncludesEveryBook(
      ConversationAction intent) {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("verbose ".repeat(200))))));
    var books =
        java.util.stream.IntStream.rangeClosed(1, 5)
            .mapToObj(
                rank -> {
                  var book =
                      new Recommendation(
                          "id-" + rank,
                          rank,
                          0.8,
                          "**Title " + rank + "**",
                          "description ".repeat(200));
                  book.setReadingPreferencesUsed(true);
                  return book;
                })
            .toList();
    var generator =
        new ConversationResponseGenerator(
            new AiResponseGenerator(client, AiOperationObserver.noop()));
    var request = request();
    request.setIntent(intent);
    String result = generator.generate(books, ConversationDraft.toTurn(request));
    assertThat(result.split("\\s+")).hasSizeLessThanOrEqualTo(150);
    assertThat(result).doesNotContain("*", "(", ")", "verbose");
    for (int rank = 1; rank <= 5; rank++) {
      assertThat(result).contains("Number " + rank + ", Title " + rank);
    }
    String offline =
        new ConversationResponseGenerator(new AiResponseGenerator(null))
            .generate(books, ConversationDraft.toTurn(request));
    assertThat(offline).isEqualTo(result);
    assertThat(offline).contains(". Number 2,", ". Number 5,").endsWith(".");
  }

  private ConversationDraft request() {
    var request = new ConversationDraft();
    request.setIntent(ConversationAction.EXPLAIN_RECOMMENDATION);
    request.setDecision(ConversationDecision.EXPLANATION);
    request.setRawText("Why this book?");
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    request.setPositiveSemanticQuery(query);
    return request;
  }

  private Recommendation book(boolean used) {
    var book =
        new Recommendation(
            "id", 4, 0.9, "Harry Potter", "A fantasy adventure at a school of magic.");
    book.setReadingPreferencesUsed(used);
    return book;
  }
}
