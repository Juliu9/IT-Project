package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Conversation policy applied before and after recommendation work in the same session update. */
public final class ConversationState {

  private static final Pattern SELECTION_REPLY =
      Pattern.compile(
          "(?i)^(?:(?:the|number|book)\\s+)?([0-9]+(?:st|nd|rd|th)?|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?:\\s+(?:one|book))?(?:\\s+please)?[.!?]?$");

  /** Resolves context and invalidates an obsolete queue before recommendation work begins. */
  public SessionRequest begin(Session session, SessionRequest original) {
    return begin(session, original, request -> {});
  }

  public SessionRequest begin(
      Session session, SessionRequest original, Consumer<SessionRequest> validate) {
    SessionRequest request = resolve(session, original);
    validate.accept(request);
    // Invalidation uses the original action because clarification replies can become searches.
    if (request.getIntent() != Intent.REPEAT
        && request.getIntent() != Intent.EXPLAIN_RECOMMENDATION
        && request.getIntent() != Intent.CHANGE_COUNT
        && request.getIntent() != Intent.CANCEL
        && !request.isSearchClarificationRequired()
        && invalidatesBatch(original)) {
      session.setPendingRecommendations(List.of());
    }
    return request;
  }

  public boolean needsRecommendations(SessionRequest request) {
    return !request.isSearchClarificationRequired() && isRecommendationIntent(request.getIntent());
  }

  /** Records the completed transition and resolves wording-independent response policy. */
  public void finish(Session session, SessionRequest request, SessionRequest original) {
    if (request.getIntent() == Intent.REPEAT) {
      complete(request);
      if (request.getDecision() == null) {
        request.setDecision(decisionFor(request.getRecommendations(), request));
      }
      return;
    }
    if (request.getIntent() == Intent.EXPLAIN_RECOMMENDATION
        || request.getIntent() == Intent.REPEAT_RECOMMENDATIONS) {
      complete(request);
      remember(session, request);
      return;
    }
    // Asking for search criteria must not replace the retained search.
    if (request.isSearchClarificationRequired()) {
      request.setRecommendations(List.of());
      complete(request);
    } else {
      if (!isRecommendationIntent(request.getIntent())) {
        request.setRecommendations(List.of());
      }
      request.setNoMoreRecommendations(
          request.getIntent() == Intent.MORE_RESULTS && request.getRecommendations().isEmpty());
      applyTransition(session, request, originalIntent(original));
    }
    request.setDecision(decisionFor(request.getRecommendations(), request));
    remember(session, request);
  }

  private void remember(Session session, SessionRequest request) {
    session.setLastRequest(historyEntry(request));
    session.setLastDecision(request.getDecision());
  }

  /** Shared eligibility rule for parsed inputs and restored conversation requests. */
  public static SessionRequest normalizeInput(SessionRequest request) {
    // Control intents may carry exact filters, but cannot introduce new semantic preferences.
    if (!acceptsSemanticInformation(request.getIntent())) {
      request.setPositiveSemanticQuery(null);
      request.setNegativeSemanticQuery(null);
      request.setPersonalised(false);
    }
    if (request.getIntent() != Intent.EXPLAIN_RECOMMENDATION) {
      request.setReferenceRank(null);
      request.setReferenceTitle(null);
    }
    return request;
  }

  private static boolean acceptsSemanticInformation(Intent intent) {
    return intent == Intent.RECOMMENDATION || intent == Intent.REFINE;
  }

  public static ConversationDecision decisionFor(
      List<Recommendation> recommendations, SessionRequest request) {
    if (recommendations == null && request == null) {
      return ConversationDecision.UNAVAILABLE;
    }
    // Clarification outranks actions; actions outrank empty-result messages.
    if (request != null) {
      if (request.isSearchClarificationRequired()) {
        return ConversationDecision.SEARCH_CLARIFICATION;
      }
      if (request.isPreferenceClarificationRequired()) {
        return ConversationDecision.PREFERENCE_CLARIFICATION;
      }
      if (request.getIntent() != null) {
        ConversationDecision action =
            switch (request.getIntent()) {
              case EXPLAIN_RECOMMENDATION ->
                  recommendations == null || recommendations.isEmpty()
                      ? ConversationDecision.NOTHING_TO_EXPLAIN
                      : ConversationDecision.EXPLANATION;
              case CHANGE_COUNT ->
                  request.getBookCount() == null
                      ? ConversationDecision.COUNT_CLARIFICATION
                      : ConversationDecision.COUNT_CHANGED;
              case UPDATE_PREFERENCES -> ConversationDecision.PREFERENCES_UPDATED;
              case CLEAR_HISTORY -> ConversationDecision.HISTORY_CLEARED;
              case HELP -> ConversationDecision.HELP;
              case CANCEL -> ConversationDecision.CANCELLED;
              case UNKNOWN -> ConversationDecision.UNKNOWN;
              case RECOMMENDATION, REFINE, MORE_RESULTS, REPEAT, REPEAT_RECOMMENDATIONS -> null;
            };
        if (action != null) {
          return action;
        }
      }
    }
    if (recommendations == null || recommendations.isEmpty()) {
      if (request != null
          && (request.isNoMoreRecommendations() || request.getIntent() == Intent.MORE_RESULTS)) {
        return ConversationDecision.NO_MORE_RESULTS;
      }
      if (request != null && request.getIntent() == Intent.REPEAT_RECOMMENDATIONS) {
        return ConversationDecision.NO_RECOMMENDATIONS_TO_REPEAT;
      }
      if (request != null && request.getIntent() == Intent.REPEAT) {
        return ConversationDecision.NOTHING_TO_REPEAT;
      }
      return ConversationDecision.NO_MATCHES;
    }
    return ConversationDecision.RECOMMENDATIONS;
  }

  static boolean awaitingPreferences(Session session) {
    return session.getRequests() != null
        && session.getRequests().stream()
            .anyMatch(SessionRequest::isPreferenceClarificationRequired);
  }

  static void retainRequest(Session session, SessionRequest request) {
    if (request == null) {
      return;
    }
    // Keep one search and one non-search entry so actions do not erase follow-up context.
    boolean search = isSearchContext(request);
    session.getRequests().removeIf(existing -> isSearchContext(existing) == search);
    session.getRequests().add(request);
    session.setUpdatedAt(Instant.now());
  }

  private boolean invalidatesBatch(SessionRequest request) {
    Intent intent = originalIntent(request);
    SessionRequest filters = new SessionRequest();
    if (request != null) {
      filters.setMustInclude(request.getMustInclude());
      filters.setMustNotInclude(request.getMustNotInclude());
    }
    return intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.UPDATE_PREFERENCES
        || intent == Intent.CLEAR_HISTORY
        || (intent == Intent.MORE_RESULTS && hasSearchCriteria(filters));
  }

  private void applyTransition(Session session, SessionRequest request, Intent originalIntent) {
    // Persist a taste reply before history replacement removes the pending clarification.
    if (request.getIntent() != Intent.CANCEL
        && (originalIntent == Intent.UPDATE_PREFERENCES
            || ((originalIntent == Intent.REFINE || originalIntent == Intent.RECOMMENDATION)
                && awaitingPreferences(session)
                && !request.isPreferenceClarificationRequired()))) {
      session.setPreferences(mergedPreferences(session, request));
    }
    if (request.getIntent() == Intent.CHANGE_COUNT) {
      session.setBookCount(resolveBookCount(request, session));
    }
    if (request.getIntent() == Intent.CANCEL) {
      session.getRequests().forEach(previous -> previous.setPreferenceClarificationRequired(false));
    }
    if (request.getIntent() == Intent.CLEAR_HISTORY) {
      session.clearHistory();
    }
    complete(request);
    session.addRecommendationIds(recommendationIds(request.getRecommendations()));
    retainRequest(session, historyEntry(request));
  }

  private void complete(SessionRequest request) {
    if (request.getRequestId() == null) {
      request.setRequestId(UUID.randomUUID().toString());
    }
    if (request.getCreatedAt() == null) {
      request.setCreatedAt(Instant.now());
    }
  }

  private Intent originalIntent(SessionRequest request) {
    return request == null || request.getIntent() == null ? Intent.UNKNOWN : request.getIntent();
  }

  private List<String> recommendationIds(List<Recommendation> recommendations) {
    return recommendations.stream()
        .filter(recommendation -> recommendation != null)
        .map(Recommendation::getBookId)
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .toList();
  }

  // Persist only reusable context; raw text and the temporary decision stay out of history.
  private SessionRequest historyEntry(SessionRequest request) {
    SessionRequest entry = new SessionRequest();
    entry.setRequestId(request.getRequestId());
    entry.setCreatedAt(request.getCreatedAt());
    entry.setIntent(request.getIntent());
    entry.setPersonalised(request.isPersonalised());
    entry.setPreferenceClarificationRequired(request.isPreferenceClarificationRequired());
    entry.setPositiveSemanticQuery(request.getPositiveSemanticQuery());
    entry.setNegativeSemanticQuery(request.getNegativeSemanticQuery());
    entry.setMustInclude(request.getMustInclude());
    entry.setMustNotInclude(request.getMustNotInclude());
    entry.setBookCount(request.getBookCount());
    entry.setRecommendations(request.getRecommendations());
    entry.setNoMoreRecommendations(request.isNoMoreRecommendations());
    entry.setSearchClarificationRequired(request.isSearchClarificationRequired());
    entry.setReferenceRank(request.getReferenceRank());
    entry.setReferenceTitle(request.getReferenceTitle());
    return entry;
  }

  SessionRequest resolve(Session session, SessionRequest request) {
    SessionRequest effective = copyOf(request);
    if (effective.getIntent() == Intent.RECOMMENDATION && !hasSearchCriteria(effective)) {
      effective.setPersonalised(true);
    }
    if (session == null) {
      if (effective.getIntent() == Intent.EXPLAIN_RECOMMENDATION) {
        effective.setRecommendations(List.of());
        effective.setDecision(ConversationDecision.NOTHING_TO_EXPLAIN);
      }
      return effective;
    }
    String reply = effective.getRawText() == null ? "" : effective.getRawText().strip();
    if (reply.matches("(?i)(?:never mind|nevermind|cancel|stop|forget it)[.!?]?")) {
      effective.setIntent(Intent.CANCEL);
    }
    if (effective.getIntent() == Intent.CANCEL) {
      return normalizeInput(effective);
    }
    // The parser has no session context; resolve short answers against the outstanding question.
    Integer selection = selectionReply(reply);
    if (session.getLastDecision() == ConversationDecision.COUNT_CLARIFICATION
        && selection != null) {
      effective.setIntent(Intent.CHANGE_COUNT);
      normalizeInput(effective);
      effective.setBookCount(selection);
    }
    if (session.getLastDecision() == ConversationDecision.EXPLANATION_TARGET_CLARIFICATION
        && (selection != null || (effective.getIntent() == Intent.UNKNOWN && !reply.isBlank()))) {
      effective.setIntent(Intent.EXPLAIN_RECOMMENDATION);
      normalizeInput(effective);
      effective.setReferenceRank(selection);
      effective.setReferenceTitle(selection == null ? reply : null);
    }
    if (effective.getIntent() == Intent.EXPLAIN_RECOMMENDATION) {
      resolveExplanation(effective, session);
      return effective;
    }
    if (effective.getIntent() == Intent.REPEAT) {
      return resolveRepeat(session, effective);
    }
    if (effective.getIntent() == Intent.REPEAT_RECOMMENDATIONS) {
      SessionRequest previous = latestSearch(session);
      SessionRequest repeated = restoreRequest(previous, effective);
      repeated.setPreferenceClarificationRequired(false);
      repeated.setSearchClarificationRequired(false);
      repeated.setNoMoreRecommendations(
          previous != null
              && (previous.isNoMoreRecommendations() || previous.getIntent() == Intent.MORE_RESULTS)
              && repeated.getRecommendations().isEmpty());
      repeated.setDecision(decisionFor(repeated.getRecommendations(), repeated));
      return repeated;
    }
    // Check before preference handling: MORE cannot invent a first search.
    if (effective.getIntent() == Intent.MORE_RESULTS
        && (session.getRequests() == null
            || session.getRequests().stream()
                .noneMatch(
                    previous ->
                        previous != null && isRecommendationIntent(previous.getIntent())))) {
      effective.setSearchClarificationRequired(true);
      return effective;
    }
    if (effective.getIntent() == Intent.REFINE
        && latestSearch(session) == null
        && !awaitingPreferences(session)) {
      if (!hasSearchCriteria(effective)) {
        effective.setSearchClarificationRequired(true);
        return effective;
      }
      effective.setIntent(Intent.RECOMMENDATION);
    }
    if (awaitingPreferences(session) && acceptsPreferences(effective.getIntent())) {
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
    if (effective.getIntent() == Intent.UPDATE_PREFERENCES) {
      applyPreferences(effective, session);
    }
    return effective;
  }

  private Integer selectionReply(String reply) {
    var match = SELECTION_REPLY.matcher(reply);
    if (!match.matches()) {
      return null;
    }
    return switch (match.group(1).toLowerCase(Locale.ROOT)) {
      case "one", "first" -> 1;
      case "two", "second" -> 2;
      case "three", "third" -> 3;
      case "four", "fourth" -> 4;
      case "five", "fifth" -> 5;
      case "six", "sixth" -> 6;
      case "seven", "seventh" -> 7;
      case "eight", "eighth" -> 8;
      case "nine", "ninth" -> 9;
      case "ten", "tenth" -> 10;
      default -> {
        try {
          yield Integer.valueOf(match.group(1).replaceFirst("(?i)(st|nd|rd|th)$", ""));
        } catch (NumberFormatException ignored) {
          yield Integer.MAX_VALUE;
        }
      }
    };
  }

  private SessionRequest resolveRepeat(Session session, SessionRequest incoming) {
    SessionRequest previous = session.getLastRequest();
    ConversationDecision decision = session.getLastDecision();
    if (previous == null && session.getRequests() != null) {
      // Sessions saved before replay snapshots can still repeat their latest retained request.
      previous =
          session.getRequests().stream()
              .filter(java.util.Objects::nonNull)
              .reduce((first, second) -> second)
              .orElse(null);
      decision = null;
    }
    if (previous == null) {
      incoming.setRecommendations(List.of());
      incoming.setDecision(ConversationDecision.NOTHING_TO_REPEAT);
      return incoming;
    }
    SessionRequest repeated = restoreRequest(previous, incoming);
    repeated.setDecision(
        decision == null ? decisionFor(repeated.getRecommendations(), previous) : decision);
    return repeated;
  }

  private SessionRequest restoreRequest(SessionRequest previous, SessionRequest incoming) {
    SessionRequest repeated = previous == null ? new SessionRequest() : historyEntry(previous);
    repeated.setIntent(incoming.getIntent());
    repeated.setRequestId(incoming.getRequestId());
    repeated.setCreatedAt(incoming.getCreatedAt());
    repeated.setRawText(incoming.getRawText());
    if (repeated.getRecommendations() == null) {
      repeated.setRecommendations(List.of());
    }
    return repeated;
  }

  public SessionRequest latestSearch(Session session) {
    return session.getRequests() == null
        ? null
        : session.getRequests().stream()
            .filter(ConversationState::isSearchContext)
            .reduce((first, second) -> second)
            .orElse(null);
  }

  private void resolveExplanation(SessionRequest target, Session session) {
    target.setRecommendations(List.of());
    target.setPreferenceClarificationRequired(false);
    target.setMustInclude(null);
    target.setMustNotInclude(null);
    target.setBookCount(null);
    SessionRequest previous = latestSearch(session);
    if (previous == null) {
      target.setDecision(ConversationDecision.NOTHING_TO_EXPLAIN);
      return;
    }
    merge(previous, target);
    List<Recommendation> page =
        previous.getRecommendations() == null
            ? List.of()
            : previous.getRecommendations().stream()
                .sorted(java.util.Comparator.comparing(Recommendation::getRank))
                .toList();
    target.setRecommendations(page);
    if (page.isEmpty()) {
      target.setDecision(ConversationDecision.EMPTY_EXPLANATION_PAGE);
      return;
    }
    Integer rank = target.getReferenceRank();
    String title = normalizeTitle(target.getReferenceTitle());
    List<Recommendation> matches = page;
    if (!title.isEmpty()) {
      List<Recommendation> exact =
          page.stream().filter(book -> normalizeTitle(book.getTitle()).equals(title)).toList();
      matches =
          exact.isEmpty()
              ? page.stream()
                  .filter(book -> normalizeTitle(book.getTitle()).contains(title))
                  .toList()
              : exact;
    }
    // Resolve a title independently before applying rank; an ambiguous title needs clarification.
    if ((title.isEmpty()
            && target.getReferenceTitle() != null
            && !target.getReferenceTitle().isBlank())
        || (!title.isEmpty() && matches.size() != 1)
        || (rank != null && matches.stream().noneMatch(book -> rank.equals(book.getRank())))) {
      target.setDecision(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
      return;
    }
    if (rank != null) {
      matches = matches.stream().filter(book -> rank.equals(book.getRank())).toList();
    }
    target.setRecommendations(matches);
    target.setDecision(ConversationDecision.EXPLANATION);
  }

  private String normalizeTitle(String title) {
    return title == null
        ? ""
        : title
            .replace("$b", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim();
  }

  private SessionPreferences mergedPreferences(Session session, SessionRequest request) {
    SessionRequest combined = copyOf(request);
    SessionPreferences preferences = new SessionPreferences();
    preferences.setPositiveSemanticQuery(copy(combined.getPositiveSemanticQuery()));
    preferences.setNegativeSemanticQuery(copy(combined.getNegativeSemanticQuery()));
    preferences.setMustInclude(copy(combined.getMustInclude()));
    preferences.setMustNotInclude(copy(combined.getMustNotInclude()));
    return preferences;
  }

  private Integer resolveBookCount(SessionRequest request, Session session) {
    if (request != null && request.getBookCount() != null) {
      return request.getBookCount();
    }
    return session == null ? null : session.getBookCount();
  }

  private static boolean isRecommendationIntent(Intent intent) {
    return intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS;
  }

  static boolean isSearchContext(SessionRequest request) {
    return request != null
        && (request.getIntent() == Intent.RECOMMENDATION
            || request.getIntent() == Intent.REFINE
            || request.getIntent() == Intent.MORE_RESULTS);
  }

  private static boolean hasSearchCriteria(SessionRequest request) {
    if (request == null) {
      return false;
    }
    MustInclude include = request.getMustInclude();
    MustNotInclude exclude = request.getMustNotInclude();
    return request.isPersonalised()
        || hasQuery(request.getPositiveSemanticQuery())
        || hasQuery(request.getNegativeSemanticQuery())
        || (include != null
            && (hasTerms(include.getAuthors())
                || hasTerms(include.getNarrators())
                || hasText(
                    include.getLanguage(),
                    include.getDuration(),
                    include.getPublicationYear(),
                    include.getSource())))
        || (exclude != null
            && (hasTerms(exclude.getAuthors())
                || hasTerms(exclude.getNarrators())
                || hasText(
                    exclude.getLanguage(),
                    exclude.getDuration(),
                    exclude.getPublicationYear(),
                    exclude.getSource())));
  }

  private static boolean hasQuery(SemanticQuery query) {
    return query != null
        && (hasTerms(query.getTopics())
            || hasTerms(query.getGenres())
            || hasTerms(query.getAuthors())
            || hasTerms(query.getNarrators())
            || hasTerms(query.getKeywords()));
  }

  private static boolean hasTerms(List<String> terms) {
    return terms != null && terms.stream().anyMatch(value -> value != null && !value.isBlank());
  }

  private static boolean hasText(String... values) {
    return Stream.of(values).anyMatch(value -> value != null && !value.isBlank());
  }

  private boolean acceptsPreferences(Intent intent) {
    return intent == Intent.UPDATE_PREFERENCES
        || intent == Intent.RECOMMENDATION
        || intent == Intent.REFINE
        || intent == Intent.MORE_RESULTS;
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
    MustInclude explicitInclude = target.getMustInclude();
    MustNotInclude explicitExclude = target.getMustNotInclude();
    MustInclude included = merge(base.getMustInclude(), explicitInclude);
    MustNotInclude excluded = merge(base.getMustNotInclude(), explicitExclude);
    if (explicitInclude != null) {
      if (!target.isAddAuthors() && hasTerms(explicitInclude.getAuthors())) {
        included.setAuthors(union(null, explicitInclude.getAuthors()));
      }
      if (!target.isAddNarrators() && hasTerms(explicitInclude.getNarrators())) {
        included.setNarrators(union(null, explicitInclude.getNarrators()));
      }
    }
    if (explicitExclude != null) {
      if (!target.isAddAuthors() && hasTerms(explicitExclude.getAuthors())) {
        excluded.setAuthors(union(null, explicitExclude.getAuthors()));
      }
      if (!target.isAddNarrators() && hasTerms(explicitExclude.getNarrators())) {
        excluded.setNarrators(union(null, explicitExclude.getNarrators()));
      }
    }
    target.setMustInclude(included);
    target.setMustNotInclude(excluded);
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

  // Resolution must not mutate parser input or the saved criteria used by later requests.
  private SessionRequest copyOf(SessionRequest source) {
    SessionRequest copy = new SessionRequest();
    copy.setIntent(Intent.UNKNOWN);
    if (source == null) {
      return copy;
    }
    copy.setAddAuthors(source.isAddAuthors());
    copy.setAddNarrators(source.isAddNarrators());
    copy.setRequestId(source.getRequestId());
    copy.setRawText(source.getRawText());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setIntent(source.getIntent() == null ? Intent.UNKNOWN : source.getIntent());
    if (acceptsSemanticInformation(copy.getIntent())) {
      copy.setPositiveSemanticQuery(copy(source.getPositiveSemanticQuery()));
      copy.setNegativeSemanticQuery(copy(source.getNegativeSemanticQuery()));
      copy.setPersonalised(source.isPersonalised());
    }
    copy.setMustInclude(copy(source.getMustInclude()));
    copy.setMustNotInclude(copy(source.getMustNotInclude()));
    if (copy.getIntent() == Intent.EXPLAIN_RECOMMENDATION) {
      copy.setReferenceRank(source.getReferenceRank());
      copy.setReferenceTitle(source.getReferenceTitle());
    }
    copy.setBookCount(source.getBookCount());
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
