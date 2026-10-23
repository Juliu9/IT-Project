package com.gen3.recommenderagent.retrieval.plan;

import com.gen3.recommenderagent.domain.session.MustInclude;
import com.gen3.recommenderagent.domain.session.MustNotInclude;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
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

/** Converts parsed request fields into one immutable retrieval plan. */
@Component
public class AudiobookRetrievalPlanner {

  private static final Pattern YEAR_RANGE =
      Pattern.compile("^(?:between (\\d{4}) and (\\d{4})|(?:from )?(\\d{4}) *(?:-|to) *(\\d{4}))$");
  private static final Pattern YEAR_COMPARISON =
      Pattern.compile(
          "^(?:(after|before|from|since|newer than|older than|over|under|more than|less than|at least|at most|minimum(?: of)?|maximum(?: of)?|up to|exactly) )?(\\d{4})$");

  private static final Pattern RELATIVE_YEARS =
      Pattern.compile(
          "^(?:in |within |from )?(?:the )?(?:last|past) (?:([0-9]+|a|an|one|two|three|four|five|six|seven|eight|nine|ten) )?years?$");
  private final Clock clock;

  public AudiobookRetrievalPlanner() {
    this(Clock.systemUTC());
  }

  AudiobookRetrievalPlanner(Clock clock) {
    this.clock = clock;
  }

  /** Separates semantic text, lexical terms, and exact payload constraints. */
  public AudiobookRetrievalPlan plan(
      int limit, SessionRequest request, Set<String> excludedBookIds) {
    SemanticQuery positive = request == null ? null : request.getPositiveSemanticQuery();
    SemanticQuery negative = request == null ? null : request.getNegativeSemanticQuery();
    String keywordText = lexicalText(positive, "");
    AudiobookFilters filters =
        new AudiobookFilters(
            includeConditions(request == null ? null : request.getMustInclude()),
            excludeConditions(request == null ? null : request.getMustNotInclude()),
            excludedBookIds == null ? List.of() : List.copyOf(excludedBookIds));
    return new AudiobookRetrievalPlan(
        modeFor(positive, negative, filters), keywordText, filters, Math.max(limit, 1));
  }

  /** Derives retrieval solely from searchable request content. */
  RetrievalMode modeFor(SemanticQuery positive, SemanticQuery negative, AudiobookFilters filters) {
    boolean hasSemantic =
        hasPositiveSemanticContent(positive) || hasNegativeSemanticContent(negative);
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

  /** Adds nonblank parsed terms to the lexical query. */
  private void add(List<String> destination, List<String> values) {
    if (values != null) {
      values.stream().filter(this::hasText).map(String::trim).forEach(destination::add);
    }
  }

  private boolean hasPositiveSemanticContent(SemanticQuery query) {
    return query != null
        && (hasValues(query.getTopics())
            || hasValues(query.getGenres())
            || hasValues(query.getKeywords())
            || hasValues(query.getAuthors())
            || hasValues(query.getNarrators()));
  }

  private boolean hasNegativeSemanticContent(SemanticQuery query) {
    return hasPositiveSemanticContent(query)
        || (query != null && (hasValues(query.getAuthors()) || hasValues(query.getNarrators())));
  }

  private boolean hasValues(List<String> values) {
    return values != null && values.stream().anyMatch(this::hasText);
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
