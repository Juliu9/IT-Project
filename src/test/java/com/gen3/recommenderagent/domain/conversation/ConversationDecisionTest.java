package com.gen3.recommenderagent.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationDecisionTest {
  private final ConversationDispatcher policy = ConversationTestSupport.dispatcher();

  @Test
  void decisionPrecedenceIsClarificationThenActionThenResults() {
    var request = new ConversationDraft();
    request.setIntent(Intent.HELP);
    request.setSearchClarificationRequired(true);
    request.setPreferenceClarificationRequired(true);
    assertThat(ConversationRules.decisionFor(List.of(), request))
        .isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    request.setSearchClarificationRequired(false);
    assertThat(ConversationRules.decisionFor(List.of(), request))
        .isEqualTo(ConversationDecision.PREFERENCE_CLARIFICATION);
    request.setPreferenceClarificationRequired(false);
    request.setNoMoreRecommendations(true);
    assertThat(ConversationRules.decisionFor(List.of(), request))
        .isEqualTo(ConversationDecision.HELP);
    request.setIntent(Intent.REPEAT);
    assertThat(ConversationRules.decisionFor(List.of(), request))
        .isEqualTo(ConversationDecision.NO_MORE_RESULTS);
    request.setNoMoreRecommendations(false);
    assertThat(ConversationRules.decisionFor(List.of(), request))
        .isEqualTo(ConversationDecision.NOTHING_TO_REPEAT);
    request.setIntent(Intent.RECOMMENDATION);
    assertThat(ConversationRules.decisionFor(List.of(), request))
        .isEqualTo(ConversationDecision.NO_MATCHES);
    assertThat(
            ConversationRules.decisionFor(
                List.of(new Recommendation("id", 1, 1.0, "Book")), request))
        .isEqualTo(ConversationDecision.RECOMMENDATIONS);
  }

  @Test
  void decisionsAreNotPersistedAndOldSessionsStillResolveThem() throws Exception {
    var mapper = JsonMapper.builder().build();
    var session = new Session();
    var request = new ConversationDraft();
    request.setIntent(Intent.MORE_RESULTS);
    request.setRecommendations(List.of());
    request.setNoMoreRecommendations(true);
    request.setDecision(ConversationDecision.NO_MORE_RESULTS);
    session.getRequests().add(ConversationTurn.fromDraft(request));
    var before = mapper.writeValueAsString(session);
    assertThat(mapper.readTree(before).path("requests").get(0).has("decision")).isFalse();
    request.setDecision(null);
    assertThat(mapper.writeValueAsString(session)).isEqualTo(before);
    var restored = mapper.readValue(before, Session.class);
    var repeat = new ConversationDraft();
    repeat.setIntent(Intent.REPEAT);
    var effective = policy.begin(restored, repeat);
    policy.finish(restored, effective, repeat);
    assertThat(effective.getDecision()).isEqualTo(ConversationDecision.NO_MORE_RESULTS);
  }
}
