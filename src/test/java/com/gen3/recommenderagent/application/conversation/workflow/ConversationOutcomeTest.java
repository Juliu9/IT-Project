package com.gen3.recommenderagent.application.conversation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.session.LastOutcome;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationOutcomeTest {
  private final ConversationDispatcher policy = ConversationTestSupport.dispatcher();

  @Test
  void containsOnlyExceptionalResults() {
    assertThat(ConversationOutcome.values())
        .containsExactly(
            ConversationOutcome.UNAVAILABLE,
            ConversationOutcome.SEARCH_DECLINED,
            ConversationOutcome.RECENCY_EXHAUSTED,
            ConversationOutcome.NOTHING_TO_REPEAT,
            ConversationOutcome.NO_RECOMMENDATIONS_TO_REPEAT,
            ConversationOutcome.NOTHING_TO_EXPLAIN,
            ConversationOutcome.EMPTY_EXPLANATION_PAGE,
            ConversationOutcome.EMPTY_EXPLANATION_SELECTION);
  }

  @Test
  void outcomesAreNotDuplicatedInsideStoredTurns() throws Exception {
    var mapper = JsonMapper.builder().build();
    var session = new Session();
    var request = new ConversationDraft();
    request.setIntent(ConversationAction.MORE_RESULTS);
    request.setRecommendations(List.of());
    request.setNoMoreRecommendations(true);
    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));
    session.setLastOutcome(new LastOutcome(ConversationDraft.toTurn(request), null));
    var before = mapper.writeValueAsString(session);
    assertThat(mapper.readTree(before).path("activeSearch").path("search").has("outcome"))
        .isFalse();
    var restored = mapper.readValue(before, Session.class);
    var repeat = new ConversationDraft();
    repeat.setIntent(ConversationAction.REPEAT);
    var effective = policy.begin(restored, repeat);
    policy.finish(restored, effective, repeat);
    assertThat(effective.getOutcome()).isNull();
    assertThat(effective.getResponseAction()).isEqualTo(ConversationAction.MORE_RESULTS);
  }
}
