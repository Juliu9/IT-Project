package com.gen3.recommenderagent.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
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
    var generator = new AiResponseGenerator(client, AiOperationObserver.noop());
    assertThat(generator.generate(books, request)).isEqualTo("An explanation.");
    var system = ArgumentCaptor.forClass(String.class);
    verify(client.prompt()).system(system.capture());
    assertThat(system.getValue())
        .contains(
            "Explain why",
            "only when readingPreferencesUsed=true",
            "Never infer reasons from numerical scores");
    String prompt = generator.compilePrompt(books, request, "");
    assertThat(prompt)
        .contains("fantasy", "readingPreferencesUsed=true", "readingPreferencesUsed=false");
  }

  @Test
  void unavailableModelFallsBackWithoutInventingPreferencesOrMissingDescription() {
    var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
        .thenThrow(new IllegalStateException("offline"));
    var known = book(true);
    var unknown = book(false);
    unknown.setShortDescription(null);
    var generator = new AiResponseGenerator(client, AiOperationObserver.noop());
    String response = generator.generate(List.of(known, unknown), request());
    assertThat(response)
        .contains(
            "Your reading preferences were used",
            "school of magic",
            "don't have enough description information");
    assertThat(new AiResponseGenerator(null).generate(List.of(unknown), request()))
        .doesNotContain("Your reading preferences were used");
  }

  private SessionRequest request() {
    var request = new SessionRequest();
    request.setIntent(Intent.EXPLAIN_RECOMMENDATION);
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
