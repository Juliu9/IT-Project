package com.gen3.recommenderagent.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseGeneratorTest {

  @Test
  void usesTheResolvedDecisionWithoutReinterpretingIntent() {
    var request = new ConversationDraft();
    request.setIntent(Intent.HELP);
    request.setDecision(
        com.gen3.recommenderagent.domain.session.ConversationDecision.NO_MORE_RESULTS);
    var ai = org.mockito.Mockito.mock(AiResponseGenerator.class);
    var generator = new ConversationResponseGenerator(ai);
    assertEquals(
        "There are no more audiobooks matching your current search. Try changing your criteria.",
        generator.generate(List.of(), ConversationTurn.fromDraft(request)));
    org.mockito.Mockito.verifyNoInteractions(ai);
  }

  @Test
  void emptyMoreAndItsRepeatExplicitlySayThereAreNoMoreMatches() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.MORE_RESULTS);
    String more = generator.generate(List.of(), ConversationTurn.fromDraft(request));
    assertTrue(more.startsWith("There are no more audiobooks matching your current search."));
    request.setIntent(Intent.REPEAT);
    request.setNoMoreRecommendations(true);
    assertEquals(more, generator.generate(List.of(), ConversationTurn.fromDraft(request)));
  }

  @Test
  void followUpPromptContainsResolvedCriteriaAndNoSearchAsksForOne() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.MORE_RESULTS);
    request.setRawText("more please");
    SemanticQuery query = new SemanticQuery();
    query.setTopics(List.of("expeditions"));
    query.setAuthors(List.of("Agatha Christie"));
    request.setPositiveSemanticQuery(query);
    SemanticQuery avoided = new SemanticQuery();
    avoided.setKeywords(List.of("violence"));
    request.setNegativeSemanticQuery(avoided);
    ExactCriteria filter = new ExactCriteria();
    filter.setLanguage("English");
    filter.setDuration("under 3 hours");
    request.setMustInclude(filter);
    String prompt =
        new AiResponseGenerator(null)
            .compilePrompt(
                List.of(new Recommendation("id", 1, 0.9, "Title", "An expedition.")),
                ConversationTurn.fromDraft(request),
                "");
    for (String value :
        List.of(
            "expeditions",
            "Agatha Christie",
            "violence",
            "English",
            "under 3 hours",
            "An expedition.")) {
      assertTrue(prompt.contains(value));
    }
    request.setSearchClarificationRequired(true);
    assertEquals(
        "What kind of audiobook would you like?",
        generator.generate(List.of(), ConversationTurn.fromDraft(request)));
  }

  @Test
  void repeatRendersThePreviousBooksOrExplainsThatThereAreNone() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.REPEAT);
    assertEquals(
        "There is no previous request to repeat. Ask me for recommendations or help first.",
        generator.generate(List.of(), ConversationTurn.fromDraft(request)));
    String response =
        generator.generate(
            List.of(new Recommendation("book-101", 1, 0.9, "Previous book")),
            ConversationTurn.fromDraft(request));
    assertTrue(response.contains("book-101"));
  }

  @Test
  void shouldBuildAHumanReadableRecommendationMessage() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));

    ConversationDraft request = new ConversationDraft();
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

    String response = generator.generate(recommendations, ConversationTurn.fromDraft(request));

    assertNotNull(response);
    assertTrue(response.toLowerCase().contains("action"));
    assertTrue(response.toLowerCase().contains("book-101"));
    assertTrue(response.toLowerCase().contains("recommend"));
  }

  @Test
  void shouldIncludeStoredShortDescriptionsInFallbackResponses() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
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
            ConversationTurn.fromDraft(request));

    assertTrue(response.contains("A young noble navigates danger and destiny on a desert planet."));
  }

  @Test
  void shouldExplainTheLimitAndReturnFiveRecommendationsWhenCountIsExplicit() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));

    ConversationDraft request = new ConversationDraft();
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

    String response = generator.generate(recommendations, ConversationTurn.fromDraft(request));

    assertNotNull(response);
    assertTrue(response.contains("up to 5"));
    assertTrue(response.contains("Book1"));
    assertTrue(response.contains("Book5"));
    assertFalse(response.contains("Book6"));
  }

  @Test
  void shouldIgnoreAnOversizedCountNotStatedByTheUser() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("fantasy");
    request.setBookCount(10);

    String response =
        generator.generate(
            List.of(new Recommendation("book-101", 1, 0.91, "Book1")),
            ConversationTurn.fromDraft(request));

    assertTrue(response.contains("Book1"));
    assertFalse(response.contains("up to 5"));
  }

  @Test
  void shouldRecognizeAnExplicitSpelledOutCount() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("Give me ten fantasy audiobooks");
    request.setBookCount(10);

    String response =
        generator.generate(
            List.of(new Recommendation("book-101", 1, 0.91, "Book1")),
            ConversationTurn.fromDraft(request));

    assertTrue(response.contains("up to 5"));
    assertTrue(response.contains("Book1"));
  }

  @Test
  void shouldGiveTheAiTheLimitNoticeAndActualRecommendationCount() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    request.setRawText("Give me 10 recommendations");
    request.setBookCount(10);

    String prompt =
        new AiResponseGenerator(null)
            .compilePrompt(
                List.of(new Recommendation("book-101", 1, 0.91, "Book1")),
                ConversationTurn.fromDraft(request),
                "I can give up to 5 recommendations at a time. ");

    assertTrue(prompt.contains("Recommendation count: 1"));
    assertTrue(prompt.contains("Limit notice: I can give up to 5 recommendations at a time."));
  }

  @Test
  void shouldReturnFriendlyFallbackWhenNoDataIsAvailable() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));

    String response = generator.generate(null, null);

    assertNotNull(response);
    assertTrue(response.toLowerCase().contains("try again"));
  }

  @Test
  void shouldNotBlameTheYearWhenCombinedFiltersReturnNoMatches() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    ExactCriteria publicationYear = new ExactCriteria();
    publicationYear.setPublicationYear("2024");
    publicationYear.setLanguage("French");
    publicationYear.setAuthors(List.of("An Author"));
    request.setMustInclude(publicationYear);

    assertEquals(
        "I couldn't find books matching those criteria. Try changing your criteria.",
        generator.generate(List.of(), ConversationTurn.fromDraft(request)));
  }

  @Test
  void personalisedNoMatchesDescribesTheCombinedSearchWithoutBlamingASingleFilter() {
    var generator = new ConversationResponseGenerator(new AiResponseGenerator(null));
    var request = new ConversationDraft();
    request.setIntent(Intent.RECOMMENDATION);
    request.setPersonalised(true);
    request.setRawText("personalised for me");
    var exact = new ExactCriteria();
    exact.setAuthors(List.of("jk rowling"));
    request.setMustInclude(exact);
    var genre = new SemanticQuery();
    genre.setGenres(List.of("romance"));
    request.setPositiveSemanticQuery(genre);
    assertEquals(
        "I couldn't find audiobooks matching your current search and saved preferences. "
            + "Try changing the author, genre, or another filter.",
        generator.generate(List.of(), ConversationTurn.fromDraft(request)));
    assertEquals(List.of("jk rowling"), request.getMustInclude().getAuthors());
    assertEquals(List.of("romance"), request.getPositiveSemanticQuery().getGenres());
  }

  @Test
  void shouldAcknowledgeChangingTheRecommendationCount() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.CHANGE_COUNT);
    request.setBookCount(3);

    assertEquals(
        "I'll show 3 recommendations at a time.",
        generator.generate(List.of(), ConversationTurn.fromDraft(request)));
  }

  @Test
  void shouldCapAnExplicitChangeCountRequest() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.CHANGE_COUNT);
    request.setRawText("Show me 10 recommendations at a time");
    request.setBookCount(10);

    assertEquals(
        "I can give up to 5 recommendations at a time. I'll show 5 recommendations at a time.",
        generator.generate(List.of(), ConversationTurn.fromDraft(request)));
  }

  @Test
  void shouldAcknowledgeUpdatingSessionPreferences() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.UPDATE_PREFERENCES);

    String response = generator.generate(List.of(), ConversationTurn.fromDraft(request));

    assertTrue(response.toLowerCase().contains("updated your preferences"));
    assertTrue(response.toLowerCase().contains("session"));
  }

  @Test
  void shouldExplainSupportedActionsForHelpIntent() {
    ConversationResponseGenerator generator =
        new ConversationResponseGenerator(new AiResponseGenerator(null));
    ConversationDraft request = new ConversationDraft();
    request.setIntent(Intent.HELP);

    String response = generator.generate(List.of(), ConversationTurn.fromDraft(request));

    assertTrue(response.toLowerCase().contains("refine"));
    assertTrue(response.toLowerCase().contains("more results"));
    assertTrue(response.toLowerCase().contains("clear"));
  }
}
