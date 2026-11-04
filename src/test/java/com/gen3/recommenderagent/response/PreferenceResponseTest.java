package com.gen3.recommenderagent.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreferenceResponseTest {
  private final ConversationResponseGenerator generator = new ConversationResponseGenerator(null);

  @Test
  void emptyPreferencesShowCountWithoutEmptyFields() {
    assertThat(render(null))
        .isEqualTo(
            "You haven't saved any preferences for this session yet. "
                + "I'll give you 3 recommendations at a time. "
                + "You can ask me to change any of these whenever you like.");
  }

  @Test
  void reportsSavedValuesWithPolarityAndSkipsEmptyFieldsWithoutAi() {
    var preferences = new SessionPreferences();
    var preferred = new SemanticQuery();
    preferred.setGenres(List.of("fantasy", "mystery"));
    preferred.setAuthors(List.of("Agatha Christie"));
    preferences.setPositiveSemanticQuery(preferred);
    var avoided = new SemanticQuery();
    avoided.setTopics(List.of("violence"));
    preferences.setNegativeSemanticQuery(avoided);
    var required = new ExactCriteria();
    required.setLanguage("English");
    required.setDuration("under 3 hours");
    required.setPublicationYear("after 2000");
    required.setSource("RNIB");
    required.setAuthors(List.of("Author One"));
    preferences.setMustInclude(required);
    var excluded = new ExactCriteria();
    excluded.setNarrators(List.of("Narrator Two"));
    preferences.setMustNotInclude(excluded);
    String response = render(preferences);
    assertThat(response)
        .contains(
            "I'll give you 3 recommendations at a time.",
            "You enjoy genres like fantasy, mystery.",
            "You enjoy author styles like Agatha Christie.",
            "You'd rather avoid stories about violence.",
            "You want books by Author One, in English, under 3 hours, published after 2000, and from RNIB.",
            "You don't want books narrated by Narrator Two.")
        .doesNotContain("keywords", "null", "Not set", "omitted")
        .endsWith("You can ask me to change any of these whenever you like.");
    assertThat(response.indexOf("You don't want"))
        .isLessThan(response.indexOf("You enjoy author styles"));
    assertThat(response.indexOf("You enjoy author styles"))
        .isLessThan(response.indexOf("You enjoy genres"));
  }

  @Test
  void overflowReservesNoticeAndNeverSplitsAnEntry() {
    var preferences = new SessionPreferences();
    var preferred = new SemanticQuery();
    String longTerm = "multi word topic ".repeat(15).strip();
    preferred.setTopics(List.of(longTerm, longTerm, longTerm, longTerm));
    preferences.setPositiveSemanticQuery(preferred);
    String response = render(preferences);
    assertThat(ResponseBudget.wordCount(response)).isLessThanOrEqualTo(150);
    assertThat(response)
        .contains("I left out a few more preferences to keep this brief.")
        .endsWith("You can ask me to change any of these whenever you like.");
    assertThat(response)
        .contains("You enjoy stories about " + longTerm + ", " + longTerm + ".")
        .doesNotContain(longTerm + ", " + longTerm + ", " + longTerm + ".");
  }

  @Test
  void completeReportCanUseTheEntireBudgetWithoutAnOmissionNotice() {
    var preferences = new SessionPreferences();
    var preferred = new SemanticQuery();
    preferred.setGenres(List.of("a ".repeat(10).strip()));
    preferred.setTopics(List.of("b ".repeat(105).strip()));
    preferences.setPositiveSemanticQuery(preferred);
    String response = render(preferences);
    assertThat(ResponseBudget.wordCount(response)).isLessThanOrEqualTo(150);
    assertThat(ResponseBudget.wordCount(response)).isEqualTo(150);
    assertThat(response)
        .doesNotContain("I left out")
        .endsWith("You can ask me to change any of these whenever you like.");
  }

  @Test
  void oversizedSingleEntryIsOmittedWhole() {
    var preferences = new SessionPreferences();
    var preferred = new SemanticQuery();
    preferred.setGenres(List.of("a ".repeat(20).strip()));
    String longTopic = "x ".repeat(128).strip();
    preferred.setTopics(List.of(longTopic));
    preferences.setPositiveSemanticQuery(preferred);
    String response = render(preferences);
    assertThat(ResponseBudget.wordCount(response)).isLessThanOrEqualTo(150);
    assertThat(response)
        .contains("I left out a few more preferences to keep this brief.")
        .endsWith("You can ask me to change any of these whenever you like.");
    assertThat(response).doesNotContain("You want", "You enjoy stories about", longTopic);
  }

  @Test
  void everyConstraintIsListedBeforeLongPositiveAndNegativeTastes() {
    var preferences = new SessionPreferences();
    var query = new SemanticQuery();
    query.setGenres(List.of("a ".repeat(120).strip()));
    query.setTopics(List.of("penguins"));
    preferences.setPositiveSemanticQuery(query);
    preferences.setNegativeSemanticQuery(query);
    var required = new ExactCriteria();
    required.setAuthors(List.of("Agatha Christie"));
    required.setNarrators(List.of("Hugh Fraser"));
    required.setLanguage("English");
    required.setDuration("under 8 hours");
    required.setPublicationYear("after 2000");
    required.setSource("RNIB");
    preferences.setMustInclude(required);
    var excluded = new ExactCriteria();
    excluded.setAuthors(List.of("Dan Brown"));
    excluded.setNarrators(List.of("Scott Brick"));
    excluded.setLanguage("French");
    excluded.setDuration("over 12 hours");
    excluded.setPublicationYear("before 1950");
    excluded.setSource("LibriVox");
    preferences.setMustNotInclude(excluded);
    String response = render(preferences);
    assertThat(response)
        .contains(
            "You want books by Agatha Christie, narrated by Hugh Fraser, in English, under 8 hours, "
                + "published after 2000, and from RNIB.",
            "You don't want books by Dan Brown, narrated by Scott Brick, in French, over 12 hours, "
                + "published before 1950, or from LibriVox.",
            "I left out")
        .doesNotContain("genres like", "stories about")
        .containsOnlyOnce("You want books")
        .containsOnlyOnce("You don't want books");
    assertThat(ResponseBudget.wordCount(response)).isLessThanOrEqualTo(150);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ConversationAction.class,
      names = {"EXPLAIN_PREFERENCES", "REPEAT"})
  void longConstraintsAreNeverTruncatedEvenWhenRepeated(ConversationAction action) {
    var preferences = new SessionPreferences();
    var required = new ExactCriteria();
    String requiredSource = "a ".repeat(120).strip();
    required.setSource(requiredSource);
    preferences.setMustInclude(required);
    var excluded = new ExactCriteria();
    String excludedSource = "b ".repeat(120).strip();
    excluded.setSource(excludedSource);
    preferences.setMustNotInclude(excluded);
    var turn = new ConversationTurn();
    turn.setIntent(action);
    if (action == ConversationAction.REPEAT) {
      turn.setResponseAction(ConversationAction.EXPLAIN_PREFERENCES);
    }
    turn.setSessionPreferenceSnapshot(preferences);
    turn.setBookCount(3);
    String response = generator.generate(List.of(), turn);
    assertThat(response)
        .contains(
            "You want books from " + requiredSource + ".",
            "You don't want books from " + excludedSource + ".")
        .doesNotContain("I left out")
        .endsWith("You can ask me to change any of these whenever you like.");
    assertThat(ResponseBudget.wordCount(response)).isGreaterThan(150);
  }

  @Test
  void twoConstraintsUseANaturalConjunctionWithoutAnExtraComma() {
    var preferences = new SessionPreferences();
    var required = new ExactCriteria();
    required.setLanguage("English");
    required.setDuration("under 8 hours");
    preferences.setMustInclude(required);
    var excluded = new ExactCriteria();
    excluded.setLanguage("French");
    excluded.setSource("LibriVox");
    preferences.setMustNotInclude(excluded);
    assertThat(render(preferences))
        .contains(
            "You want books in English and under 8 hours.",
            "You don't want books in French or from LibriVox.");
  }

  private String render(SessionPreferences preferences) {
    var turn = new ConversationTurn();
    turn.setIntent(ConversationAction.EXPLAIN_PREFERENCES);
    turn.setBookCount(3);
    turn.setSessionPreferenceSnapshot(preferences);
    return generator.generate(List.of(), turn);
  }
}
