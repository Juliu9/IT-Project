package com.gen3.recommenderagent.retrieval.plan;

import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.shared.audiobookcatalogue.model.AudiobookFilters;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Selects a retrieval plan from resolved criteria and normalized exact filters. */
@Component
public class AudiobookRetrievalPlanner {
  private final ExactFilterPolicy filterPolicy;

  /** Creates the retrieval planner with the default exact-filter policy. */
  public AudiobookRetrievalPlanner() {
    this(new ExactFilterPolicy());
  }

  /** Creates the retrieval planner with default or explicitly supplied exact-filter policy. */
  AudiobookRetrievalPlanner(Clock clock) {
    this(new ExactFilterPolicy(clock));
  }

  /** Creates the retrieval planner with default or explicitly supplied exact-filter policy. */
  @Autowired
  public AudiobookRetrievalPlanner(ExactFilterPolicy filterPolicy) {
    this.filterPolicy = filterPolicy;
  }

  /** Separates semantic text, lexical terms, and exact payload constraints. */
  public AudiobookRetrievalPlan plan(
      int limit, SessionRequest request, Set<String> excludedBookIds) {
    SemanticQuery positive = request == null ? null : request.getPositiveSemanticQuery();
    SemanticQuery negative = request == null ? null : request.getNegativeSemanticQuery();
    String keywordText = lexicalText(positive, "");
    AudiobookFilters filters = filterPolicy.filters(request, excludedBookIds);
    return new AudiobookRetrievalPlan(
        hasSemanticContent(positive) || hasSemanticContent(negative),
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
    return terms.isEmpty() ? fallback : String.join(" ", terms);
  }

  /** Adds nonblank parsed terms to the lexical query. */
  private void add(List<String> destination, List<String> values) {
    if (values != null) {
      values.stream().filter(this::hasText).map(String::trim).forEach(destination::add);
    }
  }

  /** Reports whether the request contains semantic evidence usable by a retrieval plan. */
  private boolean hasSemanticContent(SemanticQuery query) {
    return query != null && query.hasValues();
  }

  /** Reports whether the supplied text contains a non-whitespace character. */
  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
