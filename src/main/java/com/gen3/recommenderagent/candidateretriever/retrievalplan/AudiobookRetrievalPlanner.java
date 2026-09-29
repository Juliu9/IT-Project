package com.gen3.recommenderagent.candidateretriever.retrievalplan;

import com.gen3.recommenderagent.domain.session.Filter;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Converts parsed request fields into one immutable retrieval plan. */
@Component
public class AudiobookRetrievalPlanner {

  private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

  /** Separates semantic text, lexical terms, and exact payload constraints. */
  public AudiobookRetrievalPlan plan(int limit, SessionRequest request) {
    SemanticQuery positive = request == null ? null : request.getPositiveSemanticQuery();
    SemanticQuery negative = request == null ? null : request.getNegativeSemanticQuery();
    Filter filter = request == null ? null : request.getFilter();
    String keywordText = lexicalText(positive, "");
    AudiobookFilters filters =
        new AudiobookFilters(
            copy(positive == null ? null : positive.getAuthors()),
            copy(positive == null ? null : positive.getNarrators()),
            filter == null ? null : filter.getLanguage(),
            durationMinutes(filter == null ? null : filter.getDuration()));
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

  /** Parses simple values such as "under 10 hours" or "480 minutes" into minutes. */
  private Integer durationMinutes(String duration) {
    if (!hasText(duration)) {
      return null;
    }
    Matcher matcher = FIRST_NUMBER.matcher(duration);
    if (!matcher.find()) {
      return null;
    }
    int value = Integer.parseInt(matcher.group(1));
    return duration.toLowerCase(Locale.ROOT).contains("hour") ? value * 60 : value;
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
}
