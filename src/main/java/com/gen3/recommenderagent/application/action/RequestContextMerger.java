package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Combines explicit request fields with prior search criteria and session-level preferences. */
@Component
public class RequestContextMerger {

  /** Adds the latest recommendation-producing request as the base for a follow-up request. */
  public SessionRequest applyPreviousSearch(SessionRequest target, Session session) {
    if (target == null || session == null || session.getRequests() == null) {
      return target;
    }
    List<SessionRequest> requests = session.getRequests();
    for (int index = requests.size() - 1; index >= 0; index--) {
      SessionRequest previous = requests.get(index);
      if (hasSearchCriteria(previous)) {
        merge(previous, target);
        break;
      }
    }
    return target;
  }

  /** Applies persistent session preferences as defaults underneath explicit request values. */
  public SessionRequest applyPreferences(SessionRequest target, Session session) {
    if (target == null || session == null || session.getPreferences() == null) {
      return target;
    }
    SessionPreferences preferences = session.getPreferences();
    SessionRequest base = new SessionRequest();
    base.setPositiveSemanticQuery(preferences.getPositiveSemanticQuery());
    base.setNegativeSemanticQuery(preferences.getNegativeSemanticQuery());
    base.setMustInclude(preferences.getMustInclude());
    base.setMustNotInclude(preferences.getMustNotInclude());
    merge(base, target);
    return target;
  }

  /**
   * Merges new preference fields into the session without retaining request metadata or results.
   */
  public void updatePreferences(Session session, SessionRequest request) {
    if (session == null || request == null) {
      return;
    }
    SessionRequest combined = new SessionRequest();
    combined.setPositiveSemanticQuery(copy(request.getPositiveSemanticQuery()));
    combined.setNegativeSemanticQuery(copy(request.getNegativeSemanticQuery()));
    combined.setMustInclude(copy(request.getMustInclude()));
    combined.setMustNotInclude(copy(request.getMustNotInclude()));
    applyPreferences(combined, session);
    SessionPreferences preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(copy(combined.getPositiveSemanticQuery()));
    preferences.setNegativeSemanticQuery(copy(combined.getNegativeSemanticQuery()));
    preferences.setMustInclude(copy(combined.getMustInclude()));
    preferences.setMustNotInclude(copy(combined.getMustNotInclude()));
    session.setPreferences(preferences);
  }

  /** Stores an explicit count and writes the effective session count back to the request. */
  public void applyBookCount(SessionRequest request, Session session) {
    if (request == null || session == null) {
      return;
    }
    if (request.getBookCount() != null) {
      session.setBookCount(request.getBookCount());
    }
    request.setBookCount(session.getBookCount());
  }

  private void merge(SessionRequest base, SessionRequest target) {
    target.setPositiveSemanticQuery(
        merge(base.getPositiveSemanticQuery(), target.getPositiveSemanticQuery()));
    target.setNegativeSemanticQuery(
        merge(base.getNegativeSemanticQuery(), target.getNegativeSemanticQuery()));
    target.setMustInclude(merge(base.getMustInclude(), target.getMustInclude()));
    target.setMustNotInclude(merge(base.getMustNotInclude(), target.getMustNotInclude()));
  }

  private boolean hasSearchCriteria(SessionRequest request) {
    return request != null
        && request.getIntent() != Intent.UPDATE_PREFERENCES
        && (request.getPositiveSemanticQuery() != null
            || request.getNegativeSemanticQuery() != null
            || request.getMustInclude() != null
            || request.getMustNotInclude() != null);
  }

  private SemanticQuery merge(SemanticQuery base, SemanticQuery override) {
    if (base == null && override == null) {
      return null;
    }
    SemanticQuery merged = new SemanticQuery();
    merged.setTopics(
        union(values(base, SemanticQuery::getTopics), values(override, SemanticQuery::getTopics)));
    merged.setGenres(
        union(values(base, SemanticQuery::getGenres), values(override, SemanticQuery::getGenres)));
    merged.setAuthors(
        union(
            values(base, SemanticQuery::getAuthors), values(override, SemanticQuery::getAuthors)));
    merged.setNarrators(
        union(
            values(base, SemanticQuery::getNarrators),
            values(override, SemanticQuery::getNarrators)));
    merged.setKeywords(
        union(
            values(base, SemanticQuery::getKeywords),
            values(override, SemanticQuery::getKeywords)));
    return merged;
  }

  private MustInclude merge(MustInclude base, MustInclude override) {
    if (base == null && override == null) {
      return null;
    }
    MustInclude merged = new MustInclude();
    merged.setAuthors(
        union(
            base == null ? null : base.getAuthors(),
            override == null ? null : override.getAuthors()));
    merged.setNarrators(
        union(
            base == null ? null : base.getNarrators(),
            override == null ? null : override.getNarrators()));
    merged.setLanguage(
        preferred(
            base == null ? null : base.getLanguage(),
            override == null ? null : override.getLanguage()));
    merged.setDuration(
        preferred(
            base == null ? null : base.getDuration(),
            override == null ? null : override.getDuration()));
    merged.setSource(
        preferred(
            base == null ? null : base.getSource(),
            override == null ? null : override.getSource()));
    return merged;
  }

  private MustNotInclude merge(MustNotInclude base, MustNotInclude override) {
    if (base == null && override == null) {
      return null;
    }
    MustNotInclude merged = new MustNotInclude();
    merged.setAuthors(
        union(
            base == null ? null : base.getAuthors(),
            override == null ? null : override.getAuthors()));
    merged.setNarrators(
        union(
            base == null ? null : base.getNarrators(),
            override == null ? null : override.getNarrators()));
    merged.setLanguage(
        preferred(
            base == null ? null : base.getLanguage(),
            override == null ? null : override.getLanguage()));
    merged.setDuration(
        preferred(
            base == null ? null : base.getDuration(),
            override == null ? null : override.getDuration()));
    merged.setSource(
        preferred(
            base == null ? null : base.getSource(),
            override == null ? null : override.getSource()));
    return merged;
  }

  private SemanticQuery copy(SemanticQuery source) {
    return merge(null, source);
  }

  private MustInclude copy(MustInclude source) {
    return merge(null, source);
  }

  private MustNotInclude copy(MustNotInclude source) {
    return merge(null, source);
  }

  private List<String> values(
      SemanticQuery query, java.util.function.Function<SemanticQuery, List<String>> getter) {
    return query == null ? null : getter.apply(query);
  }

  private List<String> union(List<String> base, List<String> override) {
    Map<String, String> unique = new LinkedHashMap<>();
    add(unique, base);
    add(unique, override);
    return unique.isEmpty() ? null : new ArrayList<>(unique.values());
  }

  private void add(Map<String, String> unique, List<String> values) {
    if (values == null) {
      return;
    }
    values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .forEach(value -> unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
  }

  private String preferred(String base, String override) {
    return override == null || override.isBlank() ? base : override;
  }
}
