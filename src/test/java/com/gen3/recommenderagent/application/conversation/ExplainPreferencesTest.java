package com.gen3.recommenderagent.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.application.conversation.intent.ExplainPreferencesHandler;
import com.gen3.recommenderagent.application.conversation.intent.ExplainRecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatHandler;
import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
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
    search.setIntent(RecommendationHandler.ACTION);
    var book = new Recommendation("book", 1, 0.9, "A book", "Description");
    search.setRecommendations(List.of(book));
    session.getActiveSearch().setSearch(ConversationDraft.toTurn(search));
    session.getActiveSearch().setPendingRecommendations(List.of(book));
    session.setPendingQuestionKind(ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION);
    var pending = session.getPendingQuestion();
    var dispatcher = ConversationTestSupport.engine();
    var request = input(ExplainPreferencesHandler.ACTION);
    var result = dispatcher.begin(session, request);
    assertThat(dispatcher.needsRecommendations(result)).isFalse();
    dispatcher.finish(session, result, request);
    assertThat(session.getPendingQuestion()).isSameAs(pending);
    assertThat(session.getActiveSearch().getSearch().getRecommendations()).containsExactly(book);
    assertThat(session.getActiveSearch().getPendingRecommendations()).containsExactly(book);
    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("fantasy");
    var generator =
        new ConversationResponseGenerator(
            null, com.gen3.recommenderagent.testsupport.TestHandlers.registry());
    String report = generator.generate(List.of(), ConversationDraft.toTurn(result));
    assertThat(report).contains("fantasy", "I'll give you 3 recommendations at a time");
    var json = JsonMapper.builder().build();
    session = json.readValue(json.writeValueAsString(session), Session.class);
    session.setPreferences(null);
    session.setBookCount(1);
    var repeat = input(RepeatHandler.ACTION);
    var repeated = dispatcher.begin(session, repeat);
    dispatcher.finish(session, repeated, repeat);
    assertThat(generator.generate(List.of(), ConversationDraft.toTurn(repeated))).isEqualTo(report);
    var selection = input(UnknownHandler.ACTION);
    selection.setRawText("1");
    assertThat(dispatcher.begin(session, selection).getIntent())
        .isEqualTo(ExplainRecommendationHandler.ACTION);
  }

  private SessionRequest input(ConversationAction action) {
    var input = new SessionRequest();
    input.setIntent(action);
    input.setRawText(action.name());
    return input;
  }
}
