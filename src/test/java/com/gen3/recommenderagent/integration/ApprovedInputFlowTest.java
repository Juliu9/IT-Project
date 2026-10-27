package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.catalogue.port.AudiobookTitleLookup;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.application.recommendation.SimilarBookResolver;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.ExplanationSelection;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.ParsedRequest;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Exercises approved input recovery across the production dispatcher, planner and cache shape. */
class ApprovedInputFlowTest {
  private final AiParser ai = mock(AiParser.class);
  private final InputParser parser = new InputParser(new CommandParser(), ai);
  private final RecommendationWorkflow workflow = mock(RecommendationWorkflow.class);
  private final AudiobookTitleLookup titles = mock(AudiobookTitleLookup.class);
  private final AudiobookVectorRepository vectors = mock(AudiobookVectorRepository.class);
  private final ConversationEngine engine =
      new ConversationEngine(
          ConversationTestSupport.dispatcher(),
          new RecommendationPager(workflow),
          new ExactFilterPolicy(),
          new SimilarBookResolver(titles, vectors));
  private final AiResponseGenerator narration =
      mock(AiResponseGenerator.class, invocation -> "Here are your books.");
  private final ConversationResponseGenerator responses =
      new ConversationResponseGenerator(narration);
  private Session session = new Session();
  private Integer duration = 180;
  private Integer year = 2000;
  private int batch;

  ApprovedInputFlowTest() {
    session.setSessionId("test");
    session.setUserId("test");
    when(workflow.recommend(any(), any()))
        .thenAnswer(
            call -> {
              com.gen3.recommenderagent.domain.session.ConversationDraft input =
                  call.getArgument(0);
              if (input.isPersonalised()
                  && input.getPositiveSemanticQuery() == null
                  && input.getNegativeSemanticQuery() == null
                  && session.getPreferences() == null) {
                input.setPreferenceClarificationRequired(true);
                return List.of();
              }
              int generation = ++batch;
              return IntStream.rangeClosed(1, 20)
                  .mapToObj(
                      rank -> {
                        var book =
                            new Recommendation(
                                "book-" + generation + "-" + rank, rank, 1.0, "Book " + rank);
                        book.setDurationMinutes(duration == null ? null : duration + rank);
                        book.setPublicationYear(year);
                        return book;
                      })
                  .toList();
            });
  }

  private SessionRequest request(Intent intent, String raw) {
    var request = new SessionRequest();
    request.setIntent(intent);
    request.setRawText(raw);
    return request;
  }

  private ConversationTurn process(String text) {
    return engine.process(session, parser.parse(text));
  }

  private ConversationTurn search() {
    var request = request(Intent.RECOMMENDATION, "fantasy");
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    request.setPositiveSemanticQuery(query);
    return engine.process(session, request);
  }

  private void reload() {
    var json = new ObjectMapper();
    session = json.readValue(json.writeValueAsString(session), Session.class);
  }

  private ConversationTurn selection(List<String> include, List<String> exclude) {
    var request = request(Intent.EXPLAIN_RECOMMENDATION, "explain selection");
    request.setExplanationSelection(new ExplanationSelection(include, exclude));
    return engine.process(session, request);
  }

  @Test
  void resetAbandonsPendingRefinementAcrossReload() {
    var old = new SessionPreferences();
    var author = new ExactCriteria();
    author.setAuthors(List.of("Old Writer"));
    old.setMustInclude(author);
    session.setPreferences(old);
    year = null;
    search();
    var refine = request(Intent.REFINE, "more recent under two hours");
    var exact = new ExactCriteria();
    exact.setPublicationYear("more recent");
    exact.setDuration("under 2 hours");
    refine.setMustInclude(exact);
    engine.process(session, refine);
    process("reset preferences");
    var json = new ObjectMapper();
    session =
        json.readValue(
            json.writeValueAsString(session)
                .replace("pendingRefinementRequest", "pendingRecencyRequest"),
            Session.class);
    var result = process("2000");
    assertThat(session.getPendingQuestion()).isNull();
    assertThat(result.getIntent()).isEqualTo(Intent.UNKNOWN);
    assertThat(result.getRecommendations()).isEmpty();
  }

  @Test
  void rejectingDurationExtensionKeepsTheOriginalSearchAndQueuedPage() {
    duration = 50;
    var original = request(Intent.RECOMMENDATION, "under 55 minutes");
    var exact = new ExactCriteria();
    exact.setDuration("under 55 minutes");
    original.setMustInclude(exact);
    engine.process(session, original);
    process("make them longer");
    process("no");
    assertThat(session.getPendingRefinementRequest()).isNull();
    var more = process("more");
    assertThat(more.getMustInclude().getDuration()).isEqualTo("under 55 minutes");
    assertThat(more.getRecommendations().getFirst().getTitle()).isEqualTo("Book 6");
  }

  @Test
  void startingYearReplyPreservesTheEntirePendingRefinementThroughHelpAndReload() {
    year = null;
    search();
    var refine = request(Intent.REFINE, "more recent and under two hours by Suzanne Collins");
    var exact = new ExactCriteria();
    exact.setPublicationYear("more recent");
    exact.setDuration("under 2 hours");
    exact.setAuthors(List.of("Suzanne Collins"));
    refine.setMustInclude(exact);
    assertThat(engine.process(session, refine).getDecision())
        .isEqualTo(ConversationDecision.RECENCY_YEAR_CLARIFICATION);
    process("help");
    reload();
    var result = process("2000");
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 2 hours");
    assertThat(result.getMustInclude().getAuthors()).containsExactly("Suzanne Collins");
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("from 2000");
    assertThat(result.getBookCount()).isEqualTo(5);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void durationReplyPreservesPendingAuthorAndGenre(boolean longer) {
    duration = null;
    search();
    var refine = request(Intent.REFINE, "change runtime and author");
    refine.setLonger(longer);
    refine.setShorter(!longer);
    var exact = new ExactCriteria();
    exact.setAuthors(List.of("Suzanne Collins"));
    refine.setMustInclude(exact);
    var genre = new SemanticQuery();
    genre.setGenres(List.of("horror"));
    refine.setPositiveSemanticQuery(genre);
    refine.setReplaceSearchFields(List.of("positiveSemanticQuery.genres"));
    assertThat(engine.process(session, refine).getDecision())
        .isEqualTo(
            longer
                ? ConversationDecision.LONGER_DURATION_CLARIFICATION
                : ConversationDecision.SHORTER_DURATION_CLARIFICATION);
    process("help");
    reload();
    var answer = request(Intent.RECOMMENDATION, "under three hours");
    exact = new ExactCriteria();
    exact.setDuration("under 3 hours");
    answer.setMustInclude(exact);
    var result = engine.process(session, answer);
    assertThat(result.getMustInclude().getAuthors()).containsExactly("Suzanne Collins");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 3 hours");
  }

  @Test
  void olderUsesAverageYearAndPreservesTheLowerBoundUntilExtensionIsApproved() {
    year = 2000;
    var original = request(Intent.RECOMMENDATION, "fantasy from 2000");
    var exact = new ExactCriteria();
    exact.setPublicationYear("from 2000");
    original.setMustInclude(exact);
    engine.process(session, original);
    assertThat(process("older ones").getDecision())
        .isEqualTo(ConversationDecision.RECENCY_RANGE_CONFIRMATION);
    process("help");
    reload();
    var result = process("yes");
    assertThat(result.getMustInclude().getPublicationYear()).isEqualTo("before 2000");
    assertThat(result.getRecommendations()).hasSize(5);
  }

  @Test
  void missingYearsForOlderAskForAnEndingYear() {
    year = null;
    search();
    var question = process("older ones");
    assertThat(responses.generate(question.getRecommendations(), question)).contains("ending year");
    var help = process("help");
    assertThat(responses.generate(help.getRecommendations(), help)).contains("ending year");
    var repeat = process("repeat");
    assertThat(responses.generate(repeat.getRecommendations(), repeat)).contains("ending year");
    reload();
    assertThat(process("1990").getMustInclude().getPublicationYear()).isEqualTo("at most 1990");
  }

  @Test
  void longerUsesLongestDisplayedRuntimeAndConflictingUpperBoundNeedsConfirmation() {
    duration = 50;
    var original = request(Intent.RECOMMENDATION, "under 55 minutes");
    var exact = new ExactCriteria();
    exact.setDuration("under 55 minutes");
    original.setMustInclude(exact);
    engine.process(session, original);
    assertThat(process("make them longer").getDecision())
        .isEqualTo(ConversationDecision.DURATION_RANGE_CONFIRMATION);
    reload();
    process("help");
    var result = process("yes");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("at least 56 minutes");
    assertThat(session.getPendingRefinementRequest()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "why the last book,5",
    "why the former,1",
    "why the latter,5",
    "explain the last one,5"
  })
  void completeRelativeExplanationQuestionsNeverGuessARank(String text, int rank) {
    search();
    var result = process(text);
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(rank);
    verifyNoInteractions(ai);
  }

  @Test
  void explicitRemovalsReachPositiveAndNegativeSavedFieldsWithoutAddingDislikes() {
    search();
    var preferences = new SessionPreferences();
    var positive = new SemanticQuery();
    positive.setGenres(List.of("romance", "horror", "fantasy"));
    positive.setAuthors(List.of("Collins, Suzanne."));
    var negative = new SemanticQuery();
    negative.setGenres(List.of("romance", "horror"));
    preferences.setPositiveSemanticQuery(positive);
    preferences.setNegativeSemanticQuery(negative);
    var exact = new ExactCriteria();
    exact.setAuthors(List.of("Collins, Suzanne."));
    preferences.setMustInclude(exact);
    session.setPreferences(preferences);
    process("remove romance from my preferences");
    process("I no longer like horror");
    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("fantasy");
    assertThat(session.getPreferences().getNegativeSemanticQuery().getGenres())
        .containsExactly("horror");
    process("remove Suzanne Collins from my preferences");
    assertThat(session.getPreferences().getPositiveSemanticQuery().getAuthors()).isNull();
    assertThat(session.getPreferences().getMustInclude().getAuthors()).isNull();
    assertThat(session.getPendingRecommendations()).hasSize(15);
    verifyNoInteractions(ai);
  }

  @Test
  void negativeSearchesPreservePositiveSearchAndNeverUseExactGenreFields() {
    search();
    var result = process("the same but less violent");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(result.getNegativeSemanticQuery().getKeywords()).containsExactly("violent");
    result = process("only books without romance");
    assertThat(result.getNegativeSemanticQuery().getGenres()).containsExactly("romance");
    verifyNoInteractions(ai);
  }

  @Test
  void subsetsUseDisplayedOrderDeduplicateAndLetExclusionsWin() {
    search();
    var result = selection(List.of("4", "2", "2"), List.of("4"));
    assertThat(result.getRecommendations()).extracting(Recommendation::getRank).containsExactly(2);
    result = selection(List.of("Book 5", "Book 1"), List.of());
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(1, 5);
    result = selection(List.of(), List.of("Book 1", "Book 2"));
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(3, 4, 5);
  }

  @Test
  void unresolvedSubsetRetainsOtherSelectionsAcrossHelpAndReload() {
    search();
    var question = selection(List.of("Book", "99"), List.of("Book 5"));
    assertThat(question.getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    assertThat(responses.generate(question.getRecommendations(), question))
        .contains("identify Book");
    var help = process("help");
    assertThat(responses.generate(help.getRecommendations(), help)).contains("identify Book");
    reload();
    var nextQuestion = process("2");
    assertThat(nextQuestion.getDecision())
        .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
    assertThat(responses.generate(nextQuestion.getRecommendations(), nextQuestion))
        .contains("identify 99");
    var result = process("4");
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(2, 4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"explain all except all", "explain all but every displayed book"})
  void excludingTheEntirePageProducesAnEmptySelection(String text) {
    search();
    var result = process(text);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.EMPTY_EXPLANATION_SELECTION);
    assertThat(result.getRecommendations()).isEmpty();
    verifyNoInteractions(ai, narration);
  }

  @Test
  void emptySubsetReturnsFriendlyResponseAndCanSelectAgainWithoutAi() {
    search();
    var empty = selection(List.of("2"), List.of("2"));
    assertThat(empty.getDecision()).isEqualTo(ConversationDecision.EMPTY_EXPLANATION_SELECTION);
    assertThat(responses.generate(empty.getRecommendations(), empty))
        .contains("aren't any books left");
    verifyNoInteractions(narration);
    assertThat(process("4").getRecommendations())
        .extracting(Recommendation::getRank)
        .containsExactly(4);
  }

  @Test
  void aNewExplanationReplacesAPendingSubsetAndMoreClearsIt() {
    search();
    selection(List.of("99"), List.of("1"));
    assertThat(process("explain all").getRecommendations()).hasSize(5);
    selection(List.of("99"), List.of());
    process("more");
    assertThat(session.getPendingExplanation()).isNull();
    assertThat(process("2").getDecision()).isEqualTo(ConversationDecision.UNKNOWN);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Give me zero mystery books",
        "Give me minus two mystery books",
        "I want two and a half mystery books",
        "Give me 2.5 mystery books"
      })
  void invalidCountsDoNotRetrieveAndTheCorrectionResumesTheOriginalSearch(String text) {
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.RECOMMENDATION);
    parsed.setInvalidBookCount(true);
    parsed.setBookCount(BigInteger.ONE);
    var query = new SemanticQuery();
    query.setGenres(List.of("mystery"));
    parsed.setPositiveSemanticQuery(query);
    when(ai.parse(anyString())).thenReturn(parsed);
    search();
    int calls = batch;
    var question = process(text);
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    assertThat(batch).isEqualTo(calls);
    assertThat(session.getPendingRecommendations()).hasSize(15);
    assertThat(responses.generate(question.getRecommendations(), question))
        .contains("whole number from one to five");
    process("help");
    reload();
    var result = process("3");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("mystery");
    assertThat(result.getRecommendations()).hasSize(3);
    assertThat(session.getBookCount()).isEqualTo(5);
  }

  @Test
  void invalidMoreResumesWithATemporaryCountAndInvalidCountRepliesCannotBeRounded() {
    search();
    assertThat(process("0 more").getDecision()).isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    assertThat(process("2.5").getDecision()).isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    var result = process("2");
    assertThat(result.getIntent()).isEqualTo(Intent.MORE_RESULTS);
    assertThat(result.getRecommendations()).hasSize(2);
    assertThat(session.getBookCount()).isEqualTo(5);
  }

  @Test
  void correctingAnInvalidCountSettingDoesNotRestoreReferenceSelection() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("a", "Reference", List.of("One")),
                new AudiobookTitle("b", "Reference", List.of("Two"))));
    when(vectors.findEmbeddingByBookId(anyString())).thenReturn(Optional.of(new float[] {1, 0}));
    var search = request(Intent.RECOMMENDATION, "similar to Reference");
    search.setSimilarToTitle("Reference");
    search.setExplicitSearch(true);
    engine.process(session, search);
    var invalid = request(Intent.CHANGE_COUNT, "set count to zero");
    invalid.setInvalidBookCount(true);
    engine.process(session, invalid);
    reload();
    var count = process("3");
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(responses.generate(count.getRecommendations(), count))
        .contains("3 recommendations")
        .doesNotContain("Which book");
    assertThat(process("2").getIntent()).isEqualTo(Intent.UNKNOWN);
    assertThat(session.getPendingQuestion()).isNull();
  }

  private void referenceChoices() throws Exception {
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(
            List.of(
                new AudiobookTitle("a", "Reference", List.of("One")),
                new AudiobookTitle("b", "Reference", List.of("Two"))));
    when(vectors.findEmbeddingByBookId(anyString())).thenReturn(Optional.of(new float[] {1, 0}));
    var input = request(Intent.RECOMMENDATION, "similar to Reference under two hours");
    input.setSimilarToTitle("Reference");
    input.setBookCount(2);
    input.setExplicitSearch(true);
    var criteria = new ExactCriteria();
    criteria.setDuration("under 2 hours");
    input.setMustInclude(criteria);
    engine.process(session, input);
  }

  @Test
  void rejectingChoicesWithAReplacementTitlePreservesTheSearch() throws Exception {
    referenceChoices();
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(List.of(new AudiobookTitle("hobbit", "The Hobbit", List.of("Tolkien"))));
    reload();
    var result = process("None of those, try The Hobbit");
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hobbit");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 2 hours");
    assertThat(result.getRecommendations()).hasSize(2);
    verifyNoInteractions(ai);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Not now", "Leave it", "Never mind", "No, thanks"})
  void decliningAReferenceQuestionPreservesThePreviousPage(String reply) throws Exception {
    var displayed = search();
    referenceChoices();
    var result = process(reply);
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.CANCELLED);
    assertThat(session.getPendingSimilarBook()).isNull();
    assertThat(process("repeat the recommendations").getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactlyElementsOf(
            displayed.getRecommendations().stream().map(Recommendation::getBookId).toList());
    verifyNoInteractions(ai);
  }

  @Test
  void firstSearchKeepsTheMoreCountAcrossHelpAndReload() {
    var question = process("2 more");
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.SEARCH_CLARIFICATION);
    verifyNoInteractions(workflow);
    process("help");
    reload();
    var taste = request(Intent.RECOMMENDATION, "fantasy");
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    taste.setPositiveSemanticQuery(query);
    var result = engine.process(session, taste);
    assertThat(result.getRecommendations()).hasSize(2);
    assertThat(session.getBookCount()).isEqualTo(5);
  }

  @ParameterizedTest
  @ValueSource(strings = {"More please and make it three at a time", "More, three per page"})
  void combinedMoreCountIsTemporary(String text) {
    search();
    var result = process(text);
    assertThat(result.getIntent()).isEqualTo(Intent.MORE_RESULTS);
    assertThat(result.getRecommendations()).hasSize(3);
    assertThat(session.getBookCount()).isEqualTo(5);
    assertThat(process("more").getRecommendations()).hasSize(5);
    process("change count to three books");
    assertThat(session.getBookCount()).isEqualTo(3);
    verifyNoInteractions(ai);
  }

  @ParameterizedTest
  @ValueSource(strings = {"more", "repeat the recommendations"})
  void switchingActionsDiscardsTheAbandonedCountRequest(String action) {
    search();
    var invalid = request(Intent.RECOMMENDATION, "zero mystery books");
    invalid.setInvalidBookCount(true);
    invalid.setExplicitSearch(true);
    var query = new SemanticQuery();
    query.setGenres(List.of("mystery"));
    invalid.setPositiveSemanticQuery(query);
    assertThat(engine.process(session, invalid).getDecision())
        .isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    process(action);
    assertThat(session.getPendingCountRequest()).isNull();
    assertThat(process("0 more").getDecision()).isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    process("help");
    process("repeat");
    reload();
    var more = process("2");
    assertThat(more.getIntent()).isEqualTo(Intent.MORE_RESULTS);
    assertThat(more.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(more.getRecommendations()).hasSize(2);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "no matches|no",
        "no matches|no thanks",
        "no matches|not now",
        "no more|no thanks",
        "empty explanation|no thanks"
      })
  void decliningASearchOfferKeepsPreferencesAndSearchHistory(String outcome, String reply) {
    org.mockito.Mockito.doReturn(List.of()).when(workflow).recommend(any(), any());
    search();
    if (outcome.equals("no more")) {
      process("more");
    } else if (outcome.equals("empty explanation")) {
      process("explain");
    }
    var preferences = new SessionPreferences();
    var tastes = new SemanticQuery();
    tastes.setGenres(List.of("horror"));
    preferences.setPositiveSemanticQuery(tastes);
    session.setPreferences(preferences);
    var before =
        com.gen3.recommenderagent.domain.conversation.ConversationHistory.latestDisplayedSearch(
            session);
    org.mockito.Mockito.clearInvocations(workflow);
    process("help");
    process("repeat");
    reload();
    assertThat(session.getPendingDecision()).isEqualTo(ConversationDecision.SEARCH_CHANGE_OFFER);
    var declined = process(reply);
    assertThat(declined.getDecision()).isEqualTo(ConversationDecision.SEARCH_DECLINED);
    assertThat(responses.generate(declined.getRecommendations(), declined))
        .contains("No problem.", "try another search");
    assertThat(session.getPreferences().getPositiveSemanticQuery().getGenres())
        .containsExactly("horror");
    var after =
        com.gen3.recommenderagent.domain.conversation.ConversationHistory.latestDisplayedSearch(
            session);
    assertThat(after.getRequestId()).isEqualTo(before.getRequestId());
    assertThat(after.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(process("repeat").getDecision()).isEqualTo(ConversationDecision.SEARCH_DECLINED);
    verifyNoInteractions(workflow);
  }

  @Test
  void repeatingNoMatchesUsesTheSavedOutcomeWithoutSearchingForRepeat() {
    org.mockito.Mockito.doReturn(List.of()).when(workflow).recommend(any(), any());
    search();
    org.mockito.Mockito.clearInvocations(workflow);
    reload();
    var repeated = process("repeat");
    assertThat(repeated.getIntent()).isEqualTo(Intent.REPEAT);
    assertThat(repeated.getDecision()).isEqualTo(ConversationDecision.NO_MATCHES);
    assertThat(repeated.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(responses.generate(repeated.getRecommendations(), repeated))
        .contains("matching those search criteria")
        .doesNotContain("match for repeat");
    verifyNoInteractions(workflow);
  }

  @ParameterizedTest
  @CsvSource({
    "no matches,false",
    "no matches,true",
    "no more,false",
    "no more,true",
    "empty explanation,false",
    "empty explanation,true"
  })
  void acceptingNoMatchRecoveryAsksForChangesWithoutRetrieval(String outcome, boolean interrupted) {
    org.mockito.Mockito.doReturn(List.of()).when(workflow).recommend(any(), any());
    search();
    if (outcome.equals("no more")) {
      process("more");
    } else if (outcome.equals("empty explanation")) {
      process("explain");
    }
    org.mockito.Mockito.clearInvocations(workflow);
    if (interrupted) {
      var help = process("help");
      assertThat(responses.generate(help.getRecommendations(), help))
          .contains("Would you like to try a different author, genre, or filter?");
      process("repeat");
      reload();
    }
    var before = session.getRequests().getLast();
    var question = process("yes");
    assertThat(question.getDecision()).isEqualTo(ConversationDecision.SEARCH_CHANGE_CLARIFICATION);
    assertThat(responses.generate(question.getRecommendations(), question))
        .contains("What would you like to change");
    assertThat(session.getRequests().getLast().getRequestId()).isEqualTo(before.getRequestId());
    verifyNoInteractions(workflow);
    process("help");
    reload();
    var change = request(Intent.REFINE, "under two hours");
    var criteria = new ExactCriteria();
    criteria.setDuration("under 2 hours");
    change.setMustInclude(criteria);
    var result = engine.process(session, change);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 2 hours");
    org.mockito.Mockito.verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"explain", "repeat the recommendations"})
  void newPageActionsAbandonPersonalisationButKeepTheDisplayedPage(String action) {
    var displayed = search();
    var personalised = request(Intent.RECOMMENDATION, "personalised for me");
    personalised.setPersonalised(true);
    personalised.setExplicitSearch(true);
    var question = engine.process(session, personalised);
    assertThat(question.isPreferenceClarificationRequired()).isTrue();
    reload();
    if (action.equals("explain")) {
      assertThat(selection(List.of("99"), List.of()).getDecision())
          .isEqualTo(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
      var chosen = process("2");
      assertThat(chosen.getRecommendations())
          .extracting(Recommendation::getRank)
          .containsExactly(2);
      assertThat(session.isAwaitingPersonalisedPreferences()).isFalse();
    }
    var result = process(action);
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getBookId)
        .containsExactlyElementsOf(
            displayed.getRecommendations().stream().map(Recommendation::getBookId).toList());
    assertThat(responses.generate(result.getRecommendations(), result))
        .doesNotContain("To continue your personalised request");
    assertThat(session.isAwaitingPersonalisedPreferences()).isFalse();
    var answer = request(Intent.RECOMMENDATION, "horror");
    var query = new SemanticQuery();
    query.setGenres(List.of("horror"));
    answer.setPositiveSemanticQuery(query);
    var resumed = engine.process(session, answer);
    assertThat(resumed.isPersonalised()).isFalse();
    assertThat(resumed.getPositiveSemanticQuery().getGenres()).containsExactly("horror");
    assertThat(session.isAwaitingPersonalisedPreferences()).isFalse();
  }

  @Test
  void invalidCombinedMoreCountStillResumesWithATemporaryCount() {
    search();
    assertThat(process("more and make it zero at a time").getDecision())
        .isEqualTo(ConversationDecision.COUNT_CLARIFICATION);
    assertThat(process("2").getRecommendations()).hasSize(2);
    assertThat(session.getBookCount()).isEqualTo(5);
  }

  @ParameterizedTest
  @ValueSource(strings = {"books from around 2000", "books from the period 2000"})
  void completeApproximateYearSearchDoesNotInheritThePreviousGenre(String text) {
    search();
    var result = process(text);
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getPositiveSemanticQuery()).isNull();
    assertThat(result.getRecommendations()).hasSize(5);
    var bounds =
        new ExactFilterPolicy().filters(result.toDraft(), java.util.Set.of()).mustInclude();
    assertThat(bounds.minimumPublicationYear()).isEqualTo(1995);
    assertThat(bounds.maximumPublicationYear()).isEqualTo(2005);
    assertThat(batch).isEqualTo(2);
    verifyNoInteractions(ai);
  }
}
