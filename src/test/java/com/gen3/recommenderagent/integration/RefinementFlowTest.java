package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class RefinementFlowTest {
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final SessionTransitionService service =
      ConversationTestSupport.transitions(sessions, workflow);

  private ConversationDraft request(Intent intent) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    return request;
  }

  private ConversationTurn process(ConversationDraft request) {
    return service.process("session", "user", request);
  }

  private void search(ExactCriteria include, ExactCriteria exclude) {
    when(workflow.recommend(any(), any()))
        .thenReturn(
            List.of(
                new Recommendation("one", 1, 1.0, "One"),
                new Recommendation("two", 2, 0.9, "Two")));
    var request = request(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    request.setPositiveSemanticQuery(query);
    request.setBookCount(1);
    request.setMustInclude(include);
    request.setMustNotInclude(exclude);
    process(request);
    clearInvocations(workflow);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacesOrAddsExactAuthorsAndNarrators(boolean add) {
    var previous = new ExactCriteria();
    previous.setAuthors(List.of("Author A"));
    previous.setNarrators(List.of("Narrator A"));
    previous.setLanguage("English");
    search(previous, null);
    var refinement = request(Intent.REFINE);
    var include = new ExactCriteria();
    include.setAuthors(List.of("Author B"));
    include.setNarrators(List.of("Narrator B"));
    refinement.setMustInclude(include);
    refinement.setAddAuthors(add);
    refinement.setAddNarrators(add);
    var result = process(refinement);
    assertThat(result.getMustInclude().getAuthors())
        .containsExactlyElementsOf(add ? List.of("Author A", "Author B") : List.of("Author B"));
    assertThat(result.getMustInclude().getNarrators())
        .containsExactlyElementsOf(
            add ? List.of("Narrator A", "Narrator B") : List.of("Narrator B"));
    assertThat(result.getMustInclude().getLanguage()).isEqualTo("English");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
  }

  @Test
  void explicitExclusionOverridesInheritedRequirement() {
    var include = new ExactCriteria();
    include.setLanguage("English");
    search(include, null);
    var refinement = request(Intent.REFINE);
    var exclude = new ExactCriteria();
    exclude.setLanguage("English");
    refinement.setMustNotInclude(exclude);
    var result = process(refinement);
    assertThat(result.getMustInclude().getLanguage()).isNull();
    assertThat(result.getMustNotInclude().getLanguage()).isEqualTo("English");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void explicitRequirementOverridesInheritedExclusionWithoutDroppingOtherAuthors() {
    var exclude = new ExactCriteria();
    exclude.setAuthors(List.of("Author A", "Author B"));
    search(null, exclude);
    var refinement = request(Intent.REFINE);
    var include = new ExactCriteria();
    include.setAuthors(List.of("Author A"));
    refinement.setMustInclude(include);
    var result = process(refinement);
    assertThat(result.getMustInclude().getAuthors()).containsExactly("Author A");
    assertThat(result.getMustNotInclude().getAuthors()).containsExactly("Author B");
  }

  @Test
  void conflictPreservesSearchQueueAndSessionVersionWithoutCallingWorkflow() throws Exception {
    search(null, null);
    var mapper = new ObjectMapper();
    String before = mapper.writeValueAsString(sessions.getSession("session", "user"));
    var refinement = request(Intent.REFINE);
    var include = new ExactCriteria();
    include.setLanguage("English");
    var exclude = new ExactCriteria();
    exclude.setLanguage("English");
    refinement.setMustInclude(include);
    refinement.setMustNotInclude(exclude);
    assertThatThrownBy(() -> process(refinement))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("Which requirement");
    assertThat(mapper.writeValueAsString(sessions.getSession("session", "user"))).isEqualTo(before);
    verifyNoInteractions(workflow);
  }

  @Test
  void partialAuthorExclusionAndSemanticMixturesRemainValid() {
    var request = request(Intent.RECOMMENDATION);
    var include = new ExactCriteria();
    include.setAuthors(List.of("A", "B"));
    var exclude = new ExactCriteria();
    exclude.setAuthors(List.of("A"));
    request.setMustInclude(include);
    request.setMustNotInclude(exclude);
    var query = new SemanticQuery();
    query.setGenres(List.of("romance", "horror"));
    request.setPositiveSemanticQuery(query);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    assertThat(process(request).getPositiveSemanticQuery().getGenres())
        .containsExactly("romance", "horror");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void freshVagueRefinementClarifiesEvenWithSavedPreferences() {
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var preferences = new SessionPreferences();
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    preferences.setPositiveSemanticQuery(query);
    session.setPreferences(preferences);
    sessions.updateSession(session);
    var request = request(Intent.REFINE);
    request.setRawText("make it shorter");
    assertThat(process(request).getDecision()).isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    verifyNoInteractions(workflow);
    assertThat(sessions.getSession("session", "user").getRequests()).isEmpty();
  }

  @Test
  void moreDoesNotReintroduceASavedPreferenceThatTheSearchCorrected() {
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var preferences = new SessionPreferences();
    var defaults = new ExactCriteria();
    defaults.setLanguage("English");
    preferences.setMustInclude(defaults);
    session.setPreferences(preferences);
    sessions.updateSession(session);
    search(null, null);
    var correction = request(Intent.REFINE);
    var exclude = new ExactCriteria();
    exclude.setLanguage("English");
    correction.setMustNotInclude(exclude);
    assertThat(process(correction).getMustInclude().getLanguage()).isNull();
    var more = process(request(Intent.MORE_RESULTS));
    assertThat(more.getMustInclude().getLanguage()).isNull();
    assertThat(more.getMustNotInclude().getLanguage()).isEqualTo("English");
  }

  @Test
  void standaloneRecommendationCanAnswerThePreferenceQuestion() {
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var pending = request(Intent.RECOMMENDATION);
    pending.setPersonalised(true);
    pending.setPreferenceClarificationRequired(true);
    session.setRequests(new java.util.ArrayList<>(List.of(ConversationTurn.fromDraft(pending))));
    sessions.updateSession(session);
    var reply = request(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    reply.setPositiveSemanticQuery(query);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    assertThat(process(reply).getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(
            sessions
                .getSession("session", "user")
                .getPreferences()
                .getPositiveSemanticQuery()
                .getGenres())
        .containsExactly("fantasy");
  }

  @Test
  void standaloneCriteriaRouteToRecommendationInFreshSession() {
    var request = request(Intent.REFINE);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    request.setPositiveSemanticQuery(query);
    var include = new ExactCriteria();
    include.setDuration("under 5 hours");
    request.setMustInclude(include);
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    var result = process(request);
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.isSearchClarificationRequired()).isFalse();
    verify(workflow).recommend(any(), any());
  }
}
