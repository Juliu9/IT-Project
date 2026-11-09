package com.gen3.recommenderagent.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.application.conversation.intent.CancelHandler;
import com.gen3.recommenderagent.application.conversation.intent.ExplainRecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RefineHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatRecommendationsHandler;
import com.gen3.recommenderagent.application.conversation.intent.UpdatePreferencesHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.domain.session.LastOutcome;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationOutcomeTest {
  private final ConversationEngine policy = ConversationTestSupport.engine();

  @Test
  void containsOnlyExceptionalResults() {
    assertThat(com.gen3.recommenderagent.testsupport.TestHandlers.registry().outcomes())
        .containsExactlyInAnyOrder(
            UpdatePreferencesHandler.PREFERENCE_LIMIT_EXCEEDED,
            CancelHandler.SEARCH_DECLINED,
            RefineHandler.RECENCY_EXHAUSTED,
            RepeatHandler.NOTHING_TO_REPEAT,
            RepeatRecommendationsHandler.NO_RECOMMENDATIONS_TO_REPEAT,
            ExplainRecommendationHandler.NOTHING_TO_EXPLAIN,
            ExplainRecommendationHandler.EMPTY_EXPLANATION_PAGE,
            ExplainRecommendationHandler.EMPTY_EXPLANATION_SELECTION);
  }

  @Test
  void outcomesAreNotDuplicatedInsideStoredTurns() throws Exception {
    var mapper = JsonMapper.builder().build();
    var session = new Session();
    var request = new ConversationDraft();
    request.setIntent(MoreResultsHandler.ACTION);
    request.setRecommendations(List.of());
    request.setNoMoreRecommendations(true);
    session.getActiveSearch().setSearch(ConversationDraft.toTurn(request));
    session.setLastOutcome(new LastOutcome(ConversationDraft.toTurn(request), null));
    var before = mapper.writeValueAsString(session);
    assertThat(mapper.readTree(before).path("activeSearch").path("search").has("outcome"))
        .isFalse();
    var restored = mapper.readValue(before, Session.class);
    var repeat = new ConversationDraft();
    repeat.setIntent(RepeatHandler.ACTION);
    var effective = policy.begin(restored, repeat);
    policy.finish(restored, effective, repeat);
    assertThat(effective.getOutcome()).isNull();
    assertThat(effective.getResponseAction()).isEqualTo(MoreResultsHandler.ACTION);
  }
}
