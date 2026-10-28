package com.gen3.recommenderagent.domain.session;

import com.gen3.catalogue.model.PersonNameNormalizer;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
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
    validateReplacementFields(replace);
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
    result.mustInclude =
        updatedCriteria(
            mustInclude,
            get(additions, SessionPreferences::getMustInclude),
            get(removals, SessionPreferences::getMustInclude),
            "mustInclude",
            replace);
    result.mustNotInclude =
        updatedCriteria(
            mustNotInclude,
            get(additions, SessionPreferences::getMustNotInclude),
            get(removals, SessionPreferences::getMustNotInclude),
            "mustNotInclude",
            replace);
    return result;
  }

  private static void validateReplacementFields(List<String> replace) {
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
  }

  private static ExactCriteria updatedCriteria(
      ExactCriteria base,
      ExactCriteria additions,
      ExactCriteria removals,
      String prefix,
      List<String> replace) {
    var result = new ExactCriteria();
    result.setAuthors(
        personTerms(
            get(base, ExactCriteria::getAuthors),
            get(additions, ExactCriteria::getAuthors),
            get(removals, ExactCriteria::getAuthors),
            replace.contains(prefix + ".authors")));
    result.setNarrators(
        personTerms(
            get(base, ExactCriteria::getNarrators),
            get(additions, ExactCriteria::getNarrators),
            get(removals, ExactCriteria::getNarrators),
            replace.contains(prefix + ".narrators")));
    result.setLanguage(
        scalar(
            get(base, ExactCriteria::getLanguage),
            get(additions, ExactCriteria::getLanguage),
            get(removals, ExactCriteria::getLanguage),
            replace.contains(prefix + ".language")));
    result.setDuration(
        scalar(
            get(base, ExactCriteria::getDuration),
            get(additions, ExactCriteria::getDuration),
            get(removals, ExactCriteria::getDuration),
            replace.contains(prefix + ".duration")));
    result.setPublicationYear(
        scalar(
            get(base, ExactCriteria::getPublicationYear),
            get(additions, ExactCriteria::getPublicationYear),
            get(removals, ExactCriteria::getPublicationYear),
            replace.contains(prefix + ".publicationYear")));
    result.setSource(
        scalar(
            get(base, ExactCriteria::getSource),
            get(additions, ExactCriteria::getSource),
            get(removals, ExactCriteria::getSource),
            replace.contains(prefix + ".source")));
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
