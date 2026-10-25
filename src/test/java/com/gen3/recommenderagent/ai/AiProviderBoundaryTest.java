package com.gen3.recommenderagent.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.retry.RetryException;
import org.springframework.web.client.ResourceAccessException;

class AiProviderBoundaryTest {
  @Test
  void parserTranslatesKnownProviderFailures() {
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(client.prompt().system(anyString()).user(anyString()).call())
        .thenThrow(new ResourceAccessException("private connection detail"));
    assertThatThrownBy(
            () ->
                new InputParser(
                        new CommandParser(), new AiParser(client, AiOperationObserver.noop()))
                    .parse("fantasy"))
        .isInstanceOf(AiProviderException.class)
        .hasCauseInstanceOf(ResourceAccessException.class);
  }

  @Test
  void embeddingTranslatesProviderFailuresButPreservesProgrammingErrors() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    var service = new QueryEmbeddingService(model);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    var broken = new IllegalStateException("broken adapter");
    when(model.embedForResponse(any()))
        .thenThrow(new ResourceAccessException("unavailable"), broken);
    assertThatThrownBy(() -> service.embedSemanticQuery(query))
        .isInstanceOf(AiProviderException.class);
    assertThatThrownBy(() -> service.embedSemanticQuery(query)).isSameAs(broken);
  }

  @Test
  void wrappedRetryFailuresAreTranslated() {
    var failure =
        new RuntimeException(
            new RetryException("exhausted", new ResourceAccessException("unavailable")));
    assertThatThrownBy(
            () ->
                AiProviderCalls.call(
                    () -> {
                      throw failure;
                    }))
        .isInstanceOf(AiProviderException.class)
        .hasCause(failure);
  }

  @Test
  void retriesDoNotDisguiseProgrammingErrorsAsProviderFailures() {
    var failure =
        new RuntimeException(
            new RetryException("exhausted", new IllegalStateException("broken adapter")));
    assertThatThrownBy(
            () ->
                AiProviderCalls.call(
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }
}
