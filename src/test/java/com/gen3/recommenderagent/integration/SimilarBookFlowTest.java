package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.catalogue.port.AudiobookTitleLookup;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.application.recommendation.SimilarBookResolver;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimilarBookFlowTest {
  private final AudiobookTitleLookup titles = mock(AudiobookTitleLookup.class);
  private final AudiobookVectorRepository vectors = mock(AudiobookVectorRepository.class);
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final RecommendationFlowTestSupport.InMemorySessionRepository sessions =
      new RecommendationFlowTestSupport.InMemorySessionRepository();
  private final ConversationEngine engine =
      new ConversationEngine(
          ConversationTestSupport.dispatcher(),
          new RecommendationPager(workflow),
          new ExactFilterPolicy(),
          new SimilarBookResolver(titles, vectors));
  private final SessionTransitionService service = new SessionTransitionService(sessions, engine);
  private final AudiobookTitle book =
      new AudiobookTitle("hunger", "The Hunger Games", List.of("Suzanne Collins"));

  @BeforeEach
  void setup() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt())).thenReturn(List.of(book));
    when(vectors.findEmbeddingByBookId(anyString())).thenReturn(Optional.of(new float[] {1, 0}));
    when(workflow.recommend(any(), any()))
        .thenReturn(
            IntStream.rangeClosed(1, 20)
                .mapToObj(i -> new Recommendation("book-" + i, i, 1.0, "Book " + i))
                .toList());
  }

  private ConversationDraft request(Intent intent, String title) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    request.setSimilarToTitle(title);
    request.setExplicitSearch(true);
    request.setRawText(title == null ? intent.name() : "books similar to " + title);
    return request;
  }

  private void ambiguousReference() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(Intent.RECOMMENDATION, "hunger games"));
  }

  private com.gen3.recommenderagent.domain.session.SessionRequest parsedReply(String text) {
    return new com.gen3.recommenderagent.inputparser.InputParser(
            new com.gen3.recommenderagent.inputparser.CommandParser(),
            mock(com.gen3.recommenderagent.inputparser.AiParser.class))
        .parse(text);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {"CHANGE_COUNT", "RESET_PREFERENCES"})
  void pendingReferenceUsesTheCurrentCountAfterHelpAndReload(Intent action) throws Exception {
    ambiguousReference();
    var change = request(Intent.CHANGE_COUNT, null);
    change.setBookCount(2);
    service.process("session", "user", change);
    if (action == Intent.RESET_PREFERENCES) {
      service.process("session", "user", request(action, null));
    }
    service.process("session", "user", request(Intent.HELP, null));
    var mapper = new tools.jackson.databind.ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")),
            com.gen3.recommenderagent.domain.session.Session.class));
    var result = service.process("session", "user", parsedReply("1"));
    assertThat(result.getBookCount()).isEqualTo(action == Intent.CHANGE_COUNT ? 2 : 5);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "RESET_PREFERENCES,false",
    "UPDATE_PREFERENCES,false",
    "RESET_PREFERENCES,true",
    "UPDATE_PREFERENCES,true"
  })
  void referenceSelectionReappliesCurrentPreferencesAndKeepsExplicitConstraints(
      Intent action, boolean explicitAuthor) throws Exception {
    service.process("session", "user", request(Intent.HELP, null));
    var old = new com.gen3.recommenderagent.domain.session.SessionPreferences();
    var oldFilter = new ExactCriteria();
    oldFilter.setAuthors(List.of("Old Writer"));
    oldFilter.setLanguage("English");
    old.setMustInclude(oldFilter);
    var oldTaste = new com.gen3.recommenderagent.domain.session.SemanticQuery();
    oldTaste.setGenres(List.of("romance"));
    old.setPositiveSemanticQuery(oldTaste);
    var session = sessions.getSession("session", "user");
    session.setPreferences(old);
    sessions.updateSession(session);
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    var search = request(Intent.RECOMMENDATION, "hunger games");
    search.setPersonalised(true);
    var criteria = new ExactCriteria();
    criteria.setDuration("under 5 hours");
    if (explicitAuthor) {
      criteria.setAuthors(List.of("Explicit Writer"));
    }
    search.setMustInclude(criteria);
    service.process("session", "user", search);
    var edit = request(action, null);
    if (action == Intent.UPDATE_PREFERENCES) {
      var next = new com.gen3.recommenderagent.domain.session.SessionPreferences();
      var filter = new ExactCriteria();
      filter.setAuthors(List.of("New Writer"));
      filter.setLanguage("French");
      next.setMustInclude(filter);
      var taste = new com.gen3.recommenderagent.domain.session.SemanticQuery();
      taste.setGenres(List.of("horror"));
      next.setPositiveSemanticQuery(taste);
      edit.setPreferenceUpdates(next);
      edit.setReplacePreferenceFields(
          List.of("mustInclude.authors", "mustInclude.language", "positiveSemanticQuery.genres"));
    }
    service.process("session", "user", edit);
    var mapper = new tools.jackson.databind.ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")),
            com.gen3.recommenderagent.domain.session.Session.class));
    var result = service.process("session", "user", parsedReply("1"));
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 5 hours");
    assertThat(result.getMustInclude().getAuthors())
        .isEqualTo(
            explicitAuthor
                ? List.of("Explicit Writer")
                : action == Intent.UPDATE_PREFERENCES ? List.of("New Writer") : null);
    assertThat(result.getMustInclude().getLanguage())
        .isEqualTo(action == Intent.UPDATE_PREFERENCES ? "French" : null);
    assertThat(result.getSearchCriteria().getMustInclude().getAuthors())
        .isEqualTo(explicitAuthor ? List.of("Explicit Writer") : null);
    if (action == Intent.RESET_PREFERENCES) {
      assertThat(result.getPositiveSemanticQuery()).isNull();
    } else {
      assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    }
  }

  @Test
  void referenceAuthorVerificationAndAuthorRepliesUseNameEquivalence() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("hunger", "The Hunger Games", List.of("Collins, Suzanne."))));
    var search = request(Intent.RECOMMENDATION, "The Hunger Games");
    search.setSimilarToAuthor("Suzanne Collins");
    assertThat(service.process("session", "user", search).getSimilarBook().bookId())
        .isEqualTo("hunger");
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("hunger", "The Hunger Games", List.of("Collins, Suzanne.")),
                new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(Intent.RECOMMENDATION, "hunger games"));
    var reply = request(Intent.UNKNOWN, null);
    reply.setRawText("Suzanne Collins");
    reply.setExplicitSearch(false);
    assertThat(service.process("session", "user", reply).getSimilarBook().bookId())
        .isEqualTo("hunger");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"none of these", "none of them", "neither", "None of those, please!"})
  void rejectingAllReferenceChoicesAsksForAnotherTitleAndCanResume(String text) throws Exception {
    ambiguousReference();
    org.mockito.Mockito.clearInvocations(workflow, vectors, titles);
    var result = service.process("session", "user", parsedReply(text));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SIMILAR_BOOK_REQUIRED);
    assertThat(result.getSimilarBookChoices()).isEmpty();
    verifyNoInteractions(workflow, vectors, titles);
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(new AudiobookTitle("hunger", "Another Book", List.of("Another Author"))));
    var named = request(Intent.UNKNOWN, null);
    named.setRawText("Another Book");
    named.setExplicitSearch(false);
    var resumed = service.process("session", "user", named);
    assertThat(resumed.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(resumed.getSimilarBook().title()).isEqualTo("Another Book");
  }

  @Test
  void exactTitleAtTheLookupBoundaryIsIncludedInDisplayedChoices() throws Exception {
    var matches = new java.util.ArrayList<AudiobookTitle>();
    for (int i = 0; i < 5; i++) {
      matches.add(
          new AudiobookTitle("partial-" + i, "The Hunger Games companion " + i, List.of("Writer")));
    }
    matches.add(new AudiobookTitle("hunger", "The Hunger Games", List.of("Suzanne Collins")));
    when(titles.findBooksByTitle(anyString(), any(), anyInt())).thenReturn(matches);
    var result =
        service.process("session", "user", request(Intent.RECOMMENDATION, "The Hunger Games"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SIMILAR_BOOK_SELECTION);
    assertThat(result.getSimilarBookChoices()).hasSize(5);
    assertThat(result.getSimilarBookChoices().getFirst().bookId()).isEqualTo("hunger");
    var selected = service.process("session", "user", parsedReply("1"));
    assertThat(selected.getSimilarBook().bookId()).isEqualTo("hunger");
  }

  @Test
  void exactReferenceSurvivesMoreRefineExplainAndSessionSerialization() throws Exception {
    var first =
        service.process("session", "user", request(Intent.RECOMMENDATION, "the hunger games"));
    assertThat(first.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(first.isPersonalised()).isFalse();
    assertThat(first.getRecommendations()).hasSize(5);
    var more = service.process("session", "user", request(Intent.MORE_RESULTS, null));
    assertThat(more.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    var explain = service.process("session", "user", request(Intent.EXPLAIN_RECOMMENDATION, null));
    assertThat(explain.getSimilarBook().bookId()).isEqualTo("hunger");
    var refine = request(Intent.REFINE, null);
    var criteria = new ExactCriteria();
    criteria.setDuration("under 5 hours");
    refine.setMustInclude(criteria);
    var refined = service.process("session", "user", refine);
    assertThat(refined.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(refined.getMustInclude().getDuration()).isEqualTo("under 5 hours");
    verify(titles).findBooksByTitle(anyString(), any(), anyInt());
    verify(vectors).findEmbeddingByBookId("hunger");
    var fresh = request(Intent.RECOMMENDATION, null);
    var tastes = new com.gen3.recommenderagent.domain.session.SemanticQuery();
    tastes.setGenres(List.of("mystery"));
    fresh.setPositiveSemanticQuery(tastes);
    assertThat(service.process("session", "user", fresh).getSimilarBook()).isNull();
  }

  @Test
  void ambiguousEditionAsksThenNumericReplyResumesOriginalConstraints() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("edition", "The Hunger Games", List.of("Other Author"))));
    var search = request(Intent.RECOMMENDATION, "The Hunger Games");
    var exact = new ExactCriteria();
    exact.setDuration("under 10 hours");
    search.setMustInclude(exact);
    var choices = service.process("session", "user", search);
    assertThat(choices.getDecision()).isEqualTo(ConversationDecision.SIMILAR_BOOK_SELECTION);
    verifyNoInteractions(workflow, vectors);
    var invalid = request(Intent.UNKNOWN, null);
    invalid.setRawText("999999999999999999999th");
    invalid.setExplicitSearch(false);
    assertThat(service.process("session", "user", invalid).getDecision())
        .isEqualTo(ConversationDecision.SIMILAR_BOOK_SELECTION);
    var reply = request(Intent.UNKNOWN, null);
    reply.setRawText("the second one");
    reply.setExplicitSearch(false);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("edition");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 10 hours");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "one", "the first one"})
  void rawSelectionOverridesIncorrectAiSearchAndRepeatFlags(String text) throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book,
                new AudiobookTitle("other", "The hunger games", List.of("Collins, Suzanne"))));
    service.process("session", "user", request(Intent.RECOMMENDATION, "hunger games"));
    var reply = request(Intent.REPEAT, "1");
    reply.setRawText(text);
    reply.setExplicitSearch(true);
    reply.setSimilarToClarificationRequired(true);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    verify(vectors).findEmbeddingByBookId("hunger");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "one"})
  void parserToStoredSessionSelectsTheFirstEditionWithoutAi(String text) throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book,
                new AudiobookTitle("other", "The hunger games", List.of("Collins, Suzanne"))));
    service.process("session", "user", request(Intent.RECOMMENDATION, "hunger games"));
    var client = mock(org.springframework.ai.chat.client.ChatClient.class);
    var parser =
        new com.gen3.recommenderagent.inputparser.InputParser(
            new com.gen3.recommenderagent.inputparser.CommandParser(),
            new com.gen3.recommenderagent.inputparser.AiParser(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()));
    var result = service.process("session", "user", parser.parse(text));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Intent.class,
      names = {"EXPLAIN_RECOMMENDATION", "CHANGE_COUNT"})
  void latestClarificationOwnsSelectionWithoutLoadingAnOlderReferenceVector(Intent intent)
      throws Exception {
    service.process("session", "user", request(Intent.RECOMMENDATION, null));
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(Intent.RECOMMENDATION, "hunger games"));
    var question = request(intent, null);
    if (intent == Intent.EXPLAIN_RECOMMENDATION) {
      question.setReferenceRank(6);
    }
    var clarification = service.process("session", "user", question);
    assertThat(clarification.getDecision())
        .isEqualTo(
            intent == Intent.EXPLAIN_RECOMMENDATION
                ? ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
                : ConversationDecision.COUNT_CLARIFICATION);
    when(vectors.findEmbeddingByBookId(anyString()))
        .thenThrow(new java.io.IOException("unrelated catalogue unavailable"));
    var reply = request(Intent.UNKNOWN, null);
    reply.setRawText("1");
    reply.setExplicitSearch(false);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision())
        .isEqualTo(
            intent == Intent.EXPLAIN_RECOMMENDATION
                ? ConversationDecision.EXPLANATION
                : ConversationDecision.COUNT_CHANGED);
    assertThat(result.getSimilarBook()).isNull();
    if (intent == Intent.EXPLAIN_RECOMMENDATION) {
      assertThat(result.getRecommendations())
          .extracting(Recommendation::getBookId)
          .containsExactly("book-1");
    } else {
      assertThat(result.getBookCount()).isEqualTo(1);
    }
    verifyNoInteractions(vectors);
  }

  @Test
  void referenceMatchingIgnoresTheSameCatalogueMarkersAsExplanationMatching() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle(
                    "hunger", "The Hunger Games $b Volume 1", List.of("Suzanne Collins"))));
    var result =
        service.process(
            "session", "user", request(Intent.RECOMMENDATION, "the hunger games volume 1"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
  }

  @Test
  void selectingAReferenceStartsItsOwnBatchInsteadOfUsingThePreviousSearchTail() throws Exception {
    when(workflow.recommend(any(), any()))
        .thenReturn(
            java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> new Recommendation("old-" + i, i, 1.0, "Old " + i))
                .toList(),
            List.of(new Recommendation("similar-book", 1, 1.0, "Similar Book")));
    service.process("session", "user", request(Intent.RECOMMENDATION, null));
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(Intent.RECOMMENDATION, "hunger games"));
    var reply = request(Intent.UNKNOWN, null);
    reply.setRawText("1");
    reply.setExplicitSearch(false);
    var result = service.process("session", "user", reply);
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactly("similar-book");
    org.mockito.Mockito.verify(workflow, org.mockito.Mockito.times(2)).recommend(any(), any());
  }

  @Test
  void authorDisambiguatesReferenceWithoutFilteringResultsByThatAuthor() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    var search = request(Intent.RECOMMENDATION, "The Hunger Games");
    search.setSimilarToAuthor("suzanne collins");
    var result = service.process("session", "user", search);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(result.getMustInclude() == null || result.getMustInclude().getAuthors() == null)
        .isTrue();
  }

  @Test
  void missingVectorPreservesPreviousSearchAndPendingPage() throws Exception {
    service.process("session", "user", request(Intent.RECOMMENDATION, "The Hunger Games"));
    when(vectors.findEmbeddingByBookId("hunger")).thenReturn(Optional.empty());
    var failed =
        service.process("session", "user", request(Intent.RECOMMENDATION, "The Hunger Games"));
    assertThat(failed.getDecision()).isEqualTo(ConversationDecision.SIMILAR_BOOK_UNAVAILABLE);
    assertThat(
            new ConversationResponseGenerator(new AiResponseGenerator(null))
                .generate(failed.getRecommendations(), failed))
        .contains("can't use that book");
    var more = service.process("session", "user", request(Intent.MORE_RESULTS, null));
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getBookId)
        .startsWith("book-6");
    assertThat(more.getSimilarBook().bookId()).isEqualTo("hunger");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void referenceRequestStartsNewSearchWhileAwaitingPersonalisedTastes() throws Exception {
    var session = new com.gen3.recommenderagent.domain.session.Session();
    session.setSessionId("waiting");
    session.setUserId("user");
    var previous = new com.gen3.recommenderagent.domain.session.ConversationTurn();
    previous.setIntent(Intent.RECOMMENDATION);
    previous.setPreferenceClarificationRequired(true);
    session.getRequests().add(previous);
    sessions.updateSession(session);
    var search = request(Intent.RECOMMENDATION, "The Hunger Games");
    search.setExplicitSearch(false);
    com.gen3.recommenderagent.domain.conversation.ConversationRules.normalizeInput(search);
    var result = service.process("waiting", "user", search);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(result.isPersonalised()).isFalse();
  }

  @Test
  void referenceIgnoresSavedSemanticTastesButKeepsSavedExactConstraints() throws Exception {
    var session = new com.gen3.recommenderagent.domain.session.Session();
    session.setSessionId("preferences");
    session.setUserId("user");
    var preferences = new com.gen3.recommenderagent.domain.session.SessionPreferences();
    var tastes = new com.gen3.recommenderagent.domain.session.SemanticQuery();
    tastes.setGenres(List.of("romance"));
    preferences.setPositiveSemanticQuery(tastes);
    var exact = new ExactCriteria();
    exact.setLanguage("English");
    preferences.setMustInclude(exact);
    session.setPreferences(preferences);
    sessions.updateSession(session);
    var result =
        service.process("preferences", "user", request(Intent.RECOMMENDATION, "The Hunger Games"));
    assertThat(result.getPositiveSemanticQuery()).isNull();
    assertThat(result.getMustInclude().getLanguage()).isEqualTo("English");
  }

  @Test
  void missingBookTasteReplyKeepsOriginalConstraintsAndDropsUnresolvedReference() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt())).thenReturn(List.of());
    var search = request(Intent.RECOMMENDATION, "Unknown Book");
    var exact = new ExactCriteria();
    exact.setDuration("under 5 hours");
    search.setMustInclude(exact);
    service.process("session", "user", search);
    var reply = request(Intent.REFINE, null);
    reply.setExplicitSearch(false);
    var tastes = new com.gen3.recommenderagent.domain.session.SemanticQuery();
    tastes.setGenres(List.of("fantasy"));
    reply.setPositiveSemanticQuery(tastes);
    var result = service.process("session", "user", reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 5 hours");
    assertThat(result.getSimilarBook()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void missingBookAsksAboutAspectsWithoutRetrieval() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt())).thenReturn(List.of());
    var result = service.process("session", "user", request(Intent.RECOMMENDATION, "Unknown Book"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.SIMILAR_BOOK_NOT_FOUND);
    verifyNoInteractions(workflow, vectors);
  }

  @Test
  void seriesRequiresAnIndividualBookAndCancelClearsPendingSelection() throws Exception {
    var series = request(Intent.RECOMMENDATION, "The Hunger Games");
    series.setSimilarToClarificationRequired(true);
    assertThat(service.process("session", "user", series).getDecision())
        .isEqualTo(ConversationDecision.SIMILAR_BOOK_REQUIRED);
    verifyNoInteractions(titles, vectors, workflow);
    service.process("session", "user", request(Intent.CANCEL, null));
    var reply = request(Intent.UNKNOWN, null);
    reply.setRawText("2");
    reply.setExplicitSearch(false);
    assertThat(service.process("session", "user", reply).getDecision())
        .isEqualTo(ConversationDecision.UNKNOWN);
  }
}
