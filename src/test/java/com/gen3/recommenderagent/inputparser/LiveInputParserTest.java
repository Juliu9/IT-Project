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
  @CsvSource({
    "Which operations do you support,HELP,,",
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
    assertThat(result.getMustInclude()).isNotNull();
    assertThat(result.getMustInclude().getDuration()).isEqualTo(expected);
    assertThat(result.getPositiveSemanticQuery()).isNotNull();
    assertThat(result.getPositiveSemanticQuery().getGenres()).contains("mystery");
    assertThat(result.getPreferenceUpdates()).isNull();
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
