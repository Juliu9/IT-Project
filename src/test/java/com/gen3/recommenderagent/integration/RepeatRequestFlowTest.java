package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.conversation.intent.ChangeCountHandler;
import com.gen3.recommenderagent.application.conversation.intent.ClearHistoryHandler;
import com.gen3.recommenderagent.application.conversation.intent.ExplainRecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.HelpHandler;
import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatRecommendationsHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.application.session.SessionTransitionService;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import tools.jackson.databind.ObjectMapper;

class RepeatRequestFlowTest {
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(sessions, workflow);
  private final ConversationResponseGenerator responses =
      new ConversationResponseGenerator(
          new AiResponseGenerator(null),
          com.gen3.recommenderagent.testsupport.TestHandlers.registry());

  @Test
  void helpCanBeRepeatedBeforeAnyRecommendationSearch() {
    var help = process(request(HelpHandler.ACTION));
    var repeat = process(request(RepeatHandler.ACTION));
    assertThat(repeat.getOutcome()).isNull();
    assertThat(responses.generate(repeat.getRecommendations(), repeat))
        .isEqualTo(responses.generate(help.getRecommendations(), help));
    assertThat(saved().getLastOutcome().turn().getIntent()).isEqualTo(HelpHandler.ACTION);
    verifyNoInteractions(workflow);
  }

  @Test
  void repeatedExplanationKeepsItsSelectedRankAndTheMoreQueueAcrossSerialization()
      throws Exception {
    search();
    var explanation = request(ExplainRecommendationHandler.ACTION);
    explanation.setReferenceRank(4);
    explanation.setRawText("Why the fourth book?");
    var first = process(explanation);
    var mapper = new ObjectMapper();
    Session restored = mapper.readValue(mapper.writeValueAsString(saved()), Session.class);
    sessions.updateSession(restored);
    assertThat(restored.getLastOutcome().turn().getRawText()).isNull();
    for (int i = 0; i < 2; i++) {
      var repeated = process(request(RepeatHandler.ACTION));
      assertThat(repeated.getIntent()).isEqualTo(RepeatHandler.ACTION);
      assertThat(repeated.getOutcome()).isNull();
      assertThat(repeated.getRecommendations())
          .extracting(Recommendation::getRank)
          .containsExactly(4);
      assertThat(responses.generate(repeated.getRecommendations(), repeated))
          .isEqualTo(responses.generate(first.getRecommendations(), first));
      assertThat(saved().getLastOutcome().turn().getIntent())
          .isEqualTo(ExplainRecommendationHandler.ACTION);
      assertThat(saved().getActiveSearch().getPendingRecommendations()).hasSize(5);
    }
    var more = process(request(MoreResultsHandler.ACTION));
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @com.gen3.recommenderagent.testsupport.ActionSource(
      value = ConversationAction.class,
      names = {"HELP", "CHANGE_COUNT", "UPDATE_PREFERENCES", "CLEAR_HISTORY", "UNKNOWN"})
  void repeatsActionsWithoutApplyingThemAgain(ConversationAction intent) throws Exception {
    search();
    var action = request(intent);
    if (intent == ChangeCountHandler.ACTION) {
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
                    saved().getLastOutcome().outcome()));
    var repeated = process(request(RepeatHandler.ACTION));
    assertThat(repeated.getOutcome()).isEqualTo(first.getOutcome());
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
                        saved().getLastOutcome().outcome())))
        .isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void repeatsInvalidExplanationClarificationThenAllowsAnExplicitCorrection() {
    search();
    var invalid = request(ExplainRecommendationHandler.ACTION);
    invalid.setReferenceRank(6);
    var first = process(invalid);
    var repeated = process(request(RepeatHandler.ACTION));
    assertThat(repeated.getPendingQuestion())
        .isEqualTo(ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION);
    assertThat(responses.generate(repeated.getRecommendations(), repeated))
        .isEqualTo(responses.generate(first.getRecommendations(), first));
    var fourth = request(ExplainRecommendationHandler.ACTION);
    fourth.setReferenceRank(4);
    assertThat(process(fourth).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void repeatsSearchClarificationWithoutCreatingASearch() {
    var first = process(request(MoreResultsHandler.ACTION));
    var repeated = process(request(RepeatHandler.ACTION));
    assertThat(repeated.getPendingQuestion()).isEqualTo(RecommendationHandler.SEARCH_CLARIFICATION);
    assertThat(responses.generate(repeated.getRecommendations(), repeated))
        .isEqualTo(responses.generate(first.getRecommendations(), first));
    verifyNoInteractions(workflow);
  }

  @Test
  void repeatsWholeBookPageAfterSelectedExplanationAndThenGenericRepeatRepeatsThatPage() {
    search();
    var explain = request(ExplainRecommendationHandler.ACTION);
    explain.setReferenceRank(4);
    process(explain);
    assertThat(process(request(RepeatHandler.ACTION)).getRecommendations()).hasSize(1);
    var page = process(request(RepeatRecommendationsHandler.ACTION));
    assertThat(page.getOutcome()).isNull();
    assertThat(page.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(process(request(RepeatHandler.ACTION)).getRecommendations()).hasSize(5);
    assertThat(saved().getActiveSearch().getSearch()).isNotNull();
    assertThat(saved().getActiveSearch().getShownBookIds()).hasSize(5);
    assertThat(process(request(MoreResultsHandler.ACTION)).getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void distinguishesRepeatingHelpFromRepeatingTheBooks() {
    search();
    process(request(HelpHandler.ACTION));
    assertThat(process(request(RepeatHandler.ACTION)).getOutcome()).isNull();
    assertThat(process(request(RepeatRecommendationsHandler.ACTION)).getRecommendations())
        .hasSize(5);
    assertThat(process(request(RepeatHandler.ACTION)).getOutcome()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void noPreviousSearchReturnsFixedMessageAndGenericRepeatCanReplayIt() {
    process(request(HelpHandler.ACTION));
    var page = process(request(RepeatRecommendationsHandler.ACTION));
    assertThat(page.getOutcome())
        .isEqualTo(RepeatRecommendationsHandler.NO_RECOMMENDATIONS_TO_REPEAT);
    assertThat(responses.generate(page.getRecommendations(), page))
        .isEqualTo(
            "I don't have a list of recommendations to repeat right now. What kind of audiobook would you like?");
    assertThat(process(request(RepeatHandler.ACTION)).getOutcome()).isEqualTo(page.getOutcome());
    verifyNoInteractions(workflow);
  }

  @Test
  void emptyMoreRepeatsExhaustionInsteadOfAnOlderNonemptyPage() {
    search();
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(request(MoreResultsHandler.ACTION));
    process(request(MoreResultsHandler.ACTION));
    process(request(HelpHandler.ACTION));
    var page = process(request(RepeatRecommendationsHandler.ACTION));
    assertThat(page.getRecommendations()).isEmpty();
    assertThat(page.getOutcome()).isNull();
    assertThat(process(request(RepeatHandler.ACTION)).getOutcome()).isNull();
    verify(workflow, times(2)).recommend(any(), any());
  }

  @Test
  void clearingHistoryRemovesBookPageEvenThoughClearAcknowledgementCanBeRepeated() {
    search();
    process(request(ClearHistoryHandler.ACTION));
    assertThat(process(request(RepeatHandler.ACTION)).getOutcome()).isNull();
    assertThat(process(request(RepeatRecommendationsHandler.ACTION)).getOutcome())
        .isEqualTo(RepeatRecommendationsHandler.NO_RECOMMENDATIONS_TO_REPEAT);
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
    process(request(RecommendationHandler.ACTION));
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
