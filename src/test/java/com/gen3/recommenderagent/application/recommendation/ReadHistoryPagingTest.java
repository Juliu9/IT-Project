package com.gen3.recommenderagent.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.shared.readhistory.port.ReadHistoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReadHistoryPagingTest {
  @Test
  void filtersCacheAndRefillWithOneHistoryRead() throws Exception {
    var repository = mock(ReadHistoryRepository.class);
    when(repository.findReadBookIds("user", 10001)).thenReturn(Set.of("read", "also-read"));
    var workflow = mock(RecommendationWorkflow.class);
    var session = new Session();
    session.setUserId("user");
    session.getActiveSearch().setPendingRecommendations(List.of(book("read"), book("cached")));
    var request = new ConversationDraft();
    request.setBookCount(2);
    when(workflow.recommend(request, session))
        .thenAnswer(
            call -> {
              assertThat(request.getReadBookIds()).containsExactlyInAnyOrder("read", "also-read");
              return List.of(book("also-read"), book("new"));
            });
    var pager =
        new RecommendationPager(
            workflow, new ReadHistoryExclusions(Optional.of(repository), 10000));
    assertThat(pager.page(request, session))
        .extracting(Recommendation::getId)
        .containsExactly("cached", "new");
    verify(repository).findReadBookIds("user", 10001);
    assertThat(ConversationDraft.toStoredTurn(request).getSearchContext()).isNotNull();
    assertThat(
            new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(ConversationDraft.toStoredTurn(request)))
        .doesNotContain("readBookIds", "also-read");
  }

  @Test
  void historyChangesAreAppliedToTheNextCachedPage() throws Exception {
    var repository = mock(ReadHistoryRepository.class);
    when(repository.findReadBookIds("user", 11)).thenReturn(Set.of(), Set.of("next"));
    var workflow = mock(RecommendationWorkflow.class);
    var session = new Session();
    session.setUserId("user");
    session
        .getActiveSearch()
        .setPendingRecommendations(List.of(book("first"), book("next"), book("last")));
    var pager =
        new RecommendationPager(workflow, new ReadHistoryExclusions(Optional.of(repository), 10));
    var request = new ConversationDraft();
    request.setBookCount(1);
    assertThat(pager.page(request, session))
        .extracting(Recommendation::getId)
        .containsExactly("first");
    assertThat(pager.page(newRequest(), session))
        .extracting(Recommendation::getId)
        .containsExactly("last");
    verifyNoInteractions(workflow);
    verify(repository, times(2)).findReadBookIds("user", 11);
  }

  @Test
  void exhaustedUnreadResultsDoNotRelaxExclusions() throws Exception {
    var repository = mock(ReadHistoryRepository.class);
    when(repository.findReadBookIds("user", 11)).thenReturn(Set.of("read"));
    var workflow = mock(RecommendationWorkflow.class);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    var session = new Session();
    session.setUserId("user");
    session.getActiveSearch().setPendingRecommendations(List.of(book("read")));
    assertThat(
            new RecommendationPager(
                    workflow, new ReadHistoryExclusions(Optional.of(repository), 10))
                .page(newRequest(), session))
        .isEmpty();
  }

  private ConversationDraft newRequest() {
    var request = new ConversationDraft();
    request.setBookCount(1);
    return request;
  }

  private Recommendation book(String id) {
    return new Recommendation(id, 1, 1.0, id);
  }
}
