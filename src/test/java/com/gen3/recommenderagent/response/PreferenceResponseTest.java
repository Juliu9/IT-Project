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
        .isEqualTo("You have no saved session preferences. Recommendation count: 3.");
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
            "Recommendation count: 3.",
            "Preferred genres: fantasy, mystery.",
            "Preferred author styles: Agatha Christie.",
            "Avoided topics: violence.",
            "Required authors: Author One.",
            "Required language: English.",
            "Required duration: under 3 hours.",
            "Required publication year: after 2000.",
            "Required source: RNIB.",
            "Excluded narrators: Narrator Two.")
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
    assertThat(response).endsWith("Additional preferences omitted.");
    assertThat(response)
        .contains("Preferred topics: " + longTerm + ", " + longTerm + ", " + longTerm + ".");
  }

  @Test
  void completeReportCanUseTheEntireBudgetWithoutAnOmissionNotice() {
    var preferences = new SessionPreferences();
    var preferred = new SemanticQuery();
    preferred.setGenres(List.of("a ".repeat(10).strip()));
    preferred.setTopics(List.of("b ".repeat(128).strip()));
    preferences.setPositiveSemanticQuery(preferred);
    String response = render(preferences);
    assertThat(ResponseBudget.wordCount(response)).isEqualTo(149);
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
    assertThat(response).endsWith("Additional preferences omitted.");
    assertThat(response).doesNotContain("Required", "Preferred topics", longTopic);
  }

  private String render(SessionPreferences preferences) {
    var turn = new ConversationTurn();
    turn.setIntent(ConversationAction.EXPLAIN_PREFERENCES);
    turn.setBookCount(3);
    turn.setSessionPreferenceSnapshot(preferences);
    return generator.generate(List.of(), turn);
  }
}
