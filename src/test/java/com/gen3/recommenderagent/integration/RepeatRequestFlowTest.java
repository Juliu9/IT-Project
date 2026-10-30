package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class RepeatRequestFlowTest {
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(sessions, workflow);
  private final ConversationResponseGenerator responses =
      new ConversationResponseGenerator(new AiResponseGenerator(null));

  @Test
  void helpCanBeRepeatedBeforeAnyRecommendationSearch() {
    var help = process(request(ConversationAction.HELP));
    var repeat = process(request(ConversationAction.REPEAT));
    assertThat(repeat.getDecision()).isEqualTo(ConversationDecision.HELP);
    assertThat(responses.generate(repeat.getRecommendations(), repeat))
        .isEqualTo(responses.generate(help.getRecommendations(), help));
    assertThat(saved().getLastOutcome().turn().getIntent()).isEqualTo(ConversationAction.HELP);
    verifyNoInteractions(workflow);
  }

  @Test
  void repeatedExplanationKeepsItsSelectedRankAndTheMoreQueueAcrossSerialization()
      throws Exception {
    search();
    var explanation = request(ConversationAction.EXPLAIN_RECOMMENDATION);
    explanation.setReferenceRank(4);
    explanation.setRawText("Why the fourth book?");
    var first = process(explanation);
    var mapper = new ObjectMapper();
    Session restored = mapper.readValue(mapper.writeValueAsString(saved()), Session.class);
    sessions.updateSession(restored);
    assertThat(restored.getLastOutcome().turn().getRawText()).isNull();
    for (int i = 0; i < 2; i++) {
      var repeated = process(request(ConversationAction.REPEAT));
      assertThat(repeated.getIntent()).isEqualTo(ConversationAction.REPEAT);
      assertThat(repeated.getDecision()).isEqualTo(ConversationDecision.EXPLANATION);
      assertThat(repeated.getRecommendations())
          .extracting(Recommendation::getRank)
          .containsExactly(4);
      assertThat(responses.generate(repeated.getRecommendations(), repeated))
          .isEqualTo(responses.generate(first.getRecommendations(), first));
      assertThat(saved().getLastOutcome().turn().getIntent())
          .isEqualTo(ConversationAction.EXPLAIN_RECOMMENDATION);
      assertThat(saved().getActiveSearch().getPendingRecommendations()).hasSize(5);
    }
    var more = process(request(ConversationAction.MORE_RESULTS));
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = ConversationAction.class,
      names = {"HELP", "CHANGE_COUNT", "UPDATE_PREFERENCES", "CLEAR_HISTORY", "UNKNOWN"})
  void repeatsActionsWithoutApplyingThemAgain(ConversationAction intent) throws Exception {
    search();
    var action = request(intent);
    if (intent == ConversationAction.CHANGE_COUNT) {
      action.setBookCount(2);
    }
    var first = process(action);
    String before =
        new ObjectMapper()
            .writeValueAsString(
                java.util.Arrays.asList(
                    saved().getActiveSearch().getSearch(),
                    saved().getActiveSearch().getPendingRecommendations(),
                    saved().getActiveSearch().getShownBookIds(),
                    saved().getBookCount(),
                    saved().getLastOutcome().turn(),
                    saved().getLastOutcome().decision()));
    var repeated = process(request(ConversationAction.REPEAT));
    assertThat(repeated.getDecision()).isEqualTo(first.getDecision());
    assertThat(responses.generate(repeated.getRecommendations(), repeated))
        .isEqualTo(responses.generate(first.getRecommendations(), first));
    assertThat(
            new ObjectMapper()
                .writeValueAsString(
                    java.util.Arrays.asList(
                        saved().getActiveSearch().getSearch(),
                        saved().getActiveSearch().getPendingRecommendations(),
                        saved().getActiveSearch().getShownBookIds(),
                        saved().getBookCount(),
                        saved().getLastOutcome().turn(),
                        saved().getLastOutcome().decision())))
        .isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void repeatsInvalidExplanationClarificationThenAllowsAnExplicitCorrection() {
    search();
    var invalid = request(ConversationAction.EXPLAIN_RECOMMENDATION);
    invalid.setReferenceRank(6);
    var first = process(invalid);
    var repeated = process(request(ConversationAction.REPEAT));
    assertThat(repeated.getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    assertThat(responses.generate(repeated.getRecommendations(), repeated))
        .isEqualTo(responses.generate(first.getRecommendations(), first));
    var fourth = request(ConversationAction.EXPLAIN_RECOMMENDATION);
    fourth.setReferenceRank(4);
    assertThat(process(fourth).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void repeatsSearchClarificationWithoutCreatingASearch() {
    var first = process(request(ConversationAction.MORE_RESULTS));
    var repeated = process(request(ConversationAction.REPEAT));
    assertThat(repeated.getDecision()).isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    assertThat(responses.generate(repeated.getRecommendations(), repeated))
        .isEqualTo(responses.generate(first.getRecommendations(), first));
    verifyNoInteractions(workflow);
  }

  @Test
  void repeatsWholeBookPageAfterSelectedExplanationAndThenGenericRepeatRepeatsThatPage() {
    search();
    var explain = request(ConversationAction.EXPLAIN_RECOMMENDATION);
    explain.setReferenceRank(4);
    process(explain);
    assertThat(process(request(ConversationAction.REPEAT)).getRecommendations()).hasSize(1);
    var page = process(request(ConversationAction.REPEAT_RECOMMENDATIONS));
    assertThat(page.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(page.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(process(request(ConversationAction.REPEAT)).getRecommendations()).hasSize(5);
    assertThat(saved().getActiveSearch().getSearch()).isNotNull();
    assertThat(saved().getActiveSearch().getShownBookIds()).hasSize(5);
    assertThat(process(request(ConversationAction.MORE_RESULTS)).getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void distinguishesRepeatingHelpFromRepeatingTheBooks() {
    search();
    process(request(ConversationAction.HELP));
    assertThat(process(request(ConversationAction.REPEAT)).getDecision())
        .isEqualTo(ConversationDecision.HELP);
    assertThat(process(request(ConversationAction.REPEAT_RECOMMENDATIONS)).getRecommendations())
        .hasSize(5);
    assertThat(process(request(ConversationAction.REPEAT)).getDecision())
        .isEqualTo(ConversationDecision.RECOMMENDATIONS);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void noPreviousSearchReturnsFixedMessageAndGenericRepeatCanReplayIt() {
    process(request(ConversationAction.HELP));
    var page = process(request(ConversationAction.REPEAT_RECOMMENDATIONS));
    assertThat(page.getDecision()).isEqualTo(ConversationDecision.NO_RECOMMENDATIONS_TO_REPEAT);
    assertThat(responses.generate(page.getRecommendations(), page))
        .isEqualTo(
            "I don't have a list of recommendations to repeat right now. What kind of audiobook would you like?");
    assertThat(process(request(ConversationAction.REPEAT)).getDecision())
        .isEqualTo(page.getDecision());
    verifyNoInteractions(workflow);
  }

  @Test
  void emptyMoreRepeatsExhaustionInsteadOfAnOlderNonemptyPage() {
    search();
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(request(ConversationAction.MORE_RESULTS));
    process(request(ConversationAction.MORE_RESULTS));
    process(request(ConversationAction.HELP));
    var page = process(request(ConversationAction.REPEAT_RECOMMENDATIONS));
    assertThat(page.getRecommendations()).isEmpty();
    assertThat(page.getDecision()).isEqualTo(ConversationDecision.NO_MORE_RESULTS);
    assertThat(process(request(ConversationAction.REPEAT)).getDecision())
        .isEqualTo(ConversationDecision.NO_MORE_RESULTS);
    verify(workflow, times(2)).recommend(any(), any());
  }

  @Test
  void clearingHistoryRemovesBookPageEvenThoughClearAcknowledgementCanBeRepeated() {
    search();
    process(request(ConversationAction.CLEAR_HISTORY));
    assertThat(process(request(ConversationAction.REPEAT)).getDecision())
        .isEqualTo(ConversationDecision.HISTORY_CLEARED);
    assertThat(process(request(ConversationAction.REPEAT_RECOMMENDATIONS)).getDecision())
        .isEqualTo(ConversationDecision.NO_RECOMMENDATIONS_TO_REPEAT);
    assertThat(saved().getActiveSearch().getPendingRecommendations()).isEmpty();
    verify(workflow).recommend(any(), any());
  }

  private void search() {
    when(workflow.recommend(any(), any()))
        .thenReturn(
            IntStream.rangeClosed(1, 10)
                .mapToObj(
                    i ->
                        new Recommendation(
                            "book-" + i, i, 0.8, "Title " + i, "A magical adventure."))
                .toList());
    process(request(ConversationAction.RECOMMENDATION));
  }

  private ConversationDraft request(ConversationAction intent) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    return request;
  }

  private ConversationTurn process(ConversationDraft request) {
    return service.process("session", "user", request);
  }

  private Session saved() {
    return sessions.getSession("session", "user");
  }
}
