package com.gen3.recommenderagent.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionPreferencesTest {
  static java.util.stream.Stream<java.util.function.BiConsumer<SemanticQuery, List<String>>>
      semanticFields() {
    return java.util.stream.Stream.of(
        SemanticQuery::setTopics,
        SemanticQuery::setGenres,
        SemanticQuery::setAuthors,
        SemanticQuery::setNarrators,
        SemanticQuery::setKeywords);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("semanticFields")
  void semanticValueChecksIgnoreEmptyTermsAndRecogniseEveryField(
      java.util.function.BiConsumer<SemanticQuery, List<String>> field) {
    var query = new SemanticQuery();
    var preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(query);
    assertThat(query.hasValues()).isFalse();
    assertThat(preferences.hasValues()).isFalse();
    field.accept(query, List.of());
    assertThat(query.hasValues()).isFalse();
    var terms = new java.util.ArrayList<String>(java.util.Arrays.asList(null, "", " \t "));
    field.accept(query, terms);
    assertThat(query.hasValues()).isFalse();
    assertThat(preferences.hasValues()).isFalse();
    terms.add("taste");
    assertThat(query.hasValues()).isTrue();
    assertThat(preferences.hasValues()).isTrue();
    var json = new tools.jackson.databind.ObjectMapper().valueToTree(query);
    assertThat(json.has("values")).isFalse();
  }

  @Test
  void changesAreCopiedDeduplicatedAndLimitedToTheNamedFields() {
    var original = new SessionPreferences();
    var query = new SemanticQuery();
    query.setGenres(List.of("Fantasy"));
    query.setAuthors(List.of("Author"));
    original.setPositiveSemanticQuery(query);
    var exact = new ExactCriteria();
    exact.setLanguage("English");
    exact.setAuthors(List.of("A"));
    original.setMustInclude(exact);
    var additions = new SessionPreferences();
    var addedQuery = new SemanticQuery();
    addedQuery.setGenres(List.of(" fantasy ", "Horror"));
    additions.setPositiveSemanticQuery(addedQuery);
    var addedExact = new ExactCriteria();
    addedExact.setAuthors(List.of("B"));
    additions.setMustInclude(addedExact);
    var removals = new SessionPreferences();
    var removedQuery = new SemanticQuery();
    removedQuery.setAuthors(List.of("author"));
    removals.setPositiveSemanticQuery(removedQuery);
    var changed =
        original.updated(
            additions, removals, List.of("mustInclude.language", "mustInclude.authors"));
    assertThat(changed.getPositiveSemanticQuery().getGenres()).containsExactly("Fantasy", "Horror");
    assertThat(changed.getPositiveSemanticQuery().getAuthors()).isNull();
    assertThat(changed.getMustInclude().getLanguage()).isNull();
    assertThat(changed.getMustInclude().getAuthors()).containsExactly("B");
    assertThat(original.getMustInclude().getLanguage()).isEqualTo("English");
    assertThat(original.getPositiveSemanticQuery().getAuthors()).containsExactly("Author");
  }

  @Test
  void unknownReplacementFieldsAreRejected() {
    assertThatThrownBy(() -> new SessionPreferences().updated(null, null, List.of("unknown.field")))
        .isInstanceOf(InvalidRequestException.class);
  }
}
