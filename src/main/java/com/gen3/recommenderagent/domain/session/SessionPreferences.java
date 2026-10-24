package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Search preferences retained for the lifetime of one recommendation session. */
public class SessionPreferences {

  private SemanticQuery positiveSemanticQuery;
  private SemanticQuery negativeSemanticQuery;
  private MustInclude mustInclude;
  private MustNotInclude mustNotInclude;

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

  public MustInclude getMustInclude() {
    return mustInclude;
  }

  public void setMustInclude(MustInclude mustInclude) {
    this.mustInclude = mustInclude;
  }

  public MustNotInclude getMustNotInclude() {
    return mustNotInclude;
  }

  public void setMustNotInclude(MustNotInclude mustNotInclude) {
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
        throw new InvalidRequestException("Please specify which preference you want to replace.");
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
    result.mustInclude = new MustInclude();
    result.mustInclude.setAuthors(
        terms(
            get(mustInclude, MustInclude::getAuthors),
            get(mustIncludeAdds, MustInclude::getAuthors),
            get(mustIncludeRemoves, MustInclude::getAuthors),
            replace.contains("mustInclude.authors")));
    result.mustInclude.setNarrators(
        terms(
            get(mustInclude, MustInclude::getNarrators),
            get(mustIncludeAdds, MustInclude::getNarrators),
            get(mustIncludeRemoves, MustInclude::getNarrators),
            replace.contains("mustInclude.narrators")));
    result.mustInclude.setLanguage(
        scalar(
            get(mustInclude, MustInclude::getLanguage),
            get(mustIncludeAdds, MustInclude::getLanguage),
            get(mustIncludeRemoves, MustInclude::getLanguage),
            replace.contains("mustInclude.language")));
    result.mustInclude.setDuration(
        scalar(
            get(mustInclude, MustInclude::getDuration),
            get(mustIncludeAdds, MustInclude::getDuration),
            get(mustIncludeRemoves, MustInclude::getDuration),
            replace.contains("mustInclude.duration")));
    result.mustInclude.setPublicationYear(
        scalar(
            get(mustInclude, MustInclude::getPublicationYear),
            get(mustIncludeAdds, MustInclude::getPublicationYear),
            get(mustIncludeRemoves, MustInclude::getPublicationYear),
            replace.contains("mustInclude.publicationYear")));
    result.mustInclude.setSource(
        scalar(
            get(mustInclude, MustInclude::getSource),
            get(mustIncludeAdds, MustInclude::getSource),
            get(mustIncludeRemoves, MustInclude::getSource),
            replace.contains("mustInclude.source")));
    var mustNotIncludeAdds = get(additions, SessionPreferences::getMustNotInclude);
    var mustNotIncludeRemoves = get(removals, SessionPreferences::getMustNotInclude);
    result.mustNotInclude = new MustNotInclude();
    result.mustNotInclude.setAuthors(
        terms(
            get(mustNotInclude, MustNotInclude::getAuthors),
            get(mustNotIncludeAdds, MustNotInclude::getAuthors),
            get(mustNotIncludeRemoves, MustNotInclude::getAuthors),
            replace.contains("mustNotInclude.authors")));
    result.mustNotInclude.setNarrators(
        terms(
            get(mustNotInclude, MustNotInclude::getNarrators),
            get(mustNotIncludeAdds, MustNotInclude::getNarrators),
            get(mustNotIncludeRemoves, MustNotInclude::getNarrators),
            replace.contains("mustNotInclude.narrators")));
    result.mustNotInclude.setLanguage(
        scalar(
            get(mustNotInclude, MustNotInclude::getLanguage),
            get(mustNotIncludeAdds, MustNotInclude::getLanguage),
            get(mustNotIncludeRemoves, MustNotInclude::getLanguage),
            replace.contains("mustNotInclude.language")));
    result.mustNotInclude.setDuration(
        scalar(
            get(mustNotInclude, MustNotInclude::getDuration),
            get(mustNotIncludeAdds, MustNotInclude::getDuration),
            get(mustNotIncludeRemoves, MustNotInclude::getDuration),
            replace.contains("mustNotInclude.duration")));
    result.mustNotInclude.setPublicationYear(
        scalar(
            get(mustNotInclude, MustNotInclude::getPublicationYear),
            get(mustNotIncludeAdds, MustNotInclude::getPublicationYear),
            get(mustNotIncludeRemoves, MustNotInclude::getPublicationYear),
            replace.contains("mustNotInclude.publicationYear")));
    result.mustNotInclude.setSource(
        scalar(
            get(mustNotInclude, MustNotInclude::getSource),
            get(mustNotIncludeAdds, MustNotInclude::getSource),
            get(mustNotIncludeRemoves, MustNotInclude::getSource),
            replace.contains("mustNotInclude.source")));
    return result;
  }

  public boolean hasValues() {
    return hasQuery(positiveSemanticQuery)
        || hasQuery(negativeSemanticQuery)
        || hasTerms(get(mustInclude, MustInclude::getAuthors))
        || hasTerms(get(mustInclude, MustInclude::getNarrators))
        || hasTerms(get(mustNotInclude, MustNotInclude::getAuthors))
        || hasTerms(get(mustNotInclude, MustNotInclude::getNarrators))
        || java.util.stream.Stream.of(
                get(mustInclude, MustInclude::getLanguage),
                    get(mustInclude, MustInclude::getSource),
                get(mustInclude, MustInclude::getDuration),
                    get(mustInclude, MustInclude::getPublicationYear),
                get(mustNotInclude, MustNotInclude::getLanguage),
                    get(mustNotInclude, MustNotInclude::getSource),
                get(mustNotInclude, MustNotInclude::getDuration),
                    get(mustNotInclude, MustNotInclude::getPublicationYear))
            .anyMatch(value -> value != null && !value.isBlank());
  }

  private static boolean hasQuery(SemanticQuery query) {
    return query != null
        && (hasTerms(query.getGenres())
            || hasTerms(query.getTopics())
            || hasTerms(query.getKeywords())
            || hasTerms(query.getAuthors())
            || hasTerms(query.getNarrators()));
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
        terms(
            get(base, SemanticQuery::getAuthors),
            get(additions, SemanticQuery::getAuthors),
            get(removals, SemanticQuery::getAuthors),
            replace.contains(prefix + ".authors")));
    result.setNarrators(
        terms(
            get(base, SemanticQuery::getNarrators),
            get(additions, SemanticQuery::getNarrators),
            get(removals, SemanticQuery::getNarrators),
            replace.contains(prefix + ".narrators")));
    return result;
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
