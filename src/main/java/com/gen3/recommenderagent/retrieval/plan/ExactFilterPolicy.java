package com.gen3.recommenderagent.retrieval.plan;

import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.catalogue.model.AudiobookFilters;
import com.gen3.catalogue.model.PersonNameNormalizer;
import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.PreferenceRequestMapper;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.application.conversation.workflow.ConversationHistory;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.search.DurationRange;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.DurationParser;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Owns exact-filter parsing, corrections and conflict validation. */
@Component
public class ExactFilterPolicy {

  private static final Pattern YEAR_RANGE =
      Pattern.compile(
          "^(?:between (\\d{4}|\\d{3}0s) and (\\d{4}|\\d{3}0s)|(?:from )?(\\d{4}|\\d{3}0s) *(?:-|to) *(\\d{4}|\\d{3}0s))$");
  private static final Pattern YEAR_COMPARISON =
      Pattern.compile(
          "^(?:(after|before|from|since|newer than|older than|over|under|more than|less than|at least|at most|minimum(?: of)?|maximum(?: of)?|up to|exactly) )?(\\d{4})$");

  private static final Pattern APPROXIMATE_YEAR =
      Pattern.compile("^(?:from )?(?:around|(?:the )?period) (\\d{4})$");
  private static final Pattern RELATIVE_YEARS =
      Pattern.compile(
          "^(?:in |within |from )?(?:the )?(?:last|past) (?:([0-9]+|a|an|one|two|three|four|five|six|seven|eight|nine|ten) )?years?$");
  private final Clock clock;

  public ExactFilterPolicy() {
    this(Clock.systemUTC());
  }

  ExactFilterPolicy(Clock clock) {
    this.clock = clock;
  }

  public AudiobookFilters filters(SessionRequest request, Set<String> excludedBookIds) {
    return new AudiobookFilters(
        conditions(request == null ? null : request.getMustInclude()),
        conditions(request == null ? null : request.getMustNotInclude()),
        excludedBookIds == null ? List.of() : List.copyOf(excludedBookIds));
  }

  /** Checks the new message before reconciling corrections against inherited filters. */
  public void resolveFilterCorrections(SessionRequest resolved, SessionRequest incoming) {
    resolveFilterCorrections(resolved, incoming, null);
  }

  public void resolveFilterCorrections(
      SessionRequest resolved, SessionRequest incoming, SessionRequest previousSearch) {
    if ((resolved.getIntent() != ConversationAction.RECOMMENDATION
        && resolved.getIntent() != ConversationAction.REFINE
        && resolved.getIntent() != ConversationAction.MORE_RESULTS
        && resolved.getIntent() != ConversationAction.UPDATE_PREFERENCES)) {
      return;
    }
    if (incoming != null
        && incoming.getMustInclude() != null
        && ConversationText.isYearRefinement(incoming.getMustInclude().getPublicationYear())) {
      incoming = PreferenceRequestMapper.toRequest(PreferenceRequestMapper.fromRequest(incoming));
      incoming.getMustInclude().setPublicationYear(resolved.getMustInclude().getPublicationYear());
    }
    var own = filters(incoming, Set.of());
    requireCompatible(own);
    var inherited = filters(resolved, Set.of());
    var previous = filters(previousSearch, Set.of());
    for (String field : conflicts(inherited)) {
      var priority = own;
      if (!supplied(own.mustInclude(), field) && !supplied(own.mustNotInclude(), field)) {
        priority = previous;
      }
      boolean newInclude = supplied(priority.mustInclude(), field);
      boolean newExclude = supplied(priority.mustNotInclude(), field);
      if (newInclude && !newExclude) {
        clear(resolved.getMustNotInclude(), field, priority.mustInclude());
      } else if (newExclude && !newInclude) {
        clear(resolved.getMustInclude(), field, priority.mustNotInclude());
      }
    }
    requireCompatible(filters(resolved, Set.of()));
  }

  private void requireCompatible(AudiobookFilters filters) {
    var fields = conflicts(filters);
    if (!fields.isEmpty()) {
      throw new InvalidRequestException(
          "Your "
              + String.join(" and ", fields)
              + " requirements conflict. Please restate your full request with only the requirements you want to keep.");
    }
  }

  private List<String> conflicts(AudiobookFilters filters) {
    var include = filters.mustInclude();
    var exclude = filters.mustNotInclude();
    List<String> fields = new ArrayList<>();
    for (String field : List.of("authors", "narrators", "language", "source")) {
      var required = exactValues(include, field);
      var forbidden = exactValues(exclude, field);
      if (!required.isEmpty() && forbidden.containsAll(required)) {
        fields.add(field);
      }
    }
    if (supplied(include, "duration")
        && supplied(exclude, "duration")
        && covers(
            exclude.minimumDurationMinutes(),
            exclude.maximumDurationMinutes(),
            include.minimumDurationMinutes(),
            include.maximumDurationMinutes(),
            1,
            Integer.MAX_VALUE)) {
      fields.add("duration");
    }
    if (supplied(include, "publication year")
        && supplied(exclude, "publication year")
        && covers(
            exclude.minimumPublicationYear(),
            exclude.maximumPublicationYear(),
            include.minimumPublicationYear(),
            include.maximumPublicationYear(),
            0,
            9999)) {
      fields.add("publication year");
    }
    return fields;
  }

  private boolean covers(
      Integer outerMin,
      Integer outerMax,
      Integer innerMin,
      Integer innerMax,
      int floor,
      int ceiling) {
    return (outerMin == null ? floor : outerMin) <= (innerMin == null ? floor : innerMin)
        && (outerMax == null ? ceiling : outerMax) >= (innerMax == null ? ceiling : innerMax);
  }

  private List<String> exactValues(AudiobookFilterConditions filters, String field) {
    List<String> values =
        switch (field) {
          case "authors" -> filters.authors();
          case "narrators" -> filters.narrators();
          case "language" -> filters.language() == null ? List.of() : List.of(filters.language());
          case "source" -> filters.source() == null ? List.of() : List.of(filters.source());
          default -> List.of();
        };
    return values.stream()
        .filter(this::hasText)
        .map(String::trim)
        .map(value -> comparisonKey(value, field))
        .distinct()
        .toList();
  }

  private String comparisonKey(String value, String field) {
    return field.equals("authors") || field.equals("narrators")
        ? PersonNameNormalizer.comparisonKey(value)
        : value.trim().toLowerCase(Locale.ROOT);
  }

  private boolean supplied(AudiobookFilterConditions filters, String field) {
    return switch (field) {
      case "duration" ->
          filters.minimumDurationMinutes() != null || filters.maximumDurationMinutes() != null;
      case "publication year" ->
          filters.minimumPublicationYear() != null || filters.maximumPublicationYear() != null;
      default -> !exactValues(filters, field).isEmpty();
    };
  }

  private List<String> without(
      List<String> values, AudiobookFilterConditions correction, String field) {
    var removed = exactValues(correction, field);
    return values == null
        ? null
        : values.stream()
            .filter(value -> value != null && !removed.contains(comparisonKey(value, field)))
            .toList();
  }

  private void clear(ExactCriteria criteria, String field, AudiobookFilterConditions correction) {
    switch (field) {
      case "authors" -> criteria.setAuthors(without(criteria.getAuthors(), correction, field));
      case "narrators" ->
          criteria.setNarrators(without(criteria.getNarrators(), correction, field));
      case "language" -> criteria.setLanguage(null);
      case "source" -> criteria.setSource(null);
      case "duration" -> criteria.setDuration(null);
      case "publication year" -> criteria.setPublicationYear(null);
      default -> throw new IllegalArgumentException(field);
    }
  }

  private AudiobookFilterConditions conditions(ExactCriteria filter) {
    if (filter == null) {
      return AudiobookFilterConditions.empty();
    }
    DurationRange bounds = DurationParser.parse(filter.getDuration());
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
    String normalized = publicationYear.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    Matcher approximate = APPROXIMATE_YEAR.matcher(normalized);
    if (approximate.matches()) {
      int year = Integer.parseInt(approximate.group(1));
      if (year == 0) {
        throw new InvalidRequestException("Please give a publication year between 1 and 9999.");
      }
      return new RangeBounds(Math.max(1, year - 5), Math.min(9999, year + 5));
    }
    Matcher relative = RELATIVE_YEARS.matcher(normalized);
    if (normalized.equals("recent") || normalized.equals("recently") || relative.matches()) {
      return relativeYearBounds(normalized, relative);
    }
    if (normalized.matches("(?:(?:from|since) (?:the )?)?[0-9]{3}0s")) {
      normalized = normalized.replaceFirst("^(?:from|since) (?:the )?", "");
      int start = Integer.parseInt(normalized.substring(0, 4));
      return boundedYearRange(start, start + 9);
    }
    Matcher range = YEAR_RANGE.matcher(normalized);
    if (range.matches()) {
      String first = range.group(1) != null ? range.group(1) : range.group(3);
      String last = range.group(2) != null ? range.group(2) : range.group(4);
      int lower = Integer.parseInt(first.substring(0, 4));
      int upper = Integer.parseInt(last.substring(0, 4)) + (last.endsWith("s") ? 9 : 0);
      if (lower >= 1 && lower <= upper && upper <= 9999) {
        return new RangeBounds(lower, upper);
      }
    } else {
      Matcher comparison = YEAR_COMPARISON.matcher(normalized);
      if (comparison.matches()) {
        int year = Integer.parseInt(comparison.group(2));
        if (year == 0) {
          throw new InvalidRequestException("Please give a publication year between 1 and 9999.");
        }
        String operator = comparison.group(1) == null ? "exactly" : comparison.group(1);
        return switch (operator) {
          case "after", "newer than", "over", "more than" -> boundedYearRange(year + 1, null);
          case "before", "older than", "under", "less than" -> boundedYearRange(null, year - 1);
          case "from", "since", "at least", "minimum", "minimum of" -> new RangeBounds(year, null);
          case "at most", "maximum", "maximum of", "up to" -> new RangeBounds(null, year);
          default -> new RangeBounds(year, year);
        };
      }
    }
    throw new InvalidRequestException(
        "What publication year or year range would you like? You can also say 'after 2020' or 'in the last 5 years'.");
  }

  private RangeBounds relativeYearBounds(String normalized, Matcher relative) {
    int years;
    if (normalized.equals("recent") || normalized.equals("recently")) {
      years = 5;
    } else {
      years =
          switch (relative.group(1) == null ? "one" : relative.group(1)) {
            case "a", "an", "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            default -> {
              try {
                yield Integer.parseInt(relative.group(1));
              } catch (NumberFormatException exception) {
                throw new InvalidRequestException(
                    "Please give a whole number of years within the available calendar range.");
              }
            }
          };
    }
    int currentYear = Year.now(clock).getValue();
    if (years < 1 || years >= currentYear) {
      throw new InvalidRequestException(
          "Please give a positive number of years within the available calendar range.");
    }
    return new RangeBounds(currentYear - years, currentYear);
  }

  private RangeBounds boundedYearRange(Integer lower, Integer upper) {
    if ((lower != null && (lower < 1 || lower > 9999))
        || (upper != null && (upper < 1 || upper > 9999))) {
      throw new InvalidRequestException("Please give a publication year range within 1 to 9999.");
    }
    return new RangeBounds(lower, upper);
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

  private void resolveRecencyRefinement(ConversationDraft request, Session session) {
    if (request.getIntent() != ConversationAction.REFINE || request.getMustInclude() == null) {
      return;
    }
    String expression = request.getMustInclude().getPublicationYear();
    boolean older = ConversationText.isOlderRefinement(expression);
    boolean relative = ConversationText.isYearRefinement(expression);
    boolean answeringYear =
        session != null
            && session.getPendingDecision() == ConversationDecision.RECENCY_YEAR_CLARIFICATION;
    if (!relative && !answeringYear) {
      return;
    }
    var previous = session == null ? null : ConversationHistory.latestSearch(session);
    var page = previous == null ? null : previous.getRecommendations();
    var years =
        page == null
            ? List.<Integer>of()
            : page.stream()
                .map(
                    com.gen3.recommenderagent.domain.recommendation.Recommendation
                        ::getPublicationYear)
                .filter(year -> year != null && year > 0 && year <= 9999)
                .toList();
    if (older) {
      resolveOlderRefinement(request, session, previous, years);
    } else {
      resolveNewerRefinement(request, session, previous, years, relative);
    }
  }

  private void resolveOlderRefinement(
      ConversationDraft request, Session session, ConversationDraft previous, List<Integer> years) {
    if (years.isEmpty()) {
      awaitRefinement(request, session, ConversationDecision.RECENCY_YEAR_CLARIFICATION);
      return;
    }
    int upper = years.stream().mapToInt(Integer::intValue).sum() / years.size() - 1;
    if (upper < 1) {
      request.setDecision(ConversationDecision.RECENCY_EXHAUSTED);
      request.setSearchClarificationRequired(true);
      return;
    }
    var old =
        yearBounds(
            previous != null && previous.getMustInclude() != null
                ? previous.getMustInclude().getPublicationYear()
                : null);
    Integer lower = old.minimum();
    boolean conflict = lower != null && lower > upper;
    String bounded =
        conflict || lower == null ? "before " + (upper + 1) : "between " + lower + " and " + upper;
    setYear(request, bounded);
    if (conflict) {
      awaitRefinement(request, session, ConversationDecision.RECENCY_RANGE_CONFIRMATION);
    }
  }

  private void resolveNewerRefinement(
      ConversationDraft request,
      Session session,
      ConversationDraft previous,
      List<Integer> years,
      boolean relative) {
    String expression = request.getMustInclude().getPublicationYear();
    Integer lower =
        relative
            ? (years.isEmpty()
                ? null
                : years.stream().mapToInt(Integer::intValue).sum() / years.size())
            : yearBounds(expression).minimum();
    if (!relative && lower == null && yearBounds(expression).maximum() != null) {
      return;
    }
    if (lower == null) {
      awaitRefinement(request, session, ConversationDecision.RECENCY_YEAR_CLARIFICATION);
      return;
    }
    var previousBounds =
        yearBounds(
            previous == null || previous.getMustInclude() == null
                ? null
                : previous.getMustInclude().getPublicationYear());
    Integer upper = previousBounds.maximum();
    if (relative && previousBounds.minimum() != null && lower <= previousBounds.minimum()) {
      lower = previousBounds.minimum() + 1;
    }
    if (lower > 9999 || (upper != null && lower > upper)) {
      if (relative) {
        request.setDecision(ConversationDecision.RECENCY_EXHAUSTED);
      } else {
        request.setDecision(ConversationDecision.RECENCY_RANGE_CONFIRMATION);
        session.setPendingRefinementRequest(ConversationDraft.toTurn(request));
      }
      request.setSearchClarificationRequired(true);
      return;
    }
    if (!relative) {
      Integer specifiedUpper = yearBounds(expression).maximum();
      if (specifiedUpper != null) {
        upper = upper == null ? specifiedUpper : Math.min(upper, specifiedUpper);
      }
    }
    String bounded = upper == null ? "from " + lower : "between " + lower + " and " + upper;
    setYear(request, bounded);
  }

  private void awaitRefinement(
      ConversationDraft request, Session session, ConversationDecision question) {
    request.setDecision(question);
    request.setSearchClarificationRequired(true);
    session.setPendingRefinementRequest(ConversationDraft.toTurn(request));
  }

  private void setYear(ConversationDraft request, String value) {
    request.getMustInclude().setPublicationYear(value);
    if (request.getSearchCriteria() != null) {
      if (request.getSearchCriteria().getMustInclude() == null) {
        request.getSearchCriteria().setMustInclude(new ExactCriteria());
      }
      request.getSearchCriteria().getMustInclude().setPublicationYear(value);
    }
  }

  private void resolveDurationAnswer(ConversationDraft request, Session session) {
    if (session == null
        || request.getIntent() != ConversationAction.REFINE
        || request.getMustInclude() == null
        || request.getMustInclude().getDuration() == null
        || (session.getPendingDecision() != ConversationDecision.SHORTER_DURATION_CLARIFICATION
            && session.getPendingDecision() != ConversationDecision.LONGER_DURATION_CLARIFICATION)
        || session.getPendingRefinementRequest() == null) {
      return;
    }
    var pending = session.getPendingRefinementRequest().getMustInclude();
    var old = DurationParser.parse(pending == null ? null : pending.getDuration());
    var supplied = DurationParser.parse(request.getMustInclude().getDuration());
    Integer minimum = supplied.minimum() == null ? old.minimum() : supplied.minimum();
    Integer maximum = supplied.maximum() == null ? old.maximum() : supplied.maximum();
    if (minimum != null && maximum != null && minimum > maximum) {
      minimum = supplied.minimum();
      maximum = supplied.maximum();
    }
    if (!java.util.Objects.equals(minimum, supplied.minimum())
        || !java.util.Objects.equals(maximum, supplied.maximum())) {
      setDuration(
          request,
          minimum == null
              ? "at most " + maximum + " minutes"
              : maximum == null
                  ? "at least " + minimum + " minutes"
                  : "between " + minimum + " and " + maximum + " minutes");
    }
    if ((old.minimum() != null && (minimum == null || minimum < old.minimum()))
        || (old.maximum() != null && (maximum == null || maximum > old.maximum()))) {
      awaitRefinement(request, session, ConversationDecision.DURATION_RANGE_CONFIRMATION);
    }
  }

  private void setDuration(ConversationDraft request, String value) {
    var include = request.getMustInclude() == null ? new ExactCriteria() : request.getMustInclude();
    include.setDuration(value);
    request.setMustInclude(include);
    if (request.getSearchCriteria() != null) {
      if (request.getSearchCriteria().getMustInclude() == null) {
        request.getSearchCriteria().setMustInclude(new ExactCriteria());
      }
      request.getSearchCriteria().getMustInclude().setDuration(value);
    }
  }

  private void resolveDurationRefinement(ConversationDraft request, Session session) {
    if (request.getIntent() != ConversationAction.REFINE
        || (!request.isShorter() && !request.isLonger())) {
      return;
    }
    var previous = session == null ? null : ConversationHistory.latestSearch(session);
    var page = previous == null ? null : previous.getRecommendations();
    boolean longer = request.isLonger();
    if (page == null
        || page.isEmpty()
        || page.stream()
            .anyMatch(
                book -> book.getDurationMinutes() == null || book.getDurationMinutes() <= 1)) {
      awaitRefinement(
          request,
          session,
          longer
              ? ConversationDecision.LONGER_DURATION_CLARIFICATION
              : ConversationDecision.SHORTER_DURATION_CLARIFICATION);
      return;
    }
    int boundary =
        longer
            ? page.stream().mapToInt(book -> book.getDurationMinutes()).max().orElseThrow()
            : page.stream().mapToInt(book -> book.getDurationMinutes()).min().orElseThrow();
    var include = request.getMustInclude() == null ? new ExactCriteria() : request.getMustInclude();
    var old = DurationParser.parse(include.getDuration());
    if (longer && boundary == Integer.MAX_VALUE) {
      throw new InvalidRequestException(
          "Could you give me a smaller minimum duration in hours or minutes?");
    }
    int next = longer ? boundary + 1 : boundary - 1;
    boolean conflict =
        longer
            ? old.maximum() != null && next > old.maximum()
            : old.minimum() != null && next < old.minimum();
    String bounded =
        longer
            ? old.maximum() == null || conflict
                ? "at least " + next + " minutes"
                : "between " + next + " and " + old.maximum() + " minutes"
            : old.minimum() == null || conflict
                ? "under " + boundary + " minutes"
                : "between " + old.minimum() + " and " + next + " minutes";
    setDuration(request, bounded);
    request.setShorter(false);
    request.setLonger(false);
    if (conflict) {
      awaitRefinement(request, session, ConversationDecision.DURATION_RANGE_CONFIRMATION);
    }
  }

  public void validateTransition(
      ConversationDraft resolved, SessionRequest original, Session session) {
    if (resolved.getIntent() == ConversationAction.UPDATE_PREFERENCES) {
      if (resolved.getDecision() == ConversationDecision.PREFERENCE_UPDATE_CLARIFICATION) {
        return;
      }
      var proposed = PreferenceRequestMapper.toRequest(resolved.getPreferenceUpdates());
      var updates =
          original != null && original.getPreferenceUpdates() != null
              ? PreferenceRequestMapper.toRequest(original.getPreferenceUpdates())
              : original == null
                  ? new SessionRequest()
                  : PreferenceRequestMapper.toRequest(
                      PreferenceRequestMapper.fromRequest(original));
      resolveFilterCorrections(proposed, updates);
      resolved.getPreferenceUpdates().setMustInclude(proposed.getMustInclude());
      resolved.getPreferenceUpdates().setMustNotInclude(proposed.getMustNotInclude());
      return;
    }
    if (resolved.isSearchClarificationRequired()) {
      return;
    }
    resolveDurationAnswer(resolved, session);
    if (resolved.isSearchClarificationRequired()) {
      return;
    }
    resolveDurationRefinement(resolved, session);
    if (resolved.isSearchClarificationRequired()) {
      return;
    }
    resolveRecencyRefinement(resolved, session);
    if (resolved.isSearchClarificationRequired()) {
      return;
    }
    boolean awaitingTaste = session != null && session.isAwaitingPersonalisedPreferences();
    var previousSearch =
        original != null
                && !(awaitingTaste && original.isExplicitSearch())
                && (original.getIntent() == ConversationAction.REFINE
                    || original.getIntent() == ConversationAction.MORE_RESULTS
                    || (awaitingTaste && !original.isExplicitSearch()))
            ? ConversationHistory.latestSearch(session)
            : null;
    SessionRequest previous =
        previousSearch != null
                && (original.getIntent() == ConversationAction.REFINE || awaitingTaste)
                && previousSearch.getSearchCriteria() != null
            ? PreferenceRequestMapper.toRequest(previousSearch.getSearchCriteria())
            : previousSearch;
    resolveFilterCorrections(resolved, original, previous);
    if (resolved.getSearchCriteria() != null
        && (resolved.getIntent() == ConversationAction.RECOMMENDATION
            || resolved.getIntent() == ConversationAction.REFINE
            || resolved.getIntent() == ConversationAction.MORE_RESULTS)) {
      var criteria = PreferenceRequestMapper.toRequest(resolved.getSearchCriteria());
      resolveFilterCorrections(criteria, original, previous);
      resolved.setSearchCriteria(PreferenceRequestMapper.fromRequest(criteria));
    }
  }
}
