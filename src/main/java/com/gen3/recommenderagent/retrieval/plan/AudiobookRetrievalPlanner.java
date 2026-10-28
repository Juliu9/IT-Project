package com.gen3.recommenderagent.retrieval.plan;

import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
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

  public AudiobookRetrievalPlanner() {
    this(new ExactFilterPolicy());
  }

  AudiobookRetrievalPlanner(Clock clock) {
    this(new ExactFilterPolicy(clock));
  }

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
        modeFor(positive, negative, filters), keywordText, filters, Math.max(limit, 1));
  }

  /** Derives retrieval solely from searchable request content. */
  RetrievalMode modeFor(SemanticQuery positive, SemanticQuery negative, AudiobookFilters filters) {
    boolean hasSemantic = hasSemanticContent(positive) || hasSemanticContent(negative);
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

  /** Adds nonblank parsed terms to the lexical query. */
  private void add(List<String> destination, List<String> values) {
    if (values != null) {
      values.stream().filter(this::hasText).map(String::trim).forEach(destination::add);
    }
  }

  private boolean hasSemanticContent(SemanticQuery query) {
    return query != null
        && (hasValues(query.getTopics())
            || hasValues(query.getGenres())
            || hasValues(query.getKeywords())
            || hasValues(query.getAuthors())
            || hasValues(query.getNarrators()));
  }

  private boolean hasValues(List<String> values) {
    return values != null && values.stream().anyMatch(this::hasText);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
