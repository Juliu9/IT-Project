package com.gen3.recommenderagent.domain.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.Intent;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationOutcomeTest {
  private final ConversationState policy = new ConversationState();

  @Test
  void outcomePrecedenceIsClarificationThenActionThenResults() {
    var request = new SessionRequest();
    request.setIntent(Intent.HELP);
    request.setSearchClarificationRequired(true);
    request.setPreferenceClarificationRequired(true);
    assertThat(ConversationState.outcomeFor(List.of(), request))
        .isEqualTo(ConversationOutcome.SEARCH_CLARIFICATION);
    request.setSearchClarificationRequired(false);
    assertThat(ConversationState.outcomeFor(List.of(), request))
        .isEqualTo(ConversationOutcome.PREFERENCE_CLARIFICATION);
    request.setPreferenceClarificationRequired(false);
    request.setNoMoreRecommendations(true);
    assertThat(ConversationState.outcomeFor(List.of(), request))
        .isEqualTo(ConversationOutcome.HELP);
    request.setIntent(Intent.REPEAT);
    assertThat(ConversationState.outcomeFor(List.of(), request))
        .isEqualTo(ConversationOutcome.NO_MORE_RESULTS);
    request.setNoMoreRecommendations(false);
    assertThat(ConversationState.outcomeFor(List.of(), request))
        .isEqualTo(ConversationOutcome.NOTHING_TO_REPEAT);
    request.setIntent(Intent.RECOMMENDATION);
    assertThat(ConversationState.outcomeFor(List.of(), request))
        .isEqualTo(ConversationOutcome.NO_MATCHES);
    assertThat(
            ConversationState.outcomeFor(
                List.of(new Recommendation("id", 1, 1.0, "Book")), request))
        .isEqualTo(ConversationOutcome.RECOMMENDATIONS);
  }

  @Test
  void outcomesAreNotPersistedAndOldSessionsStillResolveThem() throws Exception {
    var mapper = JsonMapper.builder().build();
    var session = new Session();
    var request = new SessionRequest();
    request.setIntent(Intent.MORE_RESULTS);
    request.setRecommendations(List.of());
    request.setNoMoreRecommendations(true);
    request.setOutcome(ConversationOutcome.NO_MORE_RESULTS);
    session.getRequests().add(request);
    var before = mapper.writeValueAsString(session);
    assertThat(mapper.readTree(before).path("requests").get(0).has("outcome")).isFalse();
    request.setOutcome(null);
    assertThat(mapper.writeValueAsString(session)).isEqualTo(before);
    var restored = mapper.readValue(before, Session.class);
    var repeat = new SessionRequest();
    repeat.setIntent(Intent.REPEAT);
    var effective = policy.begin(restored, repeat);
    policy.finish(restored, effective, repeat);
    assertThat(effective.getOutcome()).isEqualTo(ConversationOutcome.NO_MORE_RESULTS);
  }
}
