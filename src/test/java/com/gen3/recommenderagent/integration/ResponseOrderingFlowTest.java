package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.api.RequestGateway;
import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.storage.session.SessionBusyException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ResponseOrderingFlowTest {
  @Test
  void anotherPageCannotCommitWhileThePreviousResponseIsGenerating() throws Exception {
    var sessions = new RecommendationFlowTestSupport.InMemorySessionRepository();
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var search = new ConversationDraft();
    search.setIntent(ConversationAction.RECOMMENDATION);
    search.setRecommendations(List.of());
    session.getActiveSearch().setSearch(ConversationDraft.toTurn(search));
    session
        .getActiveSearch()
        .setPendingRecommendations(
            IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new Recommendation("book-" + i, i, 1.0, "Book " + i))
                .toList());
    sessions.updateSession(session);
    var service =
        ConversationTestSupport.transitions(
            sessions,
            (request, state) -> {
              throw new AssertionError("Page should be cached");
            });
    var generating = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var parser = org.mockito.Mockito.mock(com.gen3.recommenderagent.inputparser.InputParser.class);
    org.mockito.Mockito.when(
            parser.parseUserIntent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers
                    .<java.util.function.Supplier<
                            com.gen3.recommenderagent.inputparser.SessionContext>>
                        any()))
        .thenAnswer(
            invocation -> {
              String raw = invocation.getArgument(0);
              var intent = new com.gen3.recommenderagent.inputparser.UserIntent();
              intent.setIntent(ConversationAction.MORE_RESULTS);
              return intent;
            });
    var gateway =
        new RequestGateway(
            parser,
            service,
            (books, turn) -> {
              if (turn.getRawText().equals("first")) {
                generating.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
              }
              return books.getFirst().getId();
            });
    var worker = Executors.newSingleThreadExecutor();
    try {
      var first = worker.submit(() -> gateway.handleRequest("session", "user", "first"));
      assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(
              sessions
                  .getSession("session", "user")
                  .getLastOutcome()
                  .turn()
                  .getRecommendations()
                  .getFirst()
                  .getId())
          .isEqualTo("book-1");
      assertThatThrownBy(() -> gateway.handleRequest("session", "user", "second"))
          .isInstanceOf(SessionBusyException.class);
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("book-1");
      assertThat(gateway.handleRequest("session", "user", "second")).isEqualTo("book-6");
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }
}
