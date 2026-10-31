package com.gen3.recommenderagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.catalogue.port.AudiobookTitleLookup;
import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.ConversationTestSupport;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.application.recommendation.SimilarBookResolver;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RecommendationCountFlowTest {
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
  private final ConversationResponseGenerator responses =
      new ConversationResponseGenerator(new AiResponseGenerator(null));
  private Session session = new Session();

  RecommendationCountFlowTest() {
    session.setBookCount(3);
    when(workflow.recommend(any(), any()))
        .thenReturn(
            IntStream.rangeClosed(1, 20)
                .mapToObj(
                    i -> {
                      var book = new Recommendation("book-" + i, i, 1.0, "Book " + i);
                      book.setDurationMinutes(180);
                      return book;
                    })
                .toList());
  }

  private UserIntent fantasy() {
    var intent = new UserIntent();
    intent.setIntent(ConversationAction.RECOMMENDATION);
    intent.setExplicitSearch(true);
    var query = new SemanticQuery();
    query.setGenres(List.of("fantasy"));
    intent.setPositiveSemanticQuery(query);
    return intent;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Give me 0 fantasy books", "Give me minus two fantasy books",
        "Give me 2.5 fantasy books", "Give me two and a half fantasy books"
      })
  void invalidSearchCountUsesSavedCountImmediatelyAndPreservesCriteria(String text) {
    when(ai.parse(anyString())).thenReturn(fantasy());
    var result = engine.process(session, parser.parse(text));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.RECOMMENDATIONS);
    assertThat(result.getRecommendations()).hasSize(3);
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(session.getPendingQuestion()).isNull();
    assertThat(responses.generate(result.getRecommendations(), result))
        .startsWith("Recommendation counts must be whole numbers between 1 and 5.")
        .contains("saved count of 3")
        .doesNotContain("How many");
    verify(workflow).recommend(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Update to 0 recommendations at a time", "Update to -2 recommendations at a time",
        "Update to 2.5 recommendations at a time", "change count"
      })
  void invalidSettingKeepsSavedCountAndDoesNotCreateAQuestion(String text) {
    var intent = new UserIntent();
    intent.setIntent(ConversationAction.CHANGE_COUNT);
    when(ai.parse(anyString())).thenReturn(intent);
    var result = engine.process(session, parser.parse(text));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.COUNT_UNCHANGED);
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(session.getPendingQuestion()).isNull();
    assertThat(result.getRecommendations()).isEmpty();
    assertThat(responses.generate(result.getRecommendations(), result))
        .isEqualTo(
            "Recommendation counts must be whole numbers between 1 and 5. "
                + "Please try asking again with a number in this range. "
                + "Your setting is still 3.");
    var next = engine.process(session, parser.parse("2"));
    assertThat(next.getIntent()).isEqualTo(ConversationAction.UNKNOWN);
    assertThat(session.getBookCount()).isEqualTo(3);
    org.mockito.Mockito.verifyNoInteractions(workflow);
  }

  @Test
  void oversizedSearchIsCappedWithoutChangingSavedCount() {
    var intent = fantasy();
    intent.setBookCount(BigInteger.valueOf(6));
    when(ai.parse(anyString())).thenReturn(intent);
    var result = engine.process(session, parser.parse("Give me 6 fantasy books"));
    assertThat(result.getRecommendations()).hasSize(5);
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(responses.generate(result.getRecommendations(), result))
        .startsWith("I can give up to 5 recommendations at a time.");
  }

  @Test
  void oversizedSettingSavesFiveAndValidSettingSavesRequestedCount() {
    var result = engine.process(session, parser.parse("set count to 6"));
    assertThat(session.getBookCount()).isEqualTo(5);
    assertThat(responses.generate(result.getRecommendations(), result))
        .contains("up to 5", "5 recommendations at a time");
    engine.process(session, parser.parse("set count to 2"));
    assertThat(session.getBookCount()).isEqualTo(2);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"thirty,30", "one hundred,100"})
  void wordedOversizedCountsExplainTheCapForSearchesAndSettings(String words, int count) {
    var intent = fantasy();
    intent.setBookCount(BigInteger.valueOf(count));
    when(ai.parse(anyString())).thenReturn(intent);
    var search = engine.process(session, parser.parse("Give me " + words + " fantasy books"));
    assertThat(search.getRecommendations()).hasSize(5);
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(responses.generate(search.getRecommendations(), search))
        .startsWith("I can give up to 5 recommendations at a time.");

    intent = new UserIntent();
    intent.setIntent(ConversationAction.CHANGE_COUNT);
    intent.setBookCount(BigInteger.valueOf(count));
    when(ai.parse(anyString())).thenReturn(intent);
    var setting = engine.process(session, parser.parse("set count to " + words));
    assertThat(session.getBookCount()).isEqualTo(5);
    String acknowledgement = responses.generate(setting.getRecommendations(), setting);
    assertThat(acknowledgement)
        .isEqualTo(
            "I can give up to 5 recommendations at a time. I'll show 5 recommendations at a time.");

    var json = JsonMapper.builder().build();
    session = json.readValue(json.writeValueAsString(session), Session.class);
    var repeat = engine.process(session, parser.parse("repeat"));
    assertThat(responses.generate(repeat.getRecommendations(), repeat)).isEqualTo(acknowledgement);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0 more", "-2 more", "2.5 more", "more and make it zero at a time"})
  void invalidMoreUsesSavedCountAndConsumesOnlyTheNextCachedPage(String text) {
    when(ai.parse(anyString())).thenReturn(fantasy());
    engine.process(session, parser.parse("Recommend fantasy books"));
    var result = engine.process(session, parser.parse(text));
    assertThat(result.getIntent()).isEqualTo(ConversationAction.MORE_RESULTS);
    assertThat(result.getRecommendations())
        .extracting(Recommendation::getId)
        .containsExactly("book-4", "book-5", "book-6");
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(session.getActiveSearch().getPendingRecommendations()).hasSize(14);
    assertThat(session.getPendingQuestion()).isNull();
    verify(workflow).recommend(any(), any());
  }

  @Test
  void invalidCountPreservesTheSimilarityReference() throws Exception {
    var intent = fantasy();
    intent.setSimilarToTitle("The Hobbit");
    when(ai.parse(anyString())).thenReturn(intent);
    when(titles.findBooksByTitle(anyString(), any(), anyInt()))
        .thenReturn(List.of(new AudiobookTitle("hobbit", "The Hobbit", List.of("Tolkien"))));
    when(vectors.findEmbeddingByBookId("hobbit")).thenReturn(Optional.of(new float[] {1, 0}));
    var result =
        engine.process(session, parser.parse("Give me 0 fantasy books similar to The Hobbit"));
    assertThat(result.getRecommendations()).hasSize(3);
    assertThat(result.getSimilarBook().bookId()).isEqualTo("hobbit");
    assertThat(session.getPendingQuestion()).isNull();
  }

  @Test
  void invalidCountPreservesTheShorterRefinement() {
    when(ai.parse(anyString())).thenReturn(fantasy());
    engine.process(session, parser.parse("Recommend fantasy books"));
    var intent = new UserIntent();
    intent.setIntent(ConversationAction.REFINE);
    intent.setShorter(true);
    when(ai.parse(anyString())).thenReturn(intent);
    var result = engine.process(session, parser.parse("Make these shorter and show 0 books"));
    assertThat(result.getIntent()).isEqualTo(ConversationAction.REFINE);
    assertThat(result.getRecommendations()).hasSize(3);
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 180 minutes");
    assertThat(result.getPositiveSemanticQuery().getGenres()).containsExactly("fantasy");
    assertThat(session.getPendingQuestion()).isNull();
  }

  @Test
  void oversizedMoreIsCappedWithoutChangingSavedCount() {
    when(ai.parse(anyString())).thenReturn(fantasy());
    engine.process(session, parser.parse("Recommend fantasy books"));
    var result = engine.process(session, parser.parse("6 more"));
    assertThat(result.getRecommendations()).hasSize(5);
    assertThat(session.getBookCount()).isEqualTo(3);
    assertThat(responses.generate(result.getRecommendations(), result)).contains("up to 5");
  }

  @Test
  void invalidMoreWithNoMatchesStillExplainsTheCountRule() {
    when(ai.parse(anyString())).thenReturn(fantasy());
    when(workflow.recommend(any(), any())).thenReturn(List.of());
    engine.process(session, parser.parse("Recommend fantasy books"));
    var result = engine.process(session, parser.parse("0 more"));
    assertThat(result.getDecision()).isEqualTo(ConversationDecision.NO_MORE_RESULTS);
    assertThat(responses.generate(result.getRecommendations(), result))
        .startsWith("Recommendation counts must be whole numbers between 1 and 5.");
  }

  @Test
  void invalidCountNoticeSurvivesReloadAndRepeat() {
    when(ai.parse(anyString())).thenReturn(fantasy());
    engine.process(session, parser.parse("Give me 0 fantasy books"));
    var json = JsonMapper.builder().build();
    session = json.readValue(json.writeValueAsString(session), Session.class);
    var repeat = engine.process(session, parser.parse("repeat"));
    assertThat(repeat.getRecommendations()).hasSize(3);
    assertThat(responses.generate(repeat.getRecommendations(), repeat))
        .startsWith("Recommendation counts must be whole numbers between 1 and 5.");
    assertThat(session.getPendingQuestion()).isNull();
  }
}
