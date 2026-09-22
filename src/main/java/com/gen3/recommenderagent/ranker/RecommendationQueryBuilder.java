package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.springframework.stereotype.Component;

@Component
public class RecommendationQueryBuilder {

  public String build(SessionRequest request) {
    Set<String> generalTerms = new LinkedHashSet<>();
    Set<String> authors = new LinkedHashSet<>();
    Set<String> excludedTerms = new LinkedHashSet<>();

    if (request.getQuery() != null) {
      addTerms(generalTerms, request.getQuery().getTopics());
      addTerms(generalTerms, request.getQuery().getGenres());
      addTerms(generalTerms, request.getQuery().getKeywords());
      addTerms(authors, request.getQuery().getAuthors());
    }

    if (request.getPreferences() != null) {
      addTerms(generalTerms, request.getPreferences().getInclude());
      addTerms(excludedTerms, request.getPreferences().getExclude());
    }

    List<String> clauses = new ArrayList<>();

    if (!generalTerms.isEmpty()) {
      clauses.add(buildFieldClause("all", generalTerms));
    }

    if (!authors.isEmpty()) {
      clauses.add(buildFieldClause("authors", authors));
    }

    String positiveQuery = clauses.isEmpty() ? "*:*" : String.join(" AND ", clauses);

    if (!excludedTerms.isEmpty()) {
      positiveQuery += " AND -" + buildFieldClause("all", excludedTerms);
    }

    return positiveQuery;
  }

  private void addTerms(Set<String> destination, List<String> terms) {
    if (terms == null) {
      return;
    }

    terms.stream()
        .filter(term -> term != null && !term.isBlank())
        .map(String::trim)
        .forEach(destination::add);
  }

  private String buildFieldClause(String field, Set<String> terms) {
    String values =
        terms.stream()
            .map(ClientUtils::escapeQueryChars)
            .map(term -> "\"" + term + "\"")
            .reduce((left, right) -> left + " OR " + right)
            .orElseThrow();

    return field + ":(" + values + ")";
  }
}
