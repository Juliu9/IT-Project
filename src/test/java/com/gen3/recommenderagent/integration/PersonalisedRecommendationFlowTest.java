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

import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.catalogue.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.AudiobookRecommendationWorkflow;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.embedding.QueryEmbeddingService;
import com.gen3.recommenderagent.embedding.SemanticQueryVectorService;
import com.gen3.recommenderagent.ranker.Ranker;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalService;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
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
  private final ConversationResponseGenerator responseGenerator =
      new ConversationResponseGenerator(new AiResponseGenerator(null));
  private final SessionTransitionService application;

  PersonalisedRecommendationFlowTest() throws Exception {
    when(users.findEmbeddingByUserId("user")).thenReturn(Optional.empty());
    EmbeddingResponse response = mock(EmbeddingResponse.class);
    when(response.getResults()).thenReturn(List.of(new Embedding(new float[] {0, 2}, 0)));
    when(embeddingModel.embedForResponse(anyList())).thenReturn(response);
    when(books.searchSemantic(any(), any(), eq(50))).thenReturn(List.of());
    when(ranker.rank(anyList(), eq(20), any(RankingContext.class)))
        .thenReturn(List.of(new Recommendation("book", 1, 0.9, "A Book")));
    QueryEmbeddingService indexer = new QueryEmbeddingService(embeddingModel);
    AudiobookRecommendationWorkflow workflow =
        new AudiobookRecommendationWorkflow(
            new CandidateRetrievalService(
                "qdrant", books, indexer, new AudiobookRetrievalPlanner()),
            ranker,
            new SemanticQueryVectorService(indexer),
            users);
    application = ConversationTestSupport.transitions(sessions, workflow);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"Recommend an audiobook", "Recommend an audiobook for me", "give me some books"})
  void genericRequestForNewUserAsksForTasteWithoutSearching(String text) {
    var request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText(text);
    var result = process(request);
    assertThat(result.isPersonalised()).isTrue();
    assertThat(result.isPreferenceClarificationRequired()).isTrue();
    assertThat(result.getRecommendations()).isEmpty();
    assertThat(responseGenerator.generate(result.getRecommendations(), result))
        .contains("What genres");
    verifyNoInteractions(books, embeddingModel, ranker);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"none", "constraints", "semantic", "both"})
  void firstPersonalisedRequestNeedsSavedTasteEvenWhenCurrentRequestHasCriteria(String criteria)
      throws Exception {
    var request = personalisedRequest();
    request.setExplicitSearch(true);
    if (criteria.equals("constraints") || criteria.equals("both")) {
      var include = new ExactCriteria();
      include.setAuthors(List.of("Suzanne Collins"));
      request.setMustInclude(include);
    }
    if (criteria.equals("semantic") || criteria.equals("both")) {
      var query = new SemanticQuery();
      query.setGenres(List.of("fantasy"));
      request.setPositiveSemanticQuery(query);
    }
    var result = process(request);
    assertThat(result.isPreferenceClarificationRequired()).isTrue();
    assertThat(result.getRecommendations()).isEmpty();
    assertThat(responseGenerator.generate(result.getRecommendations(), result))
        .isEqualTo("What genres do you like?");
    verifyNoInteractions(books, embeddingModel, ranker);
    var answer = new ConversationDraft();
    answer.setIntent(Intent.REFINE);
    var taste = new SemanticQuery();
    taste.setGenres(List.of("adventure"));
    answer.setPositiveSemanticQuery(taste);
    var resumed = process(answer);
    assertThat(resumed.isPreferenceClarificationRequired()).isFalse();
    assertThat(resumed.getRecommendations()).hasSize(1);
    if (criteria.equals("constraints") || criteria.equals("both")) {
      assertThat(resumed.getMustInclude().getAuthors()).containsExactly("Suzanne Collins");
    }
    if (criteria.equals("semantic") || criteria.equals("both")) {
      assertThat(resumed.getPositiveSemanticQuery().getGenres()).contains("fantasy", "adventure");
    }
    assertThat(sessions.getSession("session", "user").getPreferences()).isNotNull();
  }

  @Test
  void horrorAnswerIsSavedAndReusedAfterSessionReload() {
    assertThat(process(personalisedRequest()).isPreferenceClarificationRequired()).isTrue();
    var ai = mock(com.gen3.recommenderagent.inputparser.AiParser.class);
    var parsed = new com.gen3.recommenderagent.inputparser.ParsedRequest();
    parsed.setIntent(Intent.RECOMMENDATION);
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse("horror")).thenReturn(parsed);
    var parser =
        new com.gen3.recommenderagent.inputparser.InputParser(
            new com.gen3.recommenderagent.inputparser.CommandParser(), ai);
    var result = application.process("session", "user", parser.parse("horror"));
    assertThat(result.getRecommendations()).hasSize(1);
    assertThat(result.isPreferenceClarificationRequired()).isFalse();
    var mapper = JsonMapper.builder().build();
    var restored =
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class);
    assertThat(restored.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("horror");
    sessions.updateSession(restored);
    assertThat(process(personalisedRequest()).isPreferenceClarificationRequired()).isFalse();
    assertThat(
            sessions
                .getSession("session", "user")
                .getPreferences()
                .getPositiveSemanticQuery()
                .getGenres())
        .containsExactly("horror");
  }

  @Test
  void genericRequestForKnownUserUsesStoredTaste() throws Exception {
    when(users.findEmbeddingByUserId("user"))
        .thenReturn(
            Optional.of(new UserPreferenceEmbedding("user", new float[] {1, 0}, new float[0])));
    var request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("Recommend an audiobook");
    var result = process(request);
    assertThat(result.isPersonalised()).isTrue();
    assertThat(result.getRecommendations()).hasSize(1);
    verify(books).searchSemantic(any(), eq(AudiobookFilters.empty()), eq(50));
    verifyNoInteractions(embeddingModel);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"CANCEL", "RESET_PREFERENCES", "no", "not now", "keep my preferences"})
  void abandoningANewTasteQuestionKeepsThePreviousSearchAndCachedBatch(String action) {
    var search = new ConversationDraft();
    search.setIntent(Intent.RECOMMENDATION);
    var genre = new SemanticQuery();
    genre.setGenres(List.of("fantasy"));
    search.setPositiveSemanticQuery(genre);
    process(search);
    var session = sessions.getSession("session", "user");
    session.setPendingRecommendations(
        List.of(new Recommendation("remaining", 2, 0.8, "Remaining fantasy")));
    sessions.updateSession(session);
    var personalised = personalisedRequest();
    personalised.setExplicitSearch(true);
    assertThat(process(personalised).isPreferenceClarificationRequired()).isTrue();
    var cancel = new ConversationDraft();
    cancel.setIntent(
        action.equals("CANCEL") || action.equals("RESET_PREFERENCES")
            ? Intent.valueOf(action)
            : Intent.UNKNOWN);
    cancel.setRawText(action);
    process(cancel);
    org.mockito.Mockito.clearInvocations(books, embeddingModel, ranker);
    var more = new ConversationDraft();
    more.setIntent(Intent.MORE_RESULTS);
    more.setBookCount(1);
    var result = process(more);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("remaining");
    verifyNoInteractions(books, embeddingModel, ranker);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"horror", "romance"})
  void tasteReplyPreservesTheRequestedAuthorWithoutChangingTheSuppliedGenre(String genre) {
    var request = personalisedRequest();
    request.setExplicitSearch(true);
    request.setRawText("personalised book by jk rowling");
    var include = new ExactCriteria();
    include.setAuthors(List.of("jk rowling"));
    request.setMustInclude(include);
    assertThat(process(request).isPreferenceClarificationRequired()).isTrue();
    var reply = new ConversationDraft();
    reply.setIntent(Intent.RECOMMENDATION);
    reply.setRawText(genre);
    var query = new SemanticQuery();
    query.setGenres(List.of(genre));
    reply.setPositiveSemanticQuery(query);
    var result = process(reply);
    assertThat(result.getRawText()).isEqualTo(genre);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly(genre);
    assertThat(result.getMustInclude().getAuthors()).containsExactly("jk rowling");
    var saved = sessions.getSession("session", "user").getPreferences();
    assertThat(saved.getPositiveSemanticQuery().getGenres()).containsExactly(genre);
    assertThat(saved.getMustInclude().getAuthors()).isNull();
  }

  @Test
  void updatingAnAuthorPreservesSavedGenresForTheNextPersonalisedSearch() {
    var session = new Session();
    session.setSessionId("session");
    session.setUserId("user");
    var saved = new SessionPreferences();
    var genre = new SemanticQuery();
    genre.setGenres(List.of("romance"));
    saved.setPositiveSemanticQuery(genre);
    session.setPreferences(saved);
    sessions.updateSession(session);
    var update = new ConversationDraft();
    update.setIntent(Intent.UPDATE_PREFERENCES);
    var changes = new SessionPreferences();
    var author = new ExactCriteria();
    author.setAuthors(List.of("jk rowling"));
    changes.setMustInclude(author);
    update.setPreferenceUpdates(changes);
    process(update);
    var result = process(personalisedRequest());
    assertThat(result.isPreferenceClarificationRequired()).isFalse();
    assertThat(result.getMustInclude().getAuthors()).containsExactly("jk rowling");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("romance");
  }

  @Test
  void cancellationSurvivesSessionReloadAndDoesNotRestartThePreferenceQuestion() {
    process(personalisedRequest());
    var cancel = new ConversationDraft();
    cancel.setIntent(Intent.UNKNOWN);
    cancel.setRawText("never mind");
    var result = process(cancel);
    assertThat(result.getDecision())
        .isEqualTo(com.gen3.recommenderagent.domain.session.ConversationDecision.CANCELLED);
    assertThat(responseGenerator.generate(result.getRecommendations(), result))
        .contains("cancelled");
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences())
        .isFalse();
    var unknown = new ConversationDraft();
    unknown.setIntent(Intent.UNKNOWN);
    assertThat(process(unknown).getIntent()).isEqualTo(Intent.UNKNOWN);
    verifyNoInteractions(books, embeddingModel, ranker);
  }

  @Test
  void knownUserRetrievesWithCombinedVectorsWithoutCallingEmbeddingModel() throws Exception {
    when(users.findEmbeddingByUserId("user"))
        .thenReturn(
            Optional.of(
                new UserPreferenceEmbedding("user", new float[] {1, 0}, new float[] {0, 1})));

    ConversationTurn result = process(personalisedRequest());

    ArgumentCaptor<float[]> vector = ArgumentCaptor.forClass(float[].class);
    verify(books).searchSemantic(vector.capture(), eq(AudiobookFilters.empty()), eq(50));
    assertArrayEquals(new float[] {0.70710677f, 0.70710677f}, vector.getValue(), 0.0001f);
    assertThat(result.getRecommendations()).hasSize(1);
    assertThat(result.getRecommendations().getFirst().isReadingPreferencesUsed()).isTrue();
    assertThat(result.isPreferenceClarificationRequired()).isFalse();
    verifyNoInteractions(embeddingModel);
    verify(users).findEmbeddingByUserId("user");
  }

  @Test
  void knownUserWithOnlyOneVectorStillRetrievesAndKeepsFilters() throws Exception {
    when(users.findEmbeddingByUserId("user"))
        .thenReturn(
            Optional.of(new UserPreferenceEmbedding("user", new float[0], new float[] {1, 0})));
    ConversationDraft request = personalisedRequest();
    ExactCriteria filter = new ExactCriteria();
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
    ConversationTurn first = process(personalisedRequest());

    assertThat(first.getRecommendations()).isEmpty();
    assertThat(responseGenerator.generate(first.getRecommendations(), first))
        .isEqualTo("What genres do you like?");
    verifyNoInteractions(books, embeddingModel, ranker);
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences()).isTrue();

    // Simulate a separate request reading the persisted session, as Redis does.
    JsonMapper mapper = JsonMapper.builder().build();
    Session restored =
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class);
    sessions.updateSession(restored);
    ConversationDraft answer = new ConversationDraft();
    answer.setIntent(Intent.REFINE);
    SemanticQuery taste = new SemanticQuery();
    taste.setGenres(List.of("fantasy"));
    taste.setAuthors(List.of("Ursula Le Guin"));
    answer.setPositiveSemanticQuery(taste);

    ConversationTurn result = process(answer);

    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getRecommendations()).hasSize(1);
    restored = sessions.getSession("session", "user");
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
    ConversationDraft pending = personalisedRequest();
    pending.setPreferenceClarificationRequired(true);
    session.getRequests().add(ConversationTurn.fromDraft(pending));
    sessions.updateSession(session);
    ConversationDraft answer = new ConversationDraft();
    answer.setIntent(Intent.REFINE);
    SemanticQuery taste = new SemanticQuery();
    taste.setAuthors(List.of("Ursula Le Guin"));
    answer.setPositiveSemanticQuery(taste);
    process(answer);
    session = sessions.getSession("session", "user");

    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("fantasy");
    assertThat(session.getPreferences().getPositiveSemanticQuery().getAuthors())
        .containsExactly("Ursula Le Guin");
    ConversationDraft more = new ConversationDraft();
    more.setIntent(Intent.MORE_RESULTS);
    assertThat(process(more).isPersonalised()).isTrue();
  }

  @Test
  void exactOnlyPreferencesAskForSemanticTasteWithoutEmbeddingFilters() {
    Session session = sessionWithPreferences();
    ExactCriteria include = new ExactCriteria();
    include.setAuthors(List.of("Ursula Le Guin"));
    include.setLanguage("English");
    session.getPreferences().setMustInclude(include);
    sessions.updateSession(session);
    assertThat(process(personalisedRequest()).isPreferenceClarificationRequired()).isTrue();
    verifyNoInteractions(books, embeddingModel, ranker);
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
    ConversationDraft help = new ConversationDraft();
    help.setIntent(Intent.HELP);
    assertThat(process(help).getIntent()).isEqualTo(Intent.HELP);
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences()).isTrue();
    ConversationDraft clear = new ConversationDraft();
    clear.setIntent(Intent.CLEAR_HISTORY);
    process(clear);
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences())
        .isFalse();
    verifyNoInteractions(books, embeddingModel);
  }

  @Test
  void knownUserBlendsBothSemanticPolaritiesAndPreservesBothExactFilterGroups() throws Exception {
    when(users.findEmbeddingByUserId("user"))
        .thenReturn(
            Optional.of(new UserPreferenceEmbedding("user", new float[] {1, 0, 0}, new float[0])));
    when(embeddingModel.embedForResponse(anyList()))
        .thenReturn(
            new EmbeddingResponse(
                List.of(
                    new Embedding(new float[] {0, 1, 0}, 0),
                    new Embedding(new float[] {0, 0, 1}, 1))));
    ConversationDraft request = personalisedRequest();
    SemanticQuery positive = new SemanticQuery();
    positive.setTopics(List.of("deserts"));
    SemanticQuery negative = new SemanticQuery();
    negative.setGenres(List.of("romance"));
    request.setPositiveSemanticQuery(positive);
    request.setNegativeSemanticQuery(negative);
    ExactCriteria include = new ExactCriteria();
    include.setLanguage("English");
    include.setDuration("at most 2 hours");
    ExactCriteria exclude = new ExactCriteria();
    exclude.setAuthors(List.of("Excluded Author"));
    request.setMustInclude(include);
    request.setMustNotInclude(exclude);

    ConversationTurn result = process(request);

    ArgumentCaptor<float[]> vector = ArgumentCaptor.forClass(float[].class);
    ArgumentCaptor<AudiobookFilters> filters = ArgumentCaptor.forClass(AudiobookFilters.class);
    verify(books).searchSemantic(vector.capture(), filters.capture(), eq(50));
    assertArrayEquals(
        new float[] {0.4082483f, 0.4082483f, -0.8164966f}, vector.getValue(), 0.0001f);
    assertThat(filters.getValue().mustInclude().language()).isEqualTo("English");
    assertThat(filters.getValue().mustInclude().maximumDurationMinutes()).isEqualTo(120);
    assertThat(filters.getValue().mustNotInclude().authors()).containsExactly("Excluded Author");
    verify(embeddingModel).embedForResponse(List.of("Topics: deserts", "Genres: romance"));
    assertThat(result.getPositiveSemanticQuery().getTopics()).containsExactly("deserts");
    assertThat(result.getNegativeSemanticQuery().getGenres()).containsExactly("romance");
    assertThat(result.getRecommendations()).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void shortTasteAnswerRetainsPendingConstraintsAndCountAcrossReload(boolean changesYear)
      throws Exception {
    var request = personalisedRequest();
    var include = new ExactCriteria();
    include.setPublicationYear("recent");
    request.setMustInclude(include);
    request.setBookCount(2);
    assertThat(process(request).isPreferenceClarificationRequired()).isTrue();
    var mapper = JsonMapper.builder().build();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")), Session.class));
    var answer = new ConversationDraft();
    answer.setIntent(Intent.RECOMMENDATION);
    var taste = new SemanticQuery();
    taste.setGenres(List.of("fantasy"));
    answer.setPositiveSemanticQuery(taste);
    if (changesYear) {
      var changed = new ExactCriteria();
      changed.setPublicationYear("2020");
      answer.setMustInclude(changed);
    }
    var result = process(answer);
    assertThat(result.getMustInclude().getPublicationYear())
        .isEqualTo(changesYear ? "2020" : "recent");
    assertThat(result.getBookCount()).isEqualTo(2);
    var filters = ArgumentCaptor.forClass(AudiobookFilters.class);
    verify(books).searchSemantic(any(), filters.capture(), eq(50));
    assertThat(filters.getValue().mustInclude().minimumPublicationYear()).isNotNull();
  }

  @Test
  void explicitSearchAbandonsPendingPersonalisationAndConstraints() throws Exception {
    var request = personalisedRequest();
    var include = new ExactCriteria();
    include.setPublicationYear("recent");
    request.setMustInclude(include);
    process(request);
    var answer = new ConversationDraft();
    answer.setIntent(Intent.RECOMMENDATION);
    answer.setExplicitSearch(true);
    var taste = new SemanticQuery();
    taste.setGenres(List.of("horror"));
    answer.setPositiveSemanticQuery(taste);
    when(books.searchSemantic(any(), any(), eq(50))).thenReturn(List.of());
    var result = process(answer);
    assertThat(result.isPersonalised()).isFalse();
    assertThat(
            result.getMustInclude() == null || result.getMustInclude().getPublicationYear() == null)
        .isTrue();
    verify(books).searchSemantic(any(), eq(AudiobookFilters.empty()), eq(50));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void recentPersonalisedRequestNeedsSemanticTasteOnlyWithoutUserVectors(boolean knownUser)
      throws Exception {
    if (knownUser) {
      when(users.findEmbeddingByUserId("user"))
          .thenReturn(Optional.of(new UserPreferenceEmbedding("user", new float[] {1, 0}, null)));
    }
    var request = personalisedRequest();
    var include = new ExactCriteria();
    include.setPublicationYear("recent");
    request.setMustInclude(include);
    var result = process(request);
    assertThat(result.isPreferenceClarificationRequired()).isEqualTo(!knownUser);
    if (knownUser) {
      verify(books).searchSemantic(any(), any(), eq(50));
    } else {
      verifyNoInteractions(books, embeddingModel, ranker);
    }
  }

  @Test
  void tasteReplyUsesRefinementRulesForChangedAndConflictingConstraints() {
    var request = personalisedRequest();
    var include = new ExactCriteria();
    include.setLanguage("English");
    request.setMustInclude(include);
    process(request);
    var answer = new ConversationDraft();
    answer.setIntent(Intent.RECOMMENDATION);
    var taste = new SemanticQuery();
    taste.setGenres(List.of("fantasy"));
    answer.setPositiveSemanticQuery(taste);
    var excluded = new ExactCriteria();
    excluded.setLanguage("English");
    answer.setMustNotInclude(excluded);
    var result = process(answer);
    assertThat(result.getMustInclude() == null || result.getMustInclude().getLanguage() == null)
        .isTrue();
    assertThat(result.getMustNotInclude().getLanguage()).isEqualTo("English");
  }

  @Test
  void negativeTasteInFirstRequestStillAsksForSavedPreferences() throws Exception {
    var request = personalisedRequest();
    var avoided = new SemanticQuery();
    avoided.setGenres(List.of("romance"));
    request.setNegativeSemanticQuery(avoided);
    assertThat(process(request).isPreferenceClarificationRequired()).isTrue();
    verifyNoInteractions(books, embeddingModel, ranker);
  }

  @Test
  void contradictoryTasteReplyPreservesThePendingRequest() {
    var request = personalisedRequest();
    var include = new ExactCriteria();
    include.setPublicationYear("recent");
    request.setMustInclude(include);
    process(request);
    var answer = new ConversationDraft();
    answer.setIntent(Intent.RECOMMENDATION);
    var taste = new SemanticQuery();
    taste.setGenres(List.of("fantasy"));
    answer.setPositiveSemanticQuery(taste);
    var both = new ExactCriteria();
    both.setLanguage("English");
    answer.setMustInclude(both);
    var excluded = new ExactCriteria();
    excluded.setLanguage("English");
    answer.setMustNotInclude(excluded);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> process(answer))
        .isInstanceOf(com.gen3.recommenderagent.inputparser.InvalidRequestException.class);
    assertThat(sessions.getSession("session", "user").isAwaitingPersonalisedPreferences()).isTrue();
    verifyNoInteractions(books, embeddingModel, ranker);
  }

  private ConversationTurn process(ConversationDraft request) {
    return application.process("session", "user", request);
  }

  private ConversationDraft personalisedRequest() {
    ConversationDraft request = new ConversationDraft();
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
