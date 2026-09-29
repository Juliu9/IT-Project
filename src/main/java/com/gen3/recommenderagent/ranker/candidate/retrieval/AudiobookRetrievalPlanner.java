package com.gen3.recommenderagent.ranker.candidate.retrieval;

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
  private final IntentRetrievalPolicy policy;

  /** Uses the centralized intent policy when constructing plans. */
  public AudiobookRetrievalPlanner(IntentRetrievalPolicy policy) {
    this.policy = policy;
  }

  /** Separates semantic text, lexical terms, and exact payload constraints. */
  public AudiobookRetrievalPlan plan(int limit, SessionRequest request) {
    SemanticQuery semanticQuery = request == null ? null : request.getQuery();
    Filter filter = request == null ? null : request.getFilter();
    String rawText = request == null ? null : request.getRawText();
    String semanticText = hasText(rawText) ? rawText.trim() : "";
    String keywordText = lexicalText(semanticQuery, semanticText);
    AudiobookFilters filters =
        new AudiobookFilters(
            copy(semanticQuery == null ? null : semanticQuery.getAuthors()),
            copy(semanticQuery == null ? null : semanticQuery.getNarrators()),
            filter == null ? null : filter.getLanguage(),
            durationMinutes(filter == null ? null : filter.getDuration()));
    return new AudiobookRetrievalPlan(
        policy.modeFor(request == null ? null : request.getIntent()),
        keywordText,
        filters,
        Math.max(limit, 1));
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

  /** Creates an immutable, null-safe filter list. */
  private List<String> copy(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /** Reports whether text contains at least one non-whitespace character. */
  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
