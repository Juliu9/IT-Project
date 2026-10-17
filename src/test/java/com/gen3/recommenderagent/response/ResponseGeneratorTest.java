package com.gen3.recommenderagent.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseGeneratorTest {

  @Test
  void shouldBuildAHumanReadableRecommendationMessage() {
    AiResponseGenerator generator = new AiResponseGenerator(null);

    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("Recommend me an action audiobook");

    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setGenres(List.of("action", "sci-fi"));
    request.setPositiveSemanticQuery(semanticQuery);

    request.setBookCount(3);

    List<Recommendation> recommendations =
        List.of(
            new Recommendation("book-101", 1, 0.91, "book1"),
            new Recommendation("book-202", 2, 0.84, "book2"));

    String response = generator.generate(recommendations, request);

    assertNotNull(response);
    assertTrue(response.toLowerCase().contains("action"));
    assertTrue(response.toLowerCase().contains("book-101"));
    assertTrue(response.toLowerCase().contains("recommend"));
  }

  @Test
  void shouldIncludeStoredShortDescriptionsInFallbackResponses() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);

    String response =
        generator.generate(
            List.of(
                new Recommendation(
                    "book-101",
                    1,
                    0.91,
                    "Dune",
                    "A young noble navigates danger and destiny on a desert planet.")),
            request);

    assertTrue(response.contains("A young noble navigates danger and destiny on a desert planet."));
  }

  @Test
  void shouldExplainTheLimitAndReturnFiveRecommendationsWhenCountIsExplicit() {
    AiResponseGenerator generator = new AiResponseGenerator(null);

    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("Give me 10 recommendations");

    request.setBookCount(10);

    List<Recommendation> recommendations =
        List.of(
            new Recommendation("book-101", 1, 0.91, "Book1"),
            new Recommendation("book-202", 2, 0.84, "Book2"),
            new Recommendation("book-303", 3, 0.80, "Book3"),
            new Recommendation("book-404", 4, 0.76, "Book4"),
            new Recommendation("book-505", 5, 0.72, "Book5"),
            new Recommendation("book-606", 6, 0.68, "Book6"));

    String response = generator.generate(recommendations, request);

    assertNotNull(response);
    assertTrue(response.contains("up to 5"));
    assertTrue(response.contains("Book1"));
    assertTrue(response.contains("Book5"));
    assertFalse(response.contains("Book6"));
  }

  @Test
  void shouldIgnoreAnOversizedCountNotStatedByTheUser() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("fantasy");
    request.setBookCount(10);

    String response =
        generator.generate(List.of(new Recommendation("book-101", 1, 0.91, "Book1")), request);

    assertTrue(response.contains("Book1"));
    assertFalse(response.contains("up to 5"));
  }

  @Test
  void shouldRecognizeAnExplicitSpelledOutCount() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("Give me ten fantasy audiobooks");
    request.setBookCount(10);

    String response =
        generator.generate(List.of(new Recommendation("book-101", 1, 0.91, "Book1")), request);

    assertTrue(response.contains("up to 5"));
    assertTrue(response.contains("Book1"));
  }

  @Test
  void shouldGiveTheAiTheLimitNoticeAndActualRecommendationCount() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("Give me 10 recommendations");
    request.setBookCount(10);

    String prompt =
        generator.compilePrompt(
            List.of(new Recommendation("book-101", 1, 0.91, "Book1")),
            request,
            "I can give up to 5 recommendations at a time. ");

    assertTrue(prompt.contains("Recommendation count: 1"));
    assertTrue(prompt.contains("Limit notice: I can give up to 5 recommendations at a time."));
  }

  @Test
  void shouldReturnFriendlyFallbackWhenNoDataIsAvailable() {
    AiResponseGenerator generator = new AiResponseGenerator(null);

    String response = generator.generate(null, null);

    assertNotNull(response);
    assertTrue(response.toLowerCase().contains("try again"));
  }

  @Test
  void shouldAcknowledgeChangingTheRecommendationCount() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.CHANGE_COUNT);
    request.setBookCount(3);

    assertEquals("I'll show 3 recommendations at a time.", generator.generate(List.of(), request));
  }

  @Test
  void shouldCapAnExplicitChangeCountRequest() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.CHANGE_COUNT);
    request.setRawText("Show me 10 recommendations at a time");
    request.setBookCount(10);

    assertEquals(
        "I can give up to 5 recommendations at a time. I'll show 5 recommendations at a time.",
        generator.generate(List.of(), request));
  }

  @Test
  void shouldAcknowledgeUpdatingSessionPreferences() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.UPDATE_PREFERENCES);

    String response = generator.generate(List.of(), request);

    assertTrue(response.toLowerCase().contains("updated your preferences"));
    assertTrue(response.toLowerCase().contains("session"));
  }

  @Test
  void shouldExplainSupportedActionsForHelpIntent() {
    AiResponseGenerator generator = new AiResponseGenerator(null);
    SessionRequest request = new SessionRequest();
    request.setIntent(Intent.HELP);

    String response = generator.generate(List.of(), request);

    assertTrue(response.toLowerCase().contains("refine"));
    assertTrue(response.toLowerCase().contains("more results"));
    assertTrue(response.toLowerCase().contains("clear"));
  }
}
