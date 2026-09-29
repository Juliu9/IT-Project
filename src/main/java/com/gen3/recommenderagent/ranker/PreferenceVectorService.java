package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.Preferences;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Converts this request's explicit preferences into separate positive and negative vectors. */
@Service
public class PreferenceVectorService {

  private final EmbeddingIndexer embeddingIndexer;

  public PreferenceVectorService(EmbeddingIndexer embeddingIndexer) {
    this.embeddingIndexer = embeddingIndexer;
  }

  /** Embeds the request's include and exclude terms independently to preserve their polarity. */
  public PreferenceSignals create(SessionRequest request) {
    if (request == null) {
      return PreferenceSignals.empty();
    }
    Set<String> positive = new LinkedHashSet<>();
    Set<String> negative = new LinkedHashSet<>();
    add(request.getQuery(), positive, negative);
    add(request.getPreferences(), positive, negative);
    return new PreferenceSignals(
        embeddingIndexer.embedPreferenceTerms(positive.stream().toList()),
        embeddingIndexer.embedPreferenceTerms(negative.stream().toList()));
  }

  private void add(SemanticQuery semanticQuery, Set<String> positive, Set<String> negative) {
    if (semanticQuery == null) {
      return;
    }
    addAll(semanticQuery.getPositive(), positive);
    addAll(semanticQuery.getNegative(), negative);
  }

  private void add(Preferences preferences, Set<String> positive, Set<String> negative) {
    if (preferences == null) {
      return;
    }
    if (preferences.getInclude() != null) {
      preferences.getInclude().stream()
          .filter(this::hasText)
          .map(String::trim)
          .forEach(positive::add);
    }
    if (preferences.getExclude() != null) {
      preferences.getExclude().stream()
          .filter(this::hasText)
          .map(String::trim)
          .forEach(negative::add);
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private void addAll(java.util.List<String> values, Set<String> destination) {
    if (values != null) {
      values.stream().filter(this::hasText).map(String::trim).forEach(destination::add);
    }
  }
}
