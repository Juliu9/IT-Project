package com.gen3.recommenderagent.application.conversation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExplainPreferencesTest {
  @Test
  void reportPreservesSessionAndPendingExplanationAndCanBeRepeatedAfterReload() {
    var session = new Session();
    session.setBookCount(3);
    var preferences = new SessionPreferences();
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    preferences.setPositiveSemanticQuery(query);
    session.setPreferences(preferences);
    var search = new ConversationDraft();
    search.setIntent(ConversationAction.RECOMMENDATION);
    var book = new Recommendation("book", 1, 0.9, "A book", "Description");
    search.setRecommendations(List.of(book));
    session.getActiveSearch().setSearch(ConversationDraft.toTurn(search));
    session.getActiveSearch().setPendingRecommendations(List.of(book));
    session.setPendingQuestionKind(ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION);
    var pending = session.getPendingQuestion();
    var dispatcher = ConversationTestSupport.dispatcher();
    var request = input(ConversationAction.EXPLAIN_PREFERENCES);
    var result = dispatcher.begin(session, request);
    assertThat(dispatcher.needsRecommendations(result)).isFalse();
    dispatcher.finish(session, result, request);
    assertThat(session.getPendingQuestion()).isSameAs(pending);
    assertThat(session.getActiveSearch().getSearch().getRecommendations()).containsExactly(book);
    assertThat(session.getActiveSearch().getPendingRecommendations()).containsExactly(book);
    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("fantasy");
    var generator = new ConversationResponseGenerator(null);
    String report = generator.generate(List.of(), ConversationDraft.toTurn(result));
    assertThat(report).contains("fantasy", "Recommendation count: 3");
    var json = JsonMapper.builder().build();
    session = json.readValue(json.writeValueAsString(session), Session.class);
    session.setPreferences(null);
    session.setBookCount(1);
    var repeat = input(ConversationAction.REPEAT);
    var repeated = dispatcher.begin(session, repeat);
    dispatcher.finish(session, repeated, repeat);
    assertThat(generator.generate(List.of(), ConversationDraft.toTurn(repeated))).isEqualTo(report);
    var selection = input(ConversationAction.UNKNOWN);
    selection.setRawText("1");
    assertThat(dispatcher.begin(session, selection).getIntent())
        .isEqualTo(ConversationAction.EXPLAIN_RECOMMENDATION);
  }

  private SessionRequest input(ConversationAction action) {
    var input = new SessionRequest();
    input.setIntent(action);
    input.setRawText(action.name());
    return input;
  }
}
