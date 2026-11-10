package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.conversation.intent.ExplainRecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatRecommendationsHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.recommendation.ReadHistoryExclusions;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.shared.readhistory.port.ReadHistoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReadHistoryFlowTest {
  @Test
  void repeatAndExplainKeepOriginalBooksButMoreChecksCurrentHistory() throws Exception {
    var history = mock(ReadHistoryRepository.class);
    when(history.findReadBookIds("user", 10001)).thenReturn(Set.of(), Set.of("book-1", "book-2"));
    var workflow = mock(RecommendationWorkflow.class);
    when(workflow.recommend(any(), any()))
        .thenReturn(
            java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new Recommendation("book-" + i, i, 1.0, "Book " + i))
                .toList());
    var sessions = new RecommendationFlowTestSupport.InMemorySessionRepository();
    var service =
        ConversationTestSupport.transitions(
            sessions, workflow, new ReadHistoryExclusions(Optional.of(history), 10000));
    var search = request(RecommendationHandler.ACTION);
    var tastes = new SemanticQuery();
    tastes.setGenres(List.of("mystery"));
    search.setPositiveSemanticQuery(tastes);
    assertThat(service.process("session", "user", search).getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-1");
    assertThat(
            service
                .process("session", "user", request(RepeatRecommendationsHandler.ACTION))
                .getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-1");
    var explanation = request(ExplainRecommendationHandler.ACTION);
    explanation.setReferenceRank(1);
    assertThat(service.process("session", "user", explanation).getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-1");
    assertThat(
            service
                .process("session", "user", request(MoreResultsHandler.ACTION))
                .getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-3");
    verify(history, times(2)).findReadBookIds("user", 10001);
    verify(workflow).recommend(any(), any());
  }

  private ConversationDraft request(ConversationAction action) {
    var request = new ConversationDraft();
    request.setIntent(action);
    request.setBookCount(1);
    return request;
  }
}
