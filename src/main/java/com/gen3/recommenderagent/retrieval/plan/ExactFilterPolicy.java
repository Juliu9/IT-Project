package com.gen3.recommenderagent.retrieval.plan;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
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
      Pattern.compile("^(?:between (\\d{4}) and (\\d{4})|(?:from )?(\\d{4}) *(?:-|to) *(\\d{4}))$");
  private static final Pattern YEAR_COMPARISON =
      Pattern.compile(
          "^(?:(after|before|from|since|newer than|older than|over|under|more than|less than|at least|at most|minimum(?: of)?|maximum(?: of)?|up to|exactly) )?(\\d{4})$");

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
        includeConditions(request == null ? null : request.getMustInclude()),
        excludeConditions(request == null ? null : request.getMustNotInclude()),
        excludedBookIds == null ? List.of() : List.copyOf(excludedBookIds));
  }

  /** Checks the new message before reconciling corrections against inherited filters. */
  public void resolveFilterCorrections(SessionRequest resolved, SessionRequest incoming) {
    resolveFilterCorrections(resolved, incoming, null);
  }

  public void resolveFilterCorrections(
      SessionRequest resolved, SessionRequest incoming, SessionRequest previousSearch) {
    if (resolved.isSearchClarificationRequired()
        || (resolved.getIntent() != Intent.RECOMMENDATION
            && resolved.getIntent() != Intent.REFINE
            && resolved.getIntent() != Intent.MORE_RESULTS
            && resolved.getIntent() != Intent.UPDATE_PREFERENCES)) {
      return;
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
        clearExcluded(resolved, field, priority.mustInclude());
      } else if (newExclude && !newInclude) {
        clearIncluded(resolved, field, priority.mustNotInclude());
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
              + " requirements conflict. Which requirement would you like to keep?");
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
        .map(value -> value.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
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
            .filter(
                value -> value != null && !removed.contains(value.trim().toLowerCase(Locale.ROOT)))
            .toList();
  }

  private void clearIncluded(
      SessionRequest request, String field, AudiobookFilterConditions correction) {
    var include = request.getMustInclude();
    switch (field) {
      case "authors" -> include.setAuthors(without(include.getAuthors(), correction, field));
      case "narrators" -> include.setNarrators(without(include.getNarrators(), correction, field));
      case "language" -> include.setLanguage(null);
      case "source" -> include.setSource(null);
      case "duration" -> include.setDuration(null);
      case "publication year" -> include.setPublicationYear(null);
      default -> throw new IllegalArgumentException(field);
    }
  }

  private void clearExcluded(
      SessionRequest request, String field, AudiobookFilterConditions correction) {
    var exclude = request.getMustNotInclude();
    switch (field) {
      case "authors" -> exclude.setAuthors(without(exclude.getAuthors(), correction, field));
      case "narrators" -> exclude.setNarrators(without(exclude.getNarrators(), correction, field));
      case "language" -> exclude.setLanguage(null);
      case "source" -> exclude.setSource(null);
      case "duration" -> exclude.setDuration(null);
      case "publication year" -> exclude.setPublicationYear(null);
      default -> throw new IllegalArgumentException(field);
    }
  }

  private AudiobookFilterConditions includeConditions(MustInclude filter) {
    if (filter == null) {
      return AudiobookFilterConditions.empty();
    }
    DurationRange bounds = DurationRange.parse(filter.getDuration());
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

  private AudiobookFilterConditions excludeConditions(MustNotInclude filter) {
    if (filter == null) {
      return AudiobookFilterConditions.empty();
    }
    DurationRange bounds = DurationRange.parse(filter.getDuration());
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
    Matcher relative = RELATIVE_YEARS.matcher(normalized);
    if (normalized.equals("recent") || normalized.equals("recently") || relative.matches()) {
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
    Matcher range = YEAR_RANGE.matcher(normalized);
    if (range.matches()) {
      int lower = Integer.parseInt(range.group(1) != null ? range.group(1) : range.group(3));
      int upper = Integer.parseInt(range.group(2) != null ? range.group(2) : range.group(4));
      if (lower <= upper) {
        return new RangeBounds(lower, upper);
      }
    } else {
      Matcher comparison = YEAR_COMPARISON.matcher(normalized);
      if (comparison.matches()) {
        int year = Integer.parseInt(comparison.group(2));
        String operator = comparison.group(1) == null ? "exactly" : comparison.group(1);
        return switch (operator) {
          case "after", "newer than", "over", "more than" -> new RangeBounds(year + 1, null);
          case "before", "older than", "under", "less than" -> new RangeBounds(null, year - 1);
          case "from", "since", "at least", "minimum", "minimum of" -> new RangeBounds(year, null);
          case "at most", "maximum", "maximum of", "up to" -> new RangeBounds(null, year);
          default -> new RangeBounds(year, year);
        };
      }
    }
    throw new InvalidRequestException(
        "Please specify a publication year, comparison, or valid year range.");
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
}
