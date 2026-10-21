package com.gen3.recommenderagent.candidateretriever.retrievalplan;

import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Converts parsed request fields into one immutable retrieval plan. */
@Component
public class AudiobookRetrievalPlanner {

  private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

  /** Separates semantic text, lexical terms, and exact payload constraints. */
  public AudiobookRetrievalPlan plan(
      int limit, SessionRequest request, Set<String> excludedBookIds) {
    SemanticQuery positive = request == null ? null : request.getPositiveSemanticQuery();
    SemanticQuery negative = request == null ? null : request.getNegativeSemanticQuery();
    String keywordText = lexicalText(positive, "");
    AudiobookFilters filters =
        new AudiobookFilters(
            includeConditions(request == null ? null : request.getMustInclude()),
            excludeConditions(request == null ? null : request.getMustNotInclude()),
            excludedBookIds == null ? List.of() : List.copyOf(excludedBookIds));
    return new AudiobookRetrievalPlan(
        modeFor(positive, negative, filters), keywordText, filters, Math.max(limit, 1));
  }

  /** Derives retrieval solely from searchable request content. */
  RetrievalMode modeFor(SemanticQuery positive, SemanticQuery negative, AudiobookFilters filters) {
    boolean hasSemantic =
        hasPositiveSemanticContent(positive) || hasNegativeSemanticContent(negative);
    boolean hasFilters = filters != null && filters.hasConditions();
    if (hasSemantic && hasFilters) {
      return RetrievalMode.HYBRID;
    }
    if (hasSemantic) {
      return RetrievalMode.SEMANTIC;
    }
    if (hasFilters) {
      return RetrievalMode.FILTER_ONLY;
    }
    return RetrievalMode.NONE;
  }

  /** Builds sparse-search input from fields intended to influence textual relevance. */
  public String lexicalText(SemanticQuery semanticQuery, String fallback) {
    if (semanticQuery == null) {
      return fallback;
    }
    List<String> terms = new ArrayList<>();
    add(terms, semanticQuery.getTopics());
    add(terms, semanticQuery.getGenres());
    add(terms, semanticQuery.getKeywords());
    return terms.isEmpty() ? fallback : String.join(" ", terms);
  }

  private AudiobookFilterConditions includeConditions(MustInclude filter) {
    if (filter == null) {
      return AudiobookFilterConditions.empty();
    }
    DurationRange bounds = DurationRange.parse(filter.getDuration());
    RangeBounds years = yearBounds(filter.getPublicationYear());
    return new AudiobookFilterConditions(
        copy(filter.getAuthors()),
        copy(filter.getNarrators()),
        filter.getLanguage(),
        filter.getSource(),
        bounds.minimum(),
        bounds.maximum(),
        years.minimum(),
        years.maximum());
  }

  private AudiobookFilterConditions excludeConditions(MustNotInclude filter) {
    if (filter == null) {
      return AudiobookFilterConditions.empty();
    }
    DurationRange bounds = DurationRange.parse(filter.getDuration());
    RangeBounds years = yearBounds(filter.getPublicationYear());
    return new AudiobookFilterConditions(
        copy(filter.getAuthors()),
        copy(filter.getNarrators()),
        filter.getLanguage(),
        filter.getSource(),
        bounds.minimum(),
        bounds.maximum(),
        years.minimum(),
        years.maximum());
  }

  /** Parses values such as "after 2020" and "before 2000". */
  private RangeBounds yearBounds(String publicationYear) {
    if (!hasText(publicationYear)) {
      return RangeBounds.empty();
    }
    Matcher matcher = FIRST_NUMBER.matcher(publicationYear);
    if (!matcher.find()) {
      return RangeBounds.empty();
    }
    int year = Integer.parseInt(matcher.group(1));
    String normalized = publicationYear.toLowerCase(Locale.ROOT);
    boolean lowerBound =
        normalized.contains("after")
            || normalized.contains("from")
            || normalized.contains("since")
            || normalized.contains("newer")
            || normalized.contains("over")
            || normalized.contains("more than")
            || normalized.contains("at least")
            || normalized.contains("minimum");
    boolean upperBound =
        normalized.contains("before")
            || normalized.contains("older")
            || normalized.contains("under")
            || normalized.contains("less than")
            || normalized.contains("maximum");
    if (lowerBound) {
      return new RangeBounds(year, null);
    }
    if (upperBound) {
      return new RangeBounds(null, year);
    }
    return new RangeBounds(year, year);
  }

  /** Adds nonblank parsed terms to the lexical query. */
  private void add(List<String> destination, List<String> values) {
    if (values != null) {
      values.stream().filter(this::hasText).map(String::trim).forEach(destination::add);
    }
  }

  private boolean hasPositiveSemanticContent(SemanticQuery query) {
    return query != null
        && (hasValues(query.getTopics())
            || hasValues(query.getGenres())
            || hasValues(query.getKeywords()));
  }

  private boolean hasNegativeSemanticContent(SemanticQuery query) {
    return hasPositiveSemanticContent(query)
        || (query != null && (hasValues(query.getAuthors()) || hasValues(query.getNarrators())));
  }

  private boolean hasValues(List<String> values) {
    return values != null && values.stream().anyMatch(this::hasText);
  }

  /** Creates an immutable, null-safe filter list. */
  private List<String> copy(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /** Reports whether text contains at least one non-whitespace character. */
  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private record RangeBounds(Integer minimum, Integer maximum) {
    private static RangeBounds empty() {
      return new RangeBounds(null, null);
    }
  }
}
