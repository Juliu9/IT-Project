package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.intent.CancelHandler;
import com.gen3.recommenderagent.application.conversation.intent.ChangeCountHandler;
import com.gen3.recommenderagent.application.conversation.intent.ExplainRecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.HelpHandler;
import com.gen3.recommenderagent.application.conversation.intent.MoreResultsHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.RefineHandler;
import com.gen3.recommenderagent.application.conversation.intent.RepeatHandler;
import com.gen3.recommenderagent.application.conversation.intent.ResetPreferencesHandler;
import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.intent.UpdatePreferencesHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.application.recommendation.SimilarBookResolver;
import com.gen3.recommenderagent.application.session.SessionTransitionService;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import com.gen3.shared.audiobookcatalogue.model.AudiobookTitle;
import com.gen3.shared.audiobookcatalogue.port.AudiobookTitleLookup;
import com.gen3.shared.audiobookcatalogue.port.AudiobookVectorRepository;
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
          com.gen3.recommenderagent.testsupport.TestHandlers.registry(),
          new RecommendationPager(
              workflow,
              new com.gen3.recommenderagent.application.recommendation.ReadHistoryExclusions(
                  java.util.Optional.empty(), 10000)),
          new ExactFilterPolicy(),
          new SimilarBookResolver(titles, vectors));
  private final SessionTransitionService service =
      new SessionTransitionService(
          sessions,
          engine,
          new com.gen3.recommenderagent.inputparser.InputParser(
              new com.gen3.recommenderagent.inputparser.CommandParser(
                  com.gen3.recommenderagent.testsupport.TestHandlers.registry()),
              com.gen3.recommenderagent.testsupport.TestHandlers.ai(
                  (org.springframework.ai.chat.client.ChatClient.Builder) null),
              com.gen3.recommenderagent.testsupport.TestHandlers.registry()));
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

  private ConversationDraft request(ConversationAction intent, String title) {
    var request = new ConversationDraft();
    request.setIntent(intent);
    request.setSimilarToTitle(title);
    request.setStartsNewSearch(true);
    request.setRawText(title == null ? intent.name() : "books similar to " + title);
    return request;
  }

  private void ambiguousReference() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(RecommendationHandler.ACTION, "hunger games"));
  }

  @Test
  void oneAuthorAndNarratorReplaceSavedChoicesAndSurviveReferenceSelection() throws Exception {
    var session = new com.gen3.recommenderagent.domain.session.Session();
    session.setSessionId("session");
    session.setUserId("user");
    var preferences = new com.gen3.recommenderagent.domain.session.SessionPreferences();
    var saved = new com.gen3.recommenderagent.domain.search.ExactCriteria();
    saved.setAuthors(List.of("Author A"));
    saved.setNarrators(List.of("Narrator A"));
    preferences.setMustInclude(saved);
    session.setPreferences(preferences);
    sessions.updateSession(session);
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    var search = request(RecommendationHandler.ACTION, "hunger games");
    var include = new com.gen3.recommenderagent.domain.search.ExactCriteria();
    include.setAuthors(List.of("Author B"));
    include.setNarrators(List.of("Narrator B"));
    search.setMustInclude(include);
    var question = service.process("session", "user", search);
    assertThat(question.getMustInclude().getAuthors()).containsExactly("Author B");
    assertThat(question.getMustInclude().getNarrators()).containsExactly("Narrator B");
    var result = service.process("session", "user", parsedReply("2"));
    assertThat(result.getMustInclude().getAuthors()).containsExactly("Author B");
    assertThat(result.getMustInclude().getNarrators()).containsExactly("Narrator B");
    var savedPreferences = sessions.getSession("session", "user").getPreferences().getMustInclude();
    assertThat(savedPreferences.getAuthors()).containsExactly("Author A");
    assertThat(savedPreferences.getNarrators()).containsExactly("Narrator A");
  }

  private com.gen3.recommenderagent.application.conversation.model.SessionRequest parsedReply(
      String text) {
    return new com.gen3.recommenderagent.inputparser.InputParser(
            new com.gen3.recommenderagent.inputparser.CommandParser(
                com.gen3.recommenderagent.testsupport.TestHandlers.registry()),
            mock(com.gen3.recommenderagent.inputparser.AiParser.class),
            com.gen3.recommenderagent.testsupport.TestHandlers.registry())
        .parse(text);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @com.gen3.recommenderagent.testsupport.ActionSource(
      value = ConversationAction.class,
      names = {"CHANGE_COUNT", "RESET_PREFERENCES"})
  void settingsAbandonPendingReferenceAcrossHelpAndReload(ConversationAction action)
      throws Exception {
    ambiguousReference();
    var change = request(ChangeCountHandler.ACTION, null);
    change.setBookCount(2);
    service.process("session", "user", change);
    if (action == ResetPreferencesHandler.ACTION) {
      service.process("session", "user", request(action, null));
    }
    service.process("session", "user", request(HelpHandler.ACTION, null));
    var mapper = new tools.jackson.databind.ObjectMapper();
    sessions.updateSession(
        mapper.readValue(
            mapper.writeValueAsString(sessions.getSession("session", "user")),
            com.gen3.recommenderagent.domain.session.Session.class));
    var result = service.process("session", "user", parsedReply("1"));
    assertThat(result.getIntent()).isEqualTo(UnknownHandler.ACTION);
    assertThat(result.getSimilarBook()).isNull();
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "RESET_PREFERENCES,false",
    "UPDATE_PREFERENCES,false",
    "RESET_PREFERENCES,true",
    "UPDATE_PREFERENCES,true"
  })
  void preferenceChangesDiscardTheUnfinishedReferenceSearch(
      ConversationAction action, boolean explicitAuthor) throws Exception {
    service.process("session", "user", request(HelpHandler.ACTION, null));
    var old = new SessionPreferences();
    var oldFilter = new ExactCriteria();
    oldFilter.setAuthors(List.of("Old Writer"));
    oldFilter.setLanguage("English");
    old.setMustInclude(oldFilter);
    var oldTaste = new com.gen3.recommenderagent.domain.search.SemanticQuery();
    oldTaste.setGenres(List.of("romance"));
    old.setPositiveSemanticQuery(oldTaste);
    var session = sessions.getSession("session", "user");
    session.setPreferences(old);
    sessions.updateSession(session);
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    var search = request(RecommendationHandler.ACTION, "hunger games");
    search.setPersonalised(true);
    var criteria = new ExactCriteria();
    criteria.setDuration("under 5 hours");
    if (explicitAuthor) {
      criteria.setAuthors(List.of("Explicit Writer"));
    }
    search.setMustInclude(criteria);
    service.process("session", "user", search);
    var edit = request(action, null);
    if (action == UpdatePreferencesHandler.ACTION) {
      var next = new SessionPreferences();
      var filter = new ExactCriteria();
      filter.setAuthors(List.of("New Writer"));
      filter.setLanguage("French");
      next.setMustInclude(filter);
      var taste = new com.gen3.recommenderagent.domain.search.SemanticQuery();
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
    assertThat(result.getIntent()).isEqualTo(UnknownHandler.ACTION);
    assertThat(result.getSimilarBook()).isNull();
    assertThat(sessions.getSession("session", "user").getPendingQuestion()).isNull();
    assertThat(sessions.getSession("session", "user").getPreferences() == null)
        .isEqualTo(action == ResetPreferencesHandler.ACTION);
  }

  @Test
  void referenceAuthorVerificationAndAuthorRepliesUseNameEquivalence() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("hunger", "The Hunger Games", List.of("Collins, Suzanne."))));
    var search = request(RecommendationHandler.ACTION, "The Hunger Games");
    search.setSimilarToAuthor("Suzanne Collins");
    assertThat(service.process("session", "user", search).getSimilarBook().bookId())
        .isEqualTo("hunger");
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("hunger", "The Hunger Games", List.of("Collins, Suzanne.")),
                new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(RecommendationHandler.ACTION, "hunger games"));
    var reply = request(UnknownHandler.ACTION, null);
    reply.setRawText("Suzanne Collins");
    reply.setStartsNewSearch(false);
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
    assertThat(result.getPendingQuestion()).isEqualTo(RecommendationHandler.SIMILAR_BOOK_REQUIRED);
    assertThat(result.getSimilarBookChoices()).isEmpty();
    verifyNoInteractions(workflow, vectors, titles);
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(new AudiobookTitle("hunger", "Another Book", List.of("Another Author"))));
    var named = request(RecommendationHandler.ACTION, "Another Book");
    named.setRawText("Another Book");
    named.setStartsNewSearch(false);
    var resumed = service.process("session", "user", named);
    assertThat(resumed.getOutcome()).isNull();
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
        service.process(
            "session", "user", request(RecommendationHandler.ACTION, "The Hunger Games"));
    assertThat(result.getPendingQuestion()).isEqualTo(RecommendationHandler.SIMILAR_BOOK_SELECTION);
    assertThat(result.getSimilarBookChoices()).hasSize(5);
    assertThat(result.getSimilarBookChoices().getFirst().bookId()).isEqualTo("hunger");
    var selected = service.process("session", "user", parsedReply("1"));
    assertThat(selected.getSimilarBook().bookId()).isEqualTo("hunger");
  }

  @Test
  void equivalentTitleAndAuthorRecordsAutoResolveWithoutClarification() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("one", "The Fellowship of the Ring", List.of("J R R Tolkien")),
                new AudiobookTitle(
                    "two", "The fellowship of the ring,", List.of("Tolkien, J. R. R.")),
                new AudiobookTitle(
                    "three", "THE FELLOWSHIP OF THE RING", List.of("J. R. R. Tolkien"))));
    var result =
        service.process(
            "session",
            "user",
            request(RecommendationHandler.ACTION, "Lord of the Rings Fellowship of the Ring"));
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook().bookId()).isEqualTo("one");
    assertThat(result.getSimilarBookChoices()).isEmpty();
  }

  @Test
  void clarificationShowsOnlyDistinctTitlesAndAuthors() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("one", "The Fellowship of the Ring", List.of("J R R Tolkien")),
                new AudiobookTitle(
                    "two", "The fellowship of the ring,", List.of("Tolkien, J. R. R.")),
                new AudiobookTitle(
                    "adaptation",
                    "The Fellowship of the Ring (Dramatisation)",
                    List.of("J. R. R. Tolkien"))));
    var result =
        service.process(
            "session",
            "user",
            request(RecommendationHandler.ACTION, "Lord of the Rings Fellowship of the Ring"));
    assertThat(result.getPendingQuestion()).isEqualTo(RecommendationHandler.SIMILAR_BOOK_SELECTION);
    assertThat(result.getSimilarBookChoices())
        .extracting(AudiobookTitle::bookId)
        .containsExactly("one", "adaptation");
    verifyNoInteractions(workflow, vectors);
  }

  @Test
  void differentAuthorsRemainDistinctClarificationChoices() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book,
                new AudiobookTitle("same", "the hunger games", List.of("Collins, Suzanne")),
                new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    var result =
        service.process(
            "session", "user", request(RecommendationHandler.ACTION, "The Hunger Games"));
    assertThat(result.getPendingQuestion()).isEqualTo(RecommendationHandler.SIMILAR_BOOK_SELECTION);
    assertThat(result.getSimilarBookChoices())
        .extracting(AudiobookTitle::bookId)
        .containsExactly("hunger", "other");
  }

  @Test
  void truncatedLookupDoesNotAutoResolveOneVisibleIdentity() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            IntStream.range(0, 6)
                .mapToObj(
                    i ->
                        new AudiobookTitle(
                            "copy-" + i, "The Hunger Games", List.of("Suzanne Collins")))
                .toList());
    var result =
        service.process(
            "session", "user", request(RecommendationHandler.ACTION, "The Hunger Games"));
    assertThat(result.getPendingQuestion()).isEqualTo(RecommendationHandler.SIMILAR_BOOK_SELECTION);
    assertThat(result.getSimilarBookChoices()).hasSize(1);
    verifyNoInteractions(workflow, vectors);
  }

  @Test
  void exactReferenceSurvivesMoreRefineExplainAndSessionSerialization() throws Exception {
    var first =
        service.process(
            "session", "user", request(RecommendationHandler.ACTION, "the hunger games"));
    assertThat(first.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(first.isPersonalised()).isFalse();
    assertThat(first.getRecommendations()).hasSize(5);
    var more = service.process("session", "user", request(MoreResultsHandler.ACTION, null));
    assertThat(more.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(more.getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-6", "book-7", "book-8", "book-9", "book-10");
    var explain =
        service.process("session", "user", request(ExplainRecommendationHandler.ACTION, null));
    assertThat(explain.getSimilarBook().bookId()).isEqualTo("hunger");
    var refine = request(RefineHandler.ACTION, null);
    var criteria = new ExactCriteria();
    criteria.setDuration("under 5 hours");
    refine.setMustInclude(criteria);
    var refined = service.process("session", "user", refine);
    assertThat(refined.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(refined.getMustInclude().getDuration()).isEqualTo("under 5 hours");
    verify(titles).findBooksByTitle(anyString(), any(), anyInt());
    verify(vectors).findEmbeddingByBookId("hunger");
    var fresh = request(RecommendationHandler.ACTION, null);
    var tastes = new com.gen3.recommenderagent.domain.search.SemanticQuery();
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
    var search = request(RecommendationHandler.ACTION, "The Hunger Games");
    var exact = new ExactCriteria();
    exact.setDuration("under 10 hours");
    search.setMustInclude(exact);
    var choices = service.process("session", "user", search);
    assertThat(choices.getPendingQuestion())
        .isEqualTo(RecommendationHandler.SIMILAR_BOOK_SELECTION);
    verifyNoInteractions(workflow, vectors);
    var invalid = request(UnknownHandler.ACTION, null);
    invalid.setRawText("999999999999999999999th");
    invalid.setStartsNewSearch(false);
    assertThat(service.process("session", "user", invalid).getPendingQuestion())
        .isEqualTo(RecommendationHandler.SIMILAR_BOOK_SELECTION);
    var reply = request(UnknownHandler.ACTION, null);
    reply.setRawText("the second one");
    reply.setStartsNewSearch(false);
    var result = service.process("session", "user", reply);
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook().bookId()).isEqualTo("edition");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 10 hours");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "one", "the first one"})
  void rawSelectionOverridesIncorrectAiSearchAndRepeatFlags(String text) throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The hunger games", List.of("Other Author"))));
    service.process("session", "user", request(RecommendationHandler.ACTION, "hunger games"));
    var reply = request(RepeatHandler.ACTION, "1");
    reply.setRawText(text);
    reply.setStartsNewSearch(true);
    reply.setSimilarToClarificationRequired(true);
    var result = service.process("session", "user", reply);
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    verify(vectors).findEmbeddingByBookId("hunger");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "one"})
  void parserToStoredSessionSelectsTheFirstEditionWithoutAi(String text) throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The hunger games", List.of("Other Author"))));
    service.process("session", "user", request(RecommendationHandler.ACTION, "hunger games"));
    var client = mock(org.springframework.ai.chat.client.ChatClient.class);
    var parser =
        new com.gen3.recommenderagent.inputparser.InputParser(
            new com.gen3.recommenderagent.inputparser.CommandParser(
                com.gen3.recommenderagent.testsupport.TestHandlers.registry()),
            com.gen3.recommenderagent.testsupport.TestHandlers.ai(
                client, com.gen3.recommenderagent.observability.AiOperationObserver.noop()),
            com.gen3.recommenderagent.testsupport.TestHandlers.registry());
    var result = service.process("session", "user", parser.parse(text));
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    verifyNoInteractions(client);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @com.gen3.recommenderagent.testsupport.ActionSource(
      value = ConversationAction.class,
      names = {"EXPLAIN_RECOMMENDATION", "CHANGE_COUNT"})
  void latestClarificationOwnsSelectionWithoutLoadingAnOlderReferenceVector(
      ConversationAction intent) throws Exception {
    service.process("session", "user", request(RecommendationHandler.ACTION, null));
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(RecommendationHandler.ACTION, "hunger games"));
    var question = request(intent, null);
    if (intent == ExplainRecommendationHandler.ACTION) {
      question.setReferenceRank(6);
    }
    var clarification = service.process("session", "user", question);
    if (intent == ExplainRecommendationHandler.ACTION) {
      assertThat(clarification.getPendingQuestion())
          .isEqualTo(ExplainRecommendationHandler.EXPLANATION_TARGET_CLARIFICATION);
    } else {
      assertThat(clarification.getOutcome()).isNull();
    }
    when(vectors.findEmbeddingByBookId(anyString()))
        .thenThrow(new java.io.IOException("unrelated catalogue unavailable"));
    var reply = request(UnknownHandler.ACTION, null);
    reply.setRawText("1");
    reply.setStartsNewSearch(false);
    var result = service.process("session", "user", reply);
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook()).isNull();
    if (intent == ExplainRecommendationHandler.ACTION) {
      assertThat(result.getRecommendations())
          .extracting(Recommendation::getId)
          .containsExactly("book-1");
    } else {
      assertThat(sessions.getSession("session", "user").getBookCount()).isEqualTo(5);
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
            "session", "user", request(RecommendationHandler.ACTION, "the hunger games volume 1"));
    assertThat(result.getOutcome()).isNull();
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
    service.process("session", "user", request(RecommendationHandler.ACTION, null));
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    service.process("session", "user", request(RecommendationHandler.ACTION, "hunger games"));
    var reply = request(UnknownHandler.ACTION, null);
    reply.setRawText("1");
    reply.setStartsNewSearch(false);
    var result = service.process("session", "user", reply);
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("similar-book");
    org.mockito.Mockito.verify(workflow, org.mockito.Mockito.times(2)).recommend(any(), any());
  }

  @Test
  void authorDisambiguatesReferenceWithoutFilteringResultsByThatAuthor() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                book, new AudiobookTitle("other", "The Hunger Games", List.of("Other Author"))));
    var search = request(RecommendationHandler.ACTION, "The Hunger Games");
    search.setSimilarToAuthor("suzanne collins");
    var result = service.process("session", "user", search);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(result.getMustInclude() == null || result.getMustInclude().getAuthors() == null)
        .isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "the last one,other",
    "the latter,other",
    "the former,hunger"
  })
  void relativeReferenceSelectionsUseTheDisplayedChoices(String text, String expected)
      throws Exception {
    ambiguousReference();
    service.process("session", "user", request(HelpHandler.ACTION, null));
    var json = new tools.jackson.databind.ObjectMapper();
    sessions.updateSession(
        json.readValue(
            json.writeValueAsString(sessions.getSession("session", "user")),
            com.gen3.recommenderagent.domain.session.Session.class));
    var result = service.process("session", "user", parsedReply(text));
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook().bookId()).isEqualTo(expected);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void unavailableReferenceAcceptsAnotherTitleAndRetainsConstraints(boolean saysYes)
      throws Exception {
    when(vectors.findEmbeddingByBookId("hunger")).thenReturn(Optional.empty());
    var search = request(RecommendationHandler.ACTION, "The Hunger Games");
    var exact = new ExactCriteria();
    exact.setDuration("under 5 hours");
    search.setMustInclude(exact);
    assertThat(service.process("session", "user", search).getPendingQuestion())
        .isEqualTo(RecommendationHandler.SIMILAR_BOOK_UNAVAILABLE);
    service.process("session", "user", request(HelpHandler.ACTION, null));
    if (saysYes) {
      assertThat(service.process("session", "user", parsedReply("yes")).getPendingQuestion())
          .isEqualTo(RecommendationHandler.SIMILAR_BOOK_REQUIRED);
    }
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(List.of(new AudiobookTitle("other", "Another Title", List.of("Writer"))));
    var reply = request(RecommendationHandler.ACTION, "Another Title");
    reply.setRawText("Another Title");
    reply.setStartsNewSearch(false);
    var result = service.process("session", "user", reply);
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook().bookId()).isEqualTo("other");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 5 hours");
  }

  @Test
  void missingVectorPreservesPreviousSearchAndPendingPage() throws Exception {
    service.process("session", "user", request(RecommendationHandler.ACTION, "The Hunger Games"));
    when(vectors.findEmbeddingByBookId("hunger")).thenReturn(Optional.empty());
    var failed =
        service.process(
            "session", "user", request(RecommendationHandler.ACTION, "The Hunger Games"));
    assertThat(failed.getPendingQuestion())
        .isEqualTo(RecommendationHandler.SIMILAR_BOOK_UNAVAILABLE);
    assertThat(
            new ConversationResponseGenerator(
                    new AiResponseGenerator(null),
                    com.gen3.recommenderagent.testsupport.TestHandlers.registry())
                .generate(failed.getRecommendations(), failed))
        .contains("can't use that book");
    var more = service.process("session", "user", request(MoreResultsHandler.ACTION, null));
    assertThat(more.getRecommendations()).extracting(Recommendation::getId).startsWith("book-6");
    assertThat(more.getSimilarBook().bookId()).isEqualTo("hunger");
    verify(workflow).recommend(any(), any());
  }

  @Test
  void referenceRequestStartsNewSearchWhileAwaitingPersonalisedTastes() throws Exception {
    var session = new com.gen3.recommenderagent.domain.session.Session();
    session.setSessionId("waiting");
    session.setUserId("user");
    var previous = new ConversationTurn();
    previous.setIntent(RecommendationHandler.ACTION);
    previous.setPreferenceClarificationRequired(true);
    session.getActiveSearch().setSearch(previous);
    sessions.updateSession(session);
    var search = request(RecommendationHandler.ACTION, "The Hunger Games");
    search.setStartsNewSearch(false);
    com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput(
        search, com.gen3.recommenderagent.testsupport.TestHandlers.registry());
    var result = service.process("waiting", "user", search);
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hunger");
    assertThat(result.isPersonalised()).isFalse();
  }

  @Test
  void referenceIgnoresSavedSemanticTastesButKeepsSavedExactConstraints() throws Exception {
    var session = new com.gen3.recommenderagent.domain.session.Session();
    session.setSessionId("preferences");
    session.setUserId("user");
    var preferences = new SessionPreferences();
    var tastes = new com.gen3.recommenderagent.domain.search.SemanticQuery();
    tastes.setGenres(List.of("romance"));
    preferences.setPositiveSemanticQuery(tastes);
    var exact = new ExactCriteria();
    exact.setLanguage("English");
    preferences.setMustInclude(exact);
    session.setPreferences(preferences);
    sessions.updateSession(session);
    var result =
        service.process(
            "preferences", "user", request(RecommendationHandler.ACTION, "The Hunger Games"));
    assertThat(result.getPositiveSemanticQuery()).isNull();
    assertThat(result.getMustInclude().getLanguage()).isEqualTo("English");
  }

  @Test
  void missingBookTasteReplyKeepsOriginalConstraintsAndDropsUnresolvedReference() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt())).thenReturn(List.of());
    var search = request(RecommendationHandler.ACTION, "Unknown Book");
    var exact = new ExactCriteria();
    exact.setDuration("under 5 hours");
    search.setMustInclude(exact);
    service.process("session", "user", search);
    var reply = request(RefineHandler.ACTION, null);
    reply.setStartsNewSearch(false);
    var tastes = new com.gen3.recommenderagent.domain.search.SemanticQuery();
    tastes.setGenres(List.of("fantasy"));
    reply.setPositiveSemanticQuery(tastes);
    var result = service.process("session", "user", reply);
    assertThat(result.getOutcome()).isNull();
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 5 hours");
    assertThat(result.getSimilarBook()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void missingBookAsksAboutAspectsWithoutRetrieval() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt())).thenReturn(List.of());
    var result =
        service.process("session", "user", request(RecommendationHandler.ACTION, "Unknown Book"));
    assertThat(result.getPendingQuestion()).isEqualTo(RecommendationHandler.SIMILAR_BOOK_NOT_FOUND);
    verifyNoInteractions(workflow, vectors);
  }

  @Test
  void seriesRequiresAnIndividualBookAndCancelClearsPendingSelection() throws Exception {
    var series = request(RecommendationHandler.ACTION, "The Hunger Games");
    series.setSimilarToClarificationRequired(true);
    assertThat(service.process("session", "user", series).getPendingQuestion())
        .isEqualTo(RecommendationHandler.SIMILAR_BOOK_REQUIRED);
    verifyNoInteractions(titles, vectors, workflow);
    service.process("session", "user", request(CancelHandler.ACTION, null));
    var reply = request(UnknownHandler.ACTION, null);
    reply.setRawText("2");
    reply.setStartsNewSearch(false);
    assertThat(service.process("session", "user", reply).getOutcome()).isNull();
  }
}
