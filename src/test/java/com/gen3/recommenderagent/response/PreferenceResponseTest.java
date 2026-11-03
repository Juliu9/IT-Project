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
                + "I'll give you 3 recommendations at a time.");
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
    assertThat(render(preferences))
        .contains(
            "I'll give you 3 recommendations at a time.",
            "You enjoy genres like fantasy, mystery.",
            "You enjoy author styles like Agatha Christie.",
            "You'd rather avoid stories about violence.",
            "You want books by Author One.",
            "You want books in English.",
            "You want books that are under 3 hours.",
            "You want books published after 2000.",
            "You want books from RNIB.",
            "You don't want books narrated by Narrator Two.")
        .doesNotContain("keywords", "null", "Not set", "omitted");
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
    assertThat(response).endsWith("I left out a few more preferences to keep this brief.");
    assertThat(response)
        .contains("You enjoy stories about " + longTerm + ", " + longTerm + ".")
        .doesNotContain(longTerm + ", " + longTerm + ", " + longTerm + ".");
  }

  @Test
  void completeReportCanUseTheEntireBudgetWithoutAnOmissionNotice() {
    var preferences = new SessionPreferences();
    var preferred = new SemanticQuery();
    preferred.setGenres(List.of("a ".repeat(10).strip()));
    preferred.setTopics(List.of("b ".repeat(128).strip()));
    preferences.setPositiveSemanticQuery(preferred);
    String response = render(preferences);
    assertThat(ResponseBudget.wordCount(response)).isLessThanOrEqualTo(150);
    assertThat(response).doesNotContain("omitted");
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
    assertThat(response).endsWith("I left out a few more preferences to keep this brief.");
    assertThat(response).doesNotContain("You want", "You enjoy stories about", longTopic);
  }

  private String render(SessionPreferences preferences) {
    var turn = new ConversationTurn();
    turn.setIntent(ConversationAction.EXPLAIN_PREFERENCES);
    turn.setBookCount(3);
    turn.setSessionPreferenceSnapshot(preferences);
    return generator.generate(List.of(), turn);
  }
}
