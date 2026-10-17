package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves an incoming request against the conversational state held by a session. */
public final class ConversationState {

  public SessionRequest resolve(Session session, SessionRequest request) {
    SessionRequest effective = copyOf(request);
    if (session == null) {
      return effective;
    }
    if (session.isAwaitingPersonalisedPreferences() && acceptsPreferences(effective.getIntent())) {
      applyPreferences(effective, session);
      effective.setIntent(Intent.RECOMMENDATION);
      effective.setPersonalised(true);
    }
    if (effective.getIntent() == Intent.REFINE || effective.getIntent() == Intent.MORE_RESULTS) {
      applyPreviousSearch(effective, session);
    }
    if (isRecommendationIntent(effective.getIntent())) {
      applyPreferences(effective, session);
      effective.setBookCount(resolveBookCount(effective, session));
    }
    return effective;
  }

  public SessionPreferences mergedPreferences(Session session, SessionRequest request) {
    SessionRequest combined = copyOf(request);
    applyPreferences(combined, session);
    SessionPreferences preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(copy(combined.getPositiveSemanticQuery()));
    preferences.setNegativeSemanticQuery(copy(combined.getNegativeSemanticQuery()));
    preferences.setMustInclude(copy(combined.getMustInclude()));
    preferences.setMustNotInclude(copy(combined.getMustNotInclude()));
    return preferences;
  }

  public Integer resolveBookCount(SessionRequest request, Session session) {
    if (request != null && request.getBookCount() != null) {
      return request.getBookCount();
    }
    return session == null ? null : session.getBookCount();
  }

  public boolean isRecommendationIntent(Intent intent) {
    return intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS;
  }

  public static boolean isSearchContext(SessionRequest request) {
    return request != null
        && request.getIntent() != Intent.UPDATE_PREFERENCES
        && (request.isPersonalised()
            || request.getPositiveSemanticQuery() != null
            || request.getNegativeSemanticQuery() != null
            || request.getMustInclude() != null
            || request.getMustNotInclude() != null);
  }

  private boolean acceptsPreferences(Intent intent) {
    return intent == Intent.UPDATE_PREFERENCES
        || intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS
        || intent == Intent.UNKNOWN;
  }

  private void applyPreviousSearch(SessionRequest target, Session session) {
    List<SessionRequest> requests = session.getRequests();
    if (requests == null) {
      return;
    }
    for (int index = requests.size() - 1; index >= 0; index--) {
      SessionRequest previous = requests.get(index);
      if (isSearchContext(previous)) {
        merge(previous, target);
        return;
      }
    }
  }

  private void applyPreferences(SessionRequest target, Session session) {
    if (target == null || session == null || session.getPreferences() == null) {
      return;
    }
    SessionPreferences preferences = session.getPreferences();
    SessionRequest base = new SessionRequest();
    base.setPositiveSemanticQuery(preferences.getPositiveSemanticQuery());
    base.setNegativeSemanticQuery(preferences.getNegativeSemanticQuery());
    base.setMustInclude(preferences.getMustInclude());
    base.setMustNotInclude(preferences.getMustNotInclude());
    merge(base, target);
  }

  private void merge(SessionRequest base, SessionRequest target) {
    target.setPersonalised(base.isPersonalised() || target.isPersonalised());
    target.setPositiveSemanticQuery(
        merge(base.getPositiveSemanticQuery(), target.getPositiveSemanticQuery()));
    target.setNegativeSemanticQuery(
        merge(base.getNegativeSemanticQuery(), target.getNegativeSemanticQuery()));
    target.setMustInclude(merge(base.getMustInclude(), target.getMustInclude()));
    target.setMustNotInclude(merge(base.getMustNotInclude(), target.getMustNotInclude()));
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
    merged.setPublicationYear(
        preferred(
            base == null ? null : base.getPublicationYear(),
            override == null ? null : override.getPublicationYear()));
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
    merged.setPublicationYear(
        preferred(
            base == null ? null : base.getPublicationYear(),
            override == null ? null : override.getPublicationYear()));
    merged.setSource(
        preferred(
            base == null ? null : base.getSource(),
            override == null ? null : override.getSource()));
    return merged;
  }

  private SessionRequest copyOf(SessionRequest source) {
    SessionRequest copy = new SessionRequest();
    copy.setIntent(Intent.UNKNOWN);
    if (source == null) {
      return copy;
    }
    copy.setRequestId(source.getRequestId());
    copy.setRawText(source.getRawText());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setIntent(source.getIntent() == null ? Intent.UNKNOWN : source.getIntent());
    copy.setPositiveSemanticQuery(copy(source.getPositiveSemanticQuery()));
    copy.setNegativeSemanticQuery(copy(source.getNegativeSemanticQuery()));
    copy.setMustInclude(copy(source.getMustInclude()));
    copy.setMustNotInclude(copy(source.getMustNotInclude()));
    copy.setBookCount(source.getBookCount());
    copy.setPersonalised(source.isPersonalised());
    copy.setPreferenceClarificationRequired(source.isPreferenceClarificationRequired());
    return copy;
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
