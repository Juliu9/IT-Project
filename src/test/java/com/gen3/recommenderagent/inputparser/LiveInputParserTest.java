package com.gen3.recommenderagent.inputparser;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.application.ChatClientConfiguration;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;

/** Opt-in recognition checks using synthetic text and the configured parser model; no catalogue. */
@Tag("external")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_PARSER_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ActiveProfiles("live-parser-evaluation")
@SpringBootTest(
    classes = LiveInputParserTest.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiveInputParserTest {
  @Autowired private InputParser parser;

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"books from around 2000", "books from the period 2000"})
  void approximatePublicationYearsProduceAnInclusiveFiveYearRange(String text) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).as(text).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getMustInclude()).as(text).isNotNull();
    var bounds =
        new com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy()
            .filters(result, java.util.Set.of())
            .mustInclude();
    assertThat(bounds.minimumPublicationYear()).as(text).isEqualTo(1995);
    assertThat(bounds.maximumPublicationYear()).as(text).isEqualTo(2005);
    assertThat(result.getPositiveSemanticQuery()).as(text).isNull();
    assertThat(result.isPersonalised()).as(text).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "I want a book by Suzanne Collins,authors,false,Suzanne Collins",
    "I want books similar to Suzanne Collins style,authors,true,Suzanne Collins",
    "I want a book narrated by Stephen Fry,narrators,false,Stephen Fry",
    "I want books with narration similar to Stephen Fry's style,narrators,true,Stephen Fry"
  })
  void distinguishesMandatoryPeopleFromStyleSimilarity(
      String text, String role, boolean semantic, String name) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).as(text).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getSimilarToTitle()).as(text).isNullOrEmpty();
    assertThat(result.getSimilarToAuthor()).as(text).isNullOrEmpty();
    var hard = result.getMustInclude();
    var soft = result.getPositiveSemanticQuery();
    var hardNames =
        hard == null ? null : role.equals("authors") ? hard.getAuthors() : hard.getNarrators();
    var softNames =
        soft == null ? null : role.equals("authors") ? soft.getAuthors() : soft.getNarrators();
    if (semantic) {
      assertThat(softNames).as(text).containsExactly(name);
      assertThat(hardNames).as(text).isNullOrEmpty();
      assertThat(result.getMustNotInclude()).as(text).isNull();
    } else {
      assertThat(hardNames).as(text).containsExactly(name);
      assertThat(softNames).as(text).isNullOrEmpty();
    }
    assertThat(result.isPersonalised()).as(text).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "Make it horror instead,horror,true",
    "Also include mystery in that search,mystery,false"
  })
  void distinguishesReplacingAndAddingSearchGenres(String text, String genre, boolean replace) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getPositiveSemanticQuery().getGenres()).contains(genre);
    assertThat(result.getReplaceSearchFields().contains("positiveSemanticQuery.genres"))
        .isEqualTo(replace);
    assertThat(result.getPreferenceUpdates()).isNull();
  }

  @org.junit.jupiter.api.Test
  void clearingASearchFilterIsNotAPreferenceEdit() {
    var result = parser.parse("Please remove the author restriction from this search");
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getReplaceSearchFields()).containsExactly("mustInclude.authors");
    assertThat(result.getMustInclude() == null ? null : result.getMustInclude().getAuthors())
        .isNullOrEmpty();
    assertThat(result.getPreferenceUpdates()).isNull();
  }

  @org.junit.jupiter.api.Test
  void explicitPersonalisationCanAccompanyAuthorStyle() {
    var result =
        parser.parse(
            "Recommend books similar to Suzanne Collins style for me based on my reading preferences");
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.isPersonalised()).isTrue();
    assertThat(result.getPositiveSemanticQuery().getAuthors()).contains("Suzanne Collins");
    assertThat(result.getMustInclude() == null ? null : result.getMustInclude().getAuthors())
        .isNullOrEmpty();
  }

  @org.junit.jupiter.api.Test
  void explicitPeriodIncludesTheWholeFinalDecade() {
    var result = parser.parse("I want books from 1950 to the 2000s");
    var bounds =
        new com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy()
            .filters(result, java.util.Set.of())
            .mustInclude();
    assertThat(bounds.minimumPublicationYear()).isEqualTo(1950);
    assertThat(bounds.maximumPublicationYear()).isEqualTo(2009);
  }

  @ParameterizedTest
  @CsvSource({
    "Which operations do you support,HELP,,",
    "Start my session preferences and recommendation count over from scratch,RESET_PREFERENCES,,",
    "Could you explain how this service works,HELP,,",
    "the second one’s reason,EXPLAIN_RECOMMENDATION,2,",
    "the second one's reason,EXPLAIN_RECOMMENDATION,2,",
    "why the second book,EXPLAIN_RECOMMENDATION,2,",
    "why the sixth book,EXPLAIN_RECOMMENDATION,6,",
    "why these books,EXPLAIN_RECOMMENDATION,,",
    "2 more,MORE_RESULTS,,2",
    "recommend 2 fantasy audiobooks,RECOMMENDATION,,2",
    "I missed your last answer. Could you say it again,REPEAT,,",
    "Can you show me the same audiobook list you gave me earlier,REPEAT_RECOMMENDATIONS,,",
    "Show me the same books again,REPEAT_RECOMMENDATIONS,,",
    "Repeat your last explanation,REPEAT,,",
    "I have changed my mind; abandon that request,CANCEL,,"
  })
  void recognisesIntentAndSeparatesRankFromCount(
      String text, Intent intent, Integer rank, Integer count) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).as(text).isEqualTo(intent);
    assertThat(result.getReferenceRank()).as(text).isEqualTo(rank);
    assertThat(result.getBookCount()).as(text).isEqualTo(count);
    assertThat(result.getReferenceTitle()).as(text).isNullOrEmpty();
  }

  @ParameterizedTest
  @CsvSource({"under ten hours,under 10 hours", "under five hours,under 5 hours"})
  void extractsNumericDurationAndSemanticGenre(String duration, String expected) {
    var result = parser.parse("Recommend mystery audiobooks " + duration);
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getMustInclude())
        .as("duration criteria for %s; parsed=%s", duration, result)
        .isNotNull();
    assertThat(result.getMustInclude().getDuration()).isEqualTo(expected);
    assertThat(result.getPositiveSemanticQuery()).isNotNull();
    assertThat(result.getPositiveSemanticQuery().getGenres()).contains("mystery");
    assertThat(result.getPreferenceUpdates()).isNull();
  }

  @org.junit.jupiter.api.Test
  void extractsReferenceWithoutInventingThemesOrFilteringByItsAuthor() {
    var result =
        parser.parse("Books similar to The Hunger Games by Suzanne Collins under ten hours");
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getSimilarToTitle()).isEqualToIgnoringCase("The Hunger Games");
    assertThat(result.getSimilarToAuthor()).isEqualToIgnoringCase("Suzanne Collins");
    assertThat(result.getMustInclude()).isNotNull();
    assertThat(result.getMustInclude().getDuration()).isEqualTo("under 10 hours");
    assertThat(result.getMustInclude().getAuthors()).isNullOrEmpty();
    assertThat(result.getPositiveSemanticQuery()).isNull();
    assertThat(result.isPersonalised()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "a book similar to the hunger games,false",
    "books similar to The Hunger Games,false",
    "books similar to the Hunger Games series,true",
    "books similar to The Hunger Games and Divergent,true"
  })
  void distinguishesNamedBookFromExplicitSeriesOrMultipleReferences(String text, boolean clarify) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).as(text).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.isSimilarToClarificationRequired()).as(text).isEqualTo(clarify);
    if (!clarify) {
      assertThat(result.getSimilarToTitle()).as(text).isEqualToIgnoringCase("The Hunger Games");
      assertThat(result.getPositiveSemanticQuery()).as(text).isNull();
      assertThat(result.isPersonalised()).as(text).isFalse();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "make them shorter",
        "make those audiobooks shorter",
        "Can you make the previous books shorter"
      })
  void contextualShorterRequestsDoNotInventContentOrNumericLimits(String text) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).as(text).isEqualTo(Intent.REFINE);
    assertThat(result.isShorter()).as(text).isTrue();
    assertThat(result.getPositiveSemanticQuery()).as(text).isNull();
    assertThat(result.getNegativeSemanticQuery()).as(text).isNull();
    assertThat(result.getMustInclude()).as(text).isNull();
    assertThat(result.getMustNotInclude()).as(text).isNull();
    assertThat(result.getPreferenceUpdates()).as(text).isNull();
    assertThat(result.isPersonalised()).as(text).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "Recommend fantasy audiobooks with a strong female protagonist.,",
    "Recommend fantasy audiobooks by Suzanne Collins with a strong female protagonist.,Suzanne Collins"
  })
  void characterTraitsStaySemanticWhileNamedAuthorsRemainExact(String text, String author) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).as(text).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getPositiveSemanticQuery()).as(text).isNotNull();
    assertThat(result.getPositiveSemanticQuery().getGenres()).contains("fantasy");
    var themes = new java.util.ArrayList<String>();
    if (result.getPositiveSemanticQuery().getKeywords() != null) {
      themes.addAll(result.getPositiveSemanticQuery().getKeywords());
    }
    if (result.getPositiveSemanticQuery().getTopics() != null) {
      themes.addAll(result.getPositiveSemanticQuery().getTopics());
    }
    assertThat(String.join(" ", themes).toLowerCase(java.util.Locale.ROOT)).contains("female");
    if (author == null) {
      assertThat(
              result.getMustInclude() == null
                  || result.getMustInclude().getAuthors() == null
                      && result.getMustInclude().getNarrators() == null
                      && result.getMustInclude().getDuration() == null)
          .as("character traits must not become exact catalogue filters")
          .isTrue();
    } else {
      assertThat(result.getMustInclude()).isNotNull();
      assertThat(result.getMustInclude().getAuthors()).containsExactly(author);
    }
    assertThat(result.getPositiveSemanticQuery().getAuthors()).isNullOrEmpty();
    assertThat(result.getPositiveSemanticQuery().getNarrators()).isNullOrEmpty();
  }

  @org.junit.jupiter.api.Test
  void personalisedRequestKeepsExplicitAuthorConstraint() {
    var result = parser.parse("give me some books I would like by suzanne collins");
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.isPersonalised()).isTrue();
    assertThat(result.getMustInclude()).isNotNull();
    assertThat(result.getMustInclude().getAuthors()).hasSize(1);
    assertThat(result.getMustInclude().getAuthors().getFirst())
        .isEqualToIgnoringCase("Suzanne Collins");
  }

  @org.junit.jupiter.api.Test
  void bareHorrorIsATasteReplyRatherThanAnExplicitNewSearch() {
    var result = parser.parse("horror");
    assertThat(result.getIntent()).isIn(Intent.RECOMMENDATION, Intent.REFINE);
    assertThat(result.isExplicitSearch()).isFalse();
    assertThat(result.getPositiveSemanticQuery()).isNotNull();
    assertThat(result.getPositiveSemanticQuery().getGenres()).contains("horror");
  }

  @ParameterizedTest
  @CsvSource({"why the last book,last", "why the former,former", "why the latter,latter"})
  void relativeTargetsRemainContextual(String text, String selector) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).isEqualTo(Intent.EXPLAIN_RECOMMENDATION);
    assertThat(result.getReferenceRank()).isNull();
    assertThat(result.getExplanationSelection().include().getFirst()).contains(selector);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "Give me zero mystery books",
        "Give me minus two mystery books",
        "Give me 2.5 mystery books",
        "I want two and a half mystery books"
      })
  void invalidCountsKeepSearchCriteriaForClarification(String text) {
    var result = parser.parse(text);
    assertThat(result.isInvalidBookCount()).isTrue();
    assertThat(result.getBookCount()).isNull();
    assertThat(result.getIntent()).isEqualTo(Intent.RECOMMENDATION);
    assertThat(result.getPositiveSemanticQuery()).as(text).isNotNull();
    assertThat(result.getPositiveSemanticQuery().getGenres()).as(text).contains("mystery");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"the same but less violent", "Please adjust those to be less violent"})
  void negativeComparisonsAreNotDropped(String text) {
    var result = parser.parse(text);
    assertThat(result.getIntent()).isEqualTo(Intent.REFINE);
    assertThat(result.getNegativeSemanticQuery()).isNotNull();
    assertThat(result.getNegativeSemanticQuery().hasValues()).isTrue();
  }

  @org.junit.jupiter.api.Test
  void multiTitleExplanationUsesListsAndPreservesEachTitle() {
    var result = parser.parse("Explain The Hobbit and Dune");
    assertThat(result.getIntent()).isEqualTo(Intent.EXPLAIN_RECOMMENDATION);
    assertThat(result.getExplanationSelection().include())
        .containsExactlyInAnyOrder("The Hobbit", "Dune");
    result = parser.parse("Explain all except The Hobbit and Dune");
    assertThat(result.getExplanationSelection().include()).isEmpty();
    assertThat(result.getExplanationSelection().exclude())
        .containsExactlyInAnyOrder("The Hobbit", "Dune");
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("live-parser-evaluation")
  @EnableAutoConfiguration
  @Import({ChatClientConfiguration.class, AiParser.class, InputParser.class, CommandParser.class})
  static class Config {
    @Bean
    AiOperationObserver aiOperationObserver() {
      return AiOperationObserver.noop();
    }
  }
}
