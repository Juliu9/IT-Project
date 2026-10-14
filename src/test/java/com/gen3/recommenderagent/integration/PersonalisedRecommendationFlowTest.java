package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.ActionRegistry;
import com.gen3.recommenderagent.application.RequestApplicationService;
import com.gen3.recommenderagent.application.action.ChangeCountAction;
import com.gen3.recommenderagent.application.action.ClearHistoryAction;
import com.gen3.recommenderagent.application.action.MoreResultsAction;
import com.gen3.recommenderagent.application.action.NoOpAction;
import com.gen3.recommenderagent.application.action.RecommendationAction;
import com.gen3.recommenderagent.application.action.RefineAction;
import com.gen3.recommenderagent.application.action.RequestContextMerger;
import com.gen3.recommenderagent.application.action.UpdatePreferencesAction;
import com.gen3.recommenderagent.application.sessionservice.DefaultSessionService;
import com.gen3.recommenderagent.candidateretriever.CandidateRetrievalExecutor;
import com.gen3.recommenderagent.candidateretriever.QdrantCandidateRetriever;
import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectorService;
import com.gen3.recommenderagent.candidateretriever.retrievalplan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.engine.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the application, session, embedding and Qdrant retrieval paths without live services.
 */
class PersonalisedRecommendationFlowTest {

  private final QdrantAudiobookRepository books = mock(QdrantAudiobookRepository.class);
  private final UserPreferenceVectorRepository users = mock(UserPreferenceVectorRepository.class);
  private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
  private final Ranker ranker = mock(Ranker.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final AiResponseGenerator responseGenerator = new AiResponseGenerator(null);
  private final RequestApplicationService application;

  PersonalisedRecommendationFlowTest() throws Exception {
    when(users.findEmbeddingByUserId("user")).thenReturn(Optional.empty());
    EmbeddingResponse response = mock(EmbeddingResponse.class);
    when(response.getResults()).thenReturn(List.of(new Embedding(new float[] {0, 2}, 0)));
    when(embeddingModel.embedForResponse(anyList())).thenReturn(response);
    when(books.searchSemantic(any(), any(), eq(50))).thenReturn(List.of());
    when(ranker.rank(anyList(), eq(5), any(RankingContext.class)))
        .thenReturn(List.of(new Recommendation("book", 1, 0.9, "A Book")));
    EmbeddingIndexer indexer = new EmbeddingIndexer(embeddingModel);
    RequestContextMerger merger = new RequestContextMerger();
    AudiobookRecommendationWorkflow workflow =
        new AudiobookRecommendationWorkflow(
            new QdrantCandidateRetriever(
                books, new CandidateRetrievalExecutor(indexer, new AudiobookRetrievalPlanner())),
            ranker,
            new SemanticQueryVectorService(indexer),
            users);
    ActionRegistry registry =
        new ActionRegistry(
            new RecommendationAction(workflow, merger),
            new RefineAction(workflow, merger),
            new MoreResultsAction(workflow, merger),
            new ChangeCountAction(),
            new UpdatePreferencesAction(merger),
            new ClearHistoryAction(),
            new NoOpAction());
    application =
        new RequestApplicationService(new DefaultSessionService(sessions), registry, merger);
  }

  @Test
  void knownUserRetrievesWithCombinedVectorsWithoutCallingEmbeddingModel() throws Exception {
    when(users.findEmbeddingByUserId("user"))
        .thenReturn(
            Optional.of(
                new UserPreferenceEmbedding("user", new float[] {1, 0}, new float[] {0, 1})));

    SessionRequest result = process(personalisedRequest());

    ArgumentCaptor<float[]> vector = ArgumentCaptor.forClass(float[].class);
    verify(books).searchSemantic(vector.capture(), eq(AudiobookFilters.empty()), eq(50));
    assertArrayEquals(new float[] {0.70710677f, 0.70710677f}, vector.getValue(), 0.0001f);
    assertThat(result.getRecommendations()).hasSize(1);
    assertThat(result.isPreferenceClarificationRequired()).isFalse();
    verifyNoInteractions(embeddingModel);
    verify(users).findEmbeddingByUserId("user");
  }

  @Test
  void knownUserWithOnlyOneVectorStillRetrievesAndKeepsFilters() throws Exception {
    when(users.findEmbeddingByUserId("user"))
        .thenReturn(
            Optional.of(new UserPreferenceEmbedding("user", new float[0], new float[] {1, 0})));
    SessionRequest request = personalisedRequest();
    MustInclude filter = new MustInclude();
    filter.setLanguage("English");
    request.setMustInclude(filter);

    process(request);

    ArgumentCaptor<float[]> vector = ArgumentCaptor.forClass(float[].class);
    ArgumentCaptor<AudiobookFilters> filters = ArgumentCaptor.forClass(AudiobookFilters.class);
    verify(books).searchSemantic(vector.capture(), filters.capture(), eq(50));
    assertArrayEquals(new float[] {1, 0}, vector.getValue());
    assertThat(filters.getValue().mustInclude().language()).isEqualTo("English");
    verifyNoInteractions(embeddingModel);
  }

  @Test
  void newUserEmbedsExistingAuthorPreferencesAndRetrievesSemantically() throws Exception {
    Session session = sessionWithPreferences();
    SemanticQuery positive = new SemanticQuery();
    positive.setAuthors(List.of("Ursula Le Guin"));
    session.getPreferences().setPositiveSemanticQuery(positive);
    sessions.updateSession(session);

    process(personalisedRequest());

    verify(embeddingModel).embedForResponse(List.of("Authors: Ursula Le Guin"));
    verify(books).searchSemantic(any(), eq(AudiobookFilters.empty()), eq(50));
  }

  @Test
  void emptyPreferencesAskAndPersistPendingStateThenAnswerAppendsAndRecommends() throws Exception {
    Session session = sessionWithPreferences();
    SemanticQuery blank = new SemanticQuery();
    blank.setGenres(List.of(" "));
    session.getPreferences().setPositiveSemanticQuery(blank);
    sessions.updateSession(session);
    SessionRequest first = process(personalisedRequest());

    assertThat(first.getRecommendations()).isEmpty();
    assertThat(responseGenerator.generate(first.getRecommendations(), first))
        .isEqualTo("What genres, authors, or narrators do you like?");
    verifyNoInteractions(books, embeddingModel, ranker);
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences()).isTrue();

    // Simulate a separate request reading the persisted session, as Redis does.
    JsonMapper mapper = JsonMapper.builder().build();
    Session restored = mapper.readValue(mapper.writeValueAsString(session), Session.class);
    sessions.updateSession(restored);
    SessionRequest answer = new SessionRequest();
    answer.setIntent(Intent.UPDATE_PREFERENCES);
    SemanticQuery taste = new SemanticQuery();
    taste.setGenres(List.of("fantasy"));
    taste.setAuthors(List.of("Ursula Le Guin"));
    answer.setPositiveSemanticQuery(taste);

    SessionRequest result = process(answer);

    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getRecommendations()).hasSize(1);
    assertThat(restored.isAwaitingPersonalisedPreferences()).isFalse();
    assertThat(restored.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("fantasy");
    assertThat(restored.getPreferences().getPositiveSemanticQuery().getAuthors())
        .containsExactly("Ursula Le Guin");
    verify(embeddingModel).embedForResponse(List.of("Genres: fantasy\nAuthors: Ursula Le Guin"));
    verify(books).searchSemantic(any(), eq(AudiobookFilters.empty()), eq(50));
  }

  @Test
  void answerPreservesEarlierPreferencesAndMoreResultsRetainsPersonalisation() throws Exception {
    Session session = sessionWithPreferences();
    SemanticQuery earlier = new SemanticQuery();
    earlier.setGenres(List.of("fantasy"));
    session.getPreferences().setPositiveSemanticQuery(earlier);
    SessionRequest pending = personalisedRequest();
    pending.setPreferenceClarificationRequired(true);
    session.addRequest(pending);
    sessions.updateSession(session);
    SessionRequest answer = new SessionRequest();
    answer.setIntent(Intent.UPDATE_PREFERENCES);
    SemanticQuery taste = new SemanticQuery();
    taste.setAuthors(List.of("Ursula Le Guin"));
    answer.setPositiveSemanticQuery(taste);
    process(answer);

    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("fantasy");
    assertThat(session.getPreferences().getPositiveSemanticQuery().getAuthors())
        .containsExactly("Ursula Le Guin");
    SessionRequest more = new SessionRequest();
    more.setIntent(Intent.MORE_RESULTS);
    assertThat(process(more).isPersonalised()).isTrue();
  }

  @Test
  void structuredPreferencesAreEmbeddedAndAppliedAsFilters() throws Exception {
    Session session = sessionWithPreferences();
    MustInclude include = new MustInclude();
    include.setAuthors(List.of("Ursula Le Guin"));
    include.setLanguage("English");
    session.getPreferences().setMustInclude(include);
    sessions.updateSession(session);

    process(personalisedRequest());

    verify(embeddingModel)
        .embedForResponse(List.of("Keywords: Author: Ursula Le Guin, Language: English"));
    ArgumentCaptor<AudiobookFilters> filters = ArgumentCaptor.forClass(AudiobookFilters.class);
    verify(books).searchSemantic(any(), filters.capture(), eq(50));
    assertThat(filters.getValue().mustInclude().authors()).containsExactly("Ursula Le Guin");
  }

  @Test
  void unavailableUserStoreFallsBackToSessionPreferences() throws Exception {
    when(users.findEmbeddingByUserId("user")).thenThrow(new IOException("unavailable"));
    Session session = sessionWithPreferences();
    SemanticQuery positive = new SemanticQuery();
    positive.setGenres(List.of("fantasy"));
    session.getPreferences().setPositiveSemanticQuery(positive);
    sessions.updateSession(session);

    assertThat(process(personalisedRequest()).getRecommendations()).hasSize(1);
    verify(embeddingModel).embedForResponse(List.of("Genres: fantasy"));
    verify(books).searchSemantic(any(), any(), eq(50));
  }

  @Test
  void unrelatedHelpKeepsPendingAndClearHistoryCancelsIt() {
    process(personalisedRequest());
    SessionRequest help = new SessionRequest();
    help.setIntent(Intent.HELP);
    assertThat(process(help).getIntent()).isEqualTo(Intent.HELP);
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences()).isTrue();
    SessionRequest clear = new SessionRequest();
    clear.setIntent(Intent.CLEAR_HISTORY);
    process(clear);
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences())
        .isFalse();
    verifyNoInteractions(books, embeddingModel);
  }

  private SessionRequest process(SessionRequest request) {
    return application.process("session", "user", request);
  }

  private SessionRequest personalisedRequest() {
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    request.setPersonalised(true);
    request.setRawText("Give me books I would like");
    return request;
  }

  private Session sessionWithPreferences() {
    Session session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    session.setPreferences(new SessionPreferences());
    return session;
  }
}
