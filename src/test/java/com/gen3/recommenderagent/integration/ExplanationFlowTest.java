package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.RecommendationWorkflow;
import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ExplanationFlowTest {
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service = new SessionTransitionService(sessions, workflow);
  private final AiResponseGenerator responses = new AiResponseGenerator(null);

  @Test
  void explainsPageRankAndTitleWithoutChangingHistoryOrConsumingMore() throws Exception {
    search();
    String before = saved();
    var all = explain(null, null);
    assertThat(all.getRecommendations()).hasSize(5);
    assertThat(all.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(all.getRecommendations().get(3).isReadingPreferencesUsed()).isTrue();
    assertThat(explain(2, null).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(2);
    assertThat(explain(null, "HARRY POTTER: 4").getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    assertThat(explain(null, "potter 4").getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    assertThat(saved()).isEqualTo(before);
    var more = process(Intent.MORE_RESULTS);
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 6, 100})
  void invalidRanksReadOutThePageThenExplicitRetryExplainsFourth(int rank) throws Exception {
    search();
    String before = saved();
    var invalid = explain(rank, null);
    assertThat(invalid.getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    assertThat(responses.generate(invalid.getRecommendations(), invalid))
        .isEqualTo(
            "I returned 5 books: 1. Harry Potter 1. 2. Harry Potter 2. 3. Harry Potter 3. 4. Harry Potter 4. 5. Harry Potter 5. Which one did you mean?");
    assertThat(explain(4, null).getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
    assertThat(saved()).isEqualTo(before);
    assertThat(process(Intent.REPEAT).getRecommendations()).hasSize(5);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void ambiguousMissingAndConflictingTitlesClarifyWithoutSelectingSilently() {
    search();
    for (SessionRequest result :
        List.of(
            explain(null, "Harry Potter"),
            explain(null, "unknown title"),
            explain(null, "!!!"),
            explain(2, "Harry Potter 4"))) {
      assertThat(result.getDecision())
          .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
      assertThat(result.getRecommendations()).hasSize(5);
    }
    assertThat(explain(4, "Harry Potter 4").getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void noSearchAndClearedHistoryReturnFixedMessagesWithoutSearching() {
    var none = explain(null, null);
    assertThat(responses.generate(none.getRecommendations(), none))
        .isEqualTo("I haven't recommended any books yet. Ask me for recommendations first.");
    verifyNoInteractions(workflow);
    search();
    process(Intent.CLEAR_HISTORY);
    assertThat(explain(1, null).getDecision()).isEqualTo(ConversationDecision.NOTHING_TO_EXPLAIN);
    verify(workflow).recommend(any(), any());
  }

  @Test
  void emptyMoreDoesNotExplainAnOlderNonemptyPage() {
    search();
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(Intent.MORE_RESULTS);
    process(Intent.MORE_RESULTS);
    var result = explain(null, null);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EMPTY_EXPLANATION_PAGE);
    assertThat(responses.generate(result.getRecommendations(), result))
        .isEqualTo("The latest request returned no recommendations to explain.");
    assertThat(process(Intent.REPEAT).getRecommendations()).isEmpty();
    verify(workflow, times(2)).recommend(any(), any());
  }

  @Test
  void explanationPreservesPendingPreferenceQuestion() {
    search();
    var pending = new SessionRequest();
    pending.setIntent(Intent.RECOMMENDATION);
    pending.setPreferenceClarificationRequired(true);
    sessions.getSession("session", "user").getRequests().add(pending);
    var explain = explain(4, null);
    assertThat(explain.getDecision()).isEqualTo(ConversationDecision.EMPTY_EXPLANATION_PAGE);
    assertThat(
            sessions
                .getSession("session", "user")
                .getRequests()
                .getLast()
                .isPreferenceClarificationRequired())
        .isTrue();
  }

  @Test
  void invalidRankListsOnlyTheActualShortPage() {
    search();
    var session = sessions.getSession("session", "user");
    session.setPendingRecommendations(session.getPendingRecommendations().subList(0, 2));
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    process(Intent.MORE_RESULTS);
    var result = explain(6, null);
    assertThat(responses.generate(result.getRecommendations(), result))
        .isEqualTo(
            "I returned 2 books: 1. Harry Potter 6. 2. Harry Potter 7. Which one did you mean?");
  }

  @Test
  void apiClarifiesSixthThenExplainsFourthWithoutReplacingThePage() throws Exception {
    search();
    String before = saved();
    com.gen3.recommenderagent.inputparser.InputParser parser =
        raw -> {
          var request = new SessionRequest();
          request.setIntent(Intent.EXPLAIN_RECOMMENDATION);
          request.setReferenceRank(raw.contains("sixth") ? 6 : 4);
          request.setRawText(raw);
          return request;
        };
    var gateway = new com.gen3.recommenderagent.api.RequestGateway(parser, service, responses);
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(gateway).build();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .contentType("text/plain")
                .content("Why the sixth book?"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Which one did you mean?")));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/recommendations")
                .header("X-Session-Id", "session")
                .header("X-User-Id", "user")
                .contentType("text/plain")
                .content("Why the fourth book?"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Number 4, Harry Potter 4")));
    assertThat(saved()).isEqualTo(before);
    verify(workflow).recommend(any(), any());
  }

  private void search() {
    when(workflow.recommend(any(), any()))
        .thenReturn(
            IntStream.rangeClosed(1, 10)
                .mapToObj(
                    i -> {
                      var book =
                          new Recommendation(
                              "book-" + i,
                              i,
                              0.8,
                              "Harry Potter " + i,
                              "A fantasy adventure at a school of magic.");
                      book.setReadingPreferencesUsed(i == 4);
                      return book;
                    })
                .toList());
    var search = new SessionRequest();
    search.setIntent(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    search.setPositiveSemanticQuery(query);
    service.process("session", "user", search);
  }

  private SessionRequest explain(Integer rank, String title) {
    var request = new SessionRequest();
    request.setIntent(Intent.EXPLAIN_RECOMMENDATION);
    request.setReferenceRank(rank);
    request.setReferenceTitle(title);
    return service.process("session", "user", request);
  }

  private SessionRequest process(Intent intent) {
    var request = new SessionRequest();
    request.setIntent(intent);
    return service.process("session", "user", request);
  }

  private String saved() throws Exception {
    var session = sessions.getSession("session", "user");
    return new ObjectMapper()
        .writeValueAsString(
            List.of(
                session.getRequests(),
                session.getPendingRecommendations(),
                session.getShownBooks()));
  }
}
