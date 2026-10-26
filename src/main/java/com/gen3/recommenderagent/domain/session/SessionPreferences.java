package com.gen3.recommenderagent.domain.session;

import com.gen3.catalogue.model.PersonNameNormalizer;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Search preferences retained for the lifetime of one recommendation session. */
public class SessionPreferences {

  private SemanticQuery positiveSemanticQuery;
  private SemanticQuery negativeSemanticQuery;
  private ExactCriteria mustInclude;
  private ExactCriteria mustNotInclude;

  public SemanticQuery getPositiveSemanticQuery() {
    return positiveSemanticQuery;
  }

  public void setPositiveSemanticQuery(SemanticQuery positiveSemanticQuery) {
    this.positiveSemanticQuery = positiveSemanticQuery;
  }

  public SemanticQuery getNegativeSemanticQuery() {
    return negativeSemanticQuery;
  }

  public void setNegativeSemanticQuery(SemanticQuery negativeSemanticQuery) {
    this.negativeSemanticQuery = negativeSemanticQuery;
  }

  public ExactCriteria getMustInclude() {
    return mustInclude;
  }

  public void setMustInclude(ExactCriteria mustInclude) {
    this.mustInclude = mustInclude;
  }

  public ExactCriteria getMustNotInclude() {
    return mustNotInclude;
  }

  public void setMustNotInclude(ExactCriteria mustNotInclude) {
    this.mustNotInclude = mustNotInclude;
  }

  public static SessionPreferences fromRequest(SessionRequest request) {
    var preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(request.getPositiveSemanticQuery());
    preferences.setNegativeSemanticQuery(request.getNegativeSemanticQuery());
    preferences.setMustInclude(request.getMustInclude());
    preferences.setMustNotInclude(request.getMustNotInclude());
    return preferences.copy();
  }

  public SessionRequest asRequest() {
    var copy = copy();
    var request = new SessionRequest();
    request.setIntent(com.gen3.recommenderagent.domain.Intent.RECOMMENDATION);
    request.setPositiveSemanticQuery(copy.positiveSemanticQuery);
    request.setNegativeSemanticQuery(copy.negativeSemanticQuery);
    request.setMustInclude(copy.mustInclude);
    request.setMustNotInclude(copy.mustNotInclude);
    return request;
  }

  /**
   * Removes a named saved value wherever it is stored, using the existing field-specific equality.
   */
  public SessionPreferences withoutTerms(List<String> values, boolean positiveOnly) {
    var result = copy();
    for (String value : values) {
      var terms = List.of(value);
      var query = new SemanticQuery();
      query.setAuthors(terms);
      query.setNarrators(terms);
      query.setGenres(terms);
      query.setTopics(terms);
      query.setKeywords(terms);
      var exact = new ExactCriteria();
      exact.setAuthors(terms);
      exact.setNarrators(terms);
      exact.setLanguage(value);
      exact.setSource(value);
      exact.setDuration(value);
      exact.setPublicationYear(value);
      var removals = new SessionPreferences();
      removals.setPositiveSemanticQuery(query);
      removals.setMustInclude(exact);
      if (!positiveOnly) {
        removals.setNegativeSemanticQuery(query);
        removals.setMustNotInclude(exact);
      }
      result = result.updated(null, removals, List.of());
    }
    return result;
  }

  public SessionPreferences copy() {
    return updated(null, null, List.of());
  }

  /** Applies only supplied changes; replacements are paths such as positiveSemanticQuery.genres. */
  public SessionPreferences updated(
      SessionPreferences additions, SessionPreferences removals, List<String> replacementFields) {
    var replace = replacementFields == null ? List.<String>of() : replacementFields;
    for (String path : replace) {
      String[] parts = path == null ? new String[0] : path.split("\\.");
      if (parts.length != 2
          || !(switch (parts[0]) {
            case "positiveSemanticQuery", "negativeSemanticQuery" ->
                List.of("genres", "topics", "keywords", "authors", "narrators").contains(parts[1]);
            case "mustInclude", "mustNotInclude" ->
                List.of("authors", "narrators", "language", "source", "duration", "publicationYear")
                    .contains(parts[1]);
            default -> false;
          })) {
        throw new InvalidRequestException(
            "Which preference would you like to change? You can name a genre, author, or narrator.");
      }
    }
    var result = new SessionPreferences();
    result.positiveSemanticQuery =
        query(
            positiveSemanticQuery,
            get(additions, SessionPreferences::getPositiveSemanticQuery),
            get(removals, SessionPreferences::getPositiveSemanticQuery),
            "positiveSemanticQuery",
            replace);
    result.negativeSemanticQuery =
        query(
            negativeSemanticQuery,
            get(additions, SessionPreferences::getNegativeSemanticQuery),
            get(removals, SessionPreferences::getNegativeSemanticQuery),
            "negativeSemanticQuery",
            replace);
    var mustIncludeAdds = get(additions, SessionPreferences::getMustInclude);
    var mustIncludeRemoves = get(removals, SessionPreferences::getMustInclude);
    result.mustInclude = new ExactCriteria();
    result.mustInclude.setAuthors(
        personTerms(
            get(mustInclude, ExactCriteria::getAuthors),
            get(mustIncludeAdds, ExactCriteria::getAuthors),
            get(mustIncludeRemoves, ExactCriteria::getAuthors),
            replace.contains("mustInclude.authors")));
    result.mustInclude.setNarrators(
        personTerms(
            get(mustInclude, ExactCriteria::getNarrators),
            get(mustIncludeAdds, ExactCriteria::getNarrators),
            get(mustIncludeRemoves, ExactCriteria::getNarrators),
            replace.contains("mustInclude.narrators")));
    result.mustInclude.setLanguage(
        scalar(
            get(mustInclude, ExactCriteria::getLanguage),
            get(mustIncludeAdds, ExactCriteria::getLanguage),
            get(mustIncludeRemoves, ExactCriteria::getLanguage),
            replace.contains("mustInclude.language")));
    result.mustInclude.setDuration(
        scalar(
            get(mustInclude, ExactCriteria::getDuration),
            get(mustIncludeAdds, ExactCriteria::getDuration),
            get(mustIncludeRemoves, ExactCriteria::getDuration),
            replace.contains("mustInclude.duration")));
    result.mustInclude.setPublicationYear(
        scalar(
            get(mustInclude, ExactCriteria::getPublicationYear),
            get(mustIncludeAdds, ExactCriteria::getPublicationYear),
            get(mustIncludeRemoves, ExactCriteria::getPublicationYear),
            replace.contains("mustInclude.publicationYear")));
    result.mustInclude.setSource(
        scalar(
            get(mustInclude, ExactCriteria::getSource),
            get(mustIncludeAdds, ExactCriteria::getSource),
            get(mustIncludeRemoves, ExactCriteria::getSource),
            replace.contains("mustInclude.source")));
    var mustNotIncludeAdds = get(additions, SessionPreferences::getMustNotInclude);
    var mustNotIncludeRemoves = get(removals, SessionPreferences::getMustNotInclude);
    result.mustNotInclude = new ExactCriteria();
    result.mustNotInclude.setAuthors(
        personTerms(
            get(mustNotInclude, ExactCriteria::getAuthors),
            get(mustNotIncludeAdds, ExactCriteria::getAuthors),
            get(mustNotIncludeRemoves, ExactCriteria::getAuthors),
            replace.contains("mustNotInclude.authors")));
    result.mustNotInclude.setNarrators(
        personTerms(
            get(mustNotInclude, ExactCriteria::getNarrators),
            get(mustNotIncludeAdds, ExactCriteria::getNarrators),
            get(mustNotIncludeRemoves, ExactCriteria::getNarrators),
            replace.contains("mustNotInclude.narrators")));
    result.mustNotInclude.setLanguage(
        scalar(
            get(mustNotInclude, ExactCriteria::getLanguage),
            get(mustNotIncludeAdds, ExactCriteria::getLanguage),
            get(mustNotIncludeRemoves, ExactCriteria::getLanguage),
            replace.contains("mustNotInclude.language")));
    result.mustNotInclude.setDuration(
        scalar(
            get(mustNotInclude, ExactCriteria::getDuration),
            get(mustNotIncludeAdds, ExactCriteria::getDuration),
            get(mustNotIncludeRemoves, ExactCriteria::getDuration),
            replace.contains("mustNotInclude.duration")));
    result.mustNotInclude.setPublicationYear(
        scalar(
            get(mustNotInclude, ExactCriteria::getPublicationYear),
            get(mustNotIncludeAdds, ExactCriteria::getPublicationYear),
            get(mustNotIncludeRemoves, ExactCriteria::getPublicationYear),
            replace.contains("mustNotInclude.publicationYear")));
    result.mustNotInclude.setSource(
        scalar(
            get(mustNotInclude, ExactCriteria::getSource),
            get(mustNotIncludeAdds, ExactCriteria::getSource),
            get(mustNotIncludeRemoves, ExactCriteria::getSource),
            replace.contains("mustNotInclude.source")));
    return result;
  }

  public boolean hasValues() {
    return hasQuery(positiveSemanticQuery)
        || hasQuery(negativeSemanticQuery)
        || hasTerms(get(mustInclude, ExactCriteria::getAuthors))
        || hasTerms(get(mustInclude, ExactCriteria::getNarrators))
        || hasTerms(get(mustNotInclude, ExactCriteria::getAuthors))
        || hasTerms(get(mustNotInclude, ExactCriteria::getNarrators))
        || java.util.stream.Stream.of(
                get(mustInclude, ExactCriteria::getLanguage),
                    get(mustInclude, ExactCriteria::getSource),
                get(mustInclude, ExactCriteria::getDuration),
                    get(mustInclude, ExactCriteria::getPublicationYear),
                get(mustNotInclude, ExactCriteria::getLanguage),
                    get(mustNotInclude, ExactCriteria::getSource),
                get(mustNotInclude, ExactCriteria::getDuration),
                    get(mustNotInclude, ExactCriteria::getPublicationYear))
            .anyMatch(value -> value != null && !value.isBlank());
  }

  private static boolean hasQuery(SemanticQuery query) {
    return query != null && query.hasValues();
  }

  private static boolean hasTerms(List<String> terms) {
    return terms != null && terms.stream().anyMatch(value -> value != null && !value.isBlank());
  }

  private static <T, R> R get(T source, Function<T, R> getter) {
    return source == null ? null : getter.apply(source);
  }

  private static SemanticQuery query(
      SemanticQuery base,
      SemanticQuery additions,
      SemanticQuery removals,
      String prefix,
      List<String> replace) {
    if (base == null && additions == null && removals == null) {
      return null;
    }
    var result = new SemanticQuery();
    result.setGenres(
        terms(
            get(base, SemanticQuery::getGenres),
            get(additions, SemanticQuery::getGenres),
            get(removals, SemanticQuery::getGenres),
            replace.contains(prefix + ".genres")));
    result.setTopics(
        terms(
            get(base, SemanticQuery::getTopics),
            get(additions, SemanticQuery::getTopics),
            get(removals, SemanticQuery::getTopics),
            replace.contains(prefix + ".topics")));
    result.setKeywords(
        terms(
            get(base, SemanticQuery::getKeywords),
            get(additions, SemanticQuery::getKeywords),
            get(removals, SemanticQuery::getKeywords),
            replace.contains(prefix + ".keywords")));
    result.setAuthors(
        personTerms(
            get(base, SemanticQuery::getAuthors),
            get(additions, SemanticQuery::getAuthors),
            get(removals, SemanticQuery::getAuthors),
            replace.contains(prefix + ".authors")));
    result.setNarrators(
        personTerms(
            get(base, SemanticQuery::getNarrators),
            get(additions, SemanticQuery::getNarrators),
            get(removals, SemanticQuery::getNarrators),
            replace.contains(prefix + ".narrators")));
    return result;
  }

  private static List<String> personTerms(
      List<String> base, List<String> additions, List<String> removals, boolean replace) {
    var result = terms(base, additions, null, replace);
    if (result == null || removals == null) {
      return result;
    }
    var removed =
        removals.stream()
            .filter(java.util.Objects::nonNull)
            .map(PersonNameNormalizer::comparisonKey)
            .collect(java.util.stream.Collectors.toSet());
    var remaining =
        result.stream()
            .filter(value -> !removed.contains(PersonNameNormalizer.comparisonKey(value)))
            .toList();
    return remaining.isEmpty() ? null : remaining;
  }

  private static List<String> terms(
      List<String> base, List<String> additions, List<String> removals, boolean replace) {
    var result = new LinkedHashMap<String, String>();
    for (var values : java.util.Arrays.asList(replace ? null : base, additions)) {
      if (values != null) {
        values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .forEach(value -> result.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
      }
    }
    if (removals != null) {
      removals.stream()
          .filter(java.util.Objects::nonNull)
          .forEach(value -> result.remove(value.trim().toLowerCase(Locale.ROOT)));
    }
    return result.isEmpty() ? null : List.copyOf(result.values());
  }

  private static String scalar(String base, String addition, String removal, boolean replace) {
    String value = addition != null && !addition.isBlank() ? addition : replace ? null : base;
    return value != null && removal != null && value.trim().equalsIgnoreCase(removal.trim())
        ? null
        : value;
  }
}
