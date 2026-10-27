package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Translates explicit duration expressions to inclusive bounds for whole-minute catalogue data. */
public record DurationRange(Integer minimum, Integer maximum) {
  private static final String NUMBER = "(?:\\d+(?:\\.\\d+)?|\\.\\d+)";
  private static final String UNIT = "(?:hours?|hrs?|h|minutes?|mins?|m)";
  private static final Pattern TERM =
      Pattern.compile("(" + NUMBER + ")\\s*(" + UNIT + ")(?![a-z])");
  private static final Pattern QUANTITY = Pattern.compile("(" + NUMBER + ")\\s*(" + UNIT + ")");
  private static final Pattern RANGE =
      Pattern.compile(
          "^(?:between\\s+(.+?)\\s+and\\s+(.+)|(?:from\\s+)?(.+?)\\s*(?:-|to)\\s*(.+))$");
  private static final Pattern COMPARISON =
      Pattern.compile(
          "^(under|less than|below|over|more than|above|at most|no more than|up to|maximum(?: of)?|at least|no less than|minimum(?: of)?|exactly)\\s+(.+)$");

  public static String normalize(String text) {
    String value = text.strip().toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ");
    var words =
        java.util.List.of(
            "zero",
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
            "twenty");
    var tens =
        java.util.List.of(
            "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety");
    for (int index = 0; index < tens.size(); index++) {
      int base = (index + 2) * 10;
      value =
          Pattern.compile(
                  "\\b"
                      + tens.get(index)
                      + "(?:[ -](one|two|three|four|five|six|seven|eight|nine))?\\b")
              .matcher(value)
              .replaceAll(
                  match ->
                      Integer.toString(
                          base + (match.group(1) == null ? 0 : words.indexOf(match.group(1)))));
    }
    for (int number = 0; number < words.size(); number++) {
      value = value.replaceAll("\\b" + words.get(number) + "\\b", Integer.toString(number));
    }
    value = value.replaceAll("\\b(?:a|an) (hundred|thousand)\\b", "1 $1");
    value =
        Pattern.compile("([0-9]+) hundred(?: (?:and )?([0-9]+))?\\b")
            .matcher(value)
            .replaceAll(
                match ->
                    new BigDecimal(match.group(1))
                        .multiply(BigDecimal.valueOf(100))
                        .add(
                            match.group(2) == null
                                ? BigDecimal.ZERO
                                : new BigDecimal(match.group(2)))
                        .toPlainString());
    value =
        Pattern.compile("([0-9]+) thousand(?: (?:and )?([0-9]+))?\\b")
            .matcher(value)
            .replaceAll(
                match ->
                    new BigDecimal(match.group(1))
                        .multiply(BigDecimal.valueOf(1000))
                        .add(
                            match.group(2) == null
                                ? BigDecimal.ZERO
                                : new BigDecimal(match.group(2)))
                        .toPlainString());
    value = value.replaceAll("\\b(?:a )?couple of (hours?|minutes?)\\b", "2 $1");
    value = value.replaceAll("\\b([0-9]+) (hours?|hrs?|h) ([0-9]+)$", "$1 $2 $3 minutes");
    value =
        Pattern.compile("([0-9]+) and (?:a )?(half|quarter)\\b")
            .matcher(value)
            .replaceAll(
                match ->
                    new BigDecimal(match.group(1))
                        .add(
                            match.group(2).equals("half")
                                ? new BigDecimal("0.5")
                                : new BigDecimal("0.25"))
                        .toPlainString());
    value =
        Pattern.compile("([0-9]+) quarters? of (?:an? )?(hour|minute)")
            .matcher(value)
            .replaceAll(
                match ->
                    new BigDecimal(match.group(1)).divide(BigDecimal.valueOf(4)).toPlainString()
                        + " "
                        + match.group(2));
    value = value.replaceAll("\\b(?:a )?quarter of (?:an? )?(hour|minute)", "0.25 $1");
    value =
        value
            .replaceAll("\\bhalf (?:an? )?(hour|minute)", "0.5 $1")
            .replaceAll("\\b(?:a|an) (hour|minute)", "1 $1");
    value =
        Pattern.compile("(" + NUMBER + ") (" + UNIT + ") and (?:a )?(half|quarter)\\b")
            .matcher(value)
            .replaceAll(
                match ->
                    new BigDecimal(match.group(1))
                            .add(
                                match.group(3).equals("half")
                                    ? new BigDecimal("0.5")
                                    : new BigDecimal("0.25"))
                            .toPlainString()
                        + " "
                        + match.group(2));
    return value;
  }

  public static DurationRange parse(String text) {
    if (text == null || text.isBlank()) {
      return new DurationRange(null, null);
    }
    String normalized = normalize(text);
    try {
      Matcher range = RANGE.matcher(normalized);
      if (range.matches()) {
        String lower = range.group(1) != null ? range.group(1) : range.group(3);
        String upper = range.group(2) != null ? range.group(2) : range.group(4);
        Matcher upperQuantity = QUANTITY.matcher(upper);
        if (lower.matches(NUMBER) && upperQuantity.matches()) {
          lower += " " + upperQuantity.group(2);
        }
        return bounded(minutes(lower), minutes(upper));
      }
      Matcher comparison = COMPARISON.matcher(normalized);
      String operator = comparison.matches() ? comparison.group(1) : "exactly";
      BigDecimal value = minutes(comparison.matches() ? comparison.group(2) : normalized);
      return switch (operator) {
        case "under", "less than", "below" -> checked(null, whole(value, RoundingMode.CEILING) - 1);
        case "over", "more than", "above" ->
            checked(Math.addExact(whole(value, RoundingMode.FLOOR), 1), null);
        case "at most", "no more than", "up to", "maximum", "maximum of" ->
            checked(null, whole(value, RoundingMode.FLOOR));
        case "at least", "no less than", "minimum", "minimum of" ->
            checked(whole(value, RoundingMode.CEILING), null);
        default -> bounded(value, value);
      };
    } catch (ArithmeticException | IllegalArgumentException exception) {
      throw invalidDuration();
    }
  }

  private static BigDecimal minutes(String text) {
    Matcher terms = TERM.matcher(text);
    BigDecimal total = BigDecimal.ZERO;
    int end = 0;
    boolean hoursSeen = false;
    boolean minutesSeen = false;
    while (terms.find()) {
      String separator = text.substring(end, terms.start()).trim();
      if (!separator.isEmpty() && !(end > 0 && separator.equals("and"))) {
        throw invalidDuration();
      }
      boolean hours = terms.group(2).startsWith("h");
      if ((hours && hoursSeen) || (!hours && minutesSeen)) {
        throw invalidDuration();
      }
      hoursSeen |= hours;
      minutesSeen |= !hours;
      BigDecimal quantity = new BigDecimal(terms.group(1));
      total = total.add(hours ? quantity.multiply(BigDecimal.valueOf(60)) : quantity);
      end = terms.end();
    }
    if (end == 0
        || !text.substring(end).isBlank()
        || total.signum() <= 0
        || total.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
      throw invalidDuration();
    }
    return total;
  }

  private static DurationRange bounded(BigDecimal minimum, BigDecimal maximum) {
    return checked(whole(minimum, RoundingMode.CEILING), whole(maximum, RoundingMode.FLOOR));
  }

  private static int whole(BigDecimal value, RoundingMode rounding) {
    return value.setScale(0, rounding).intValueExact();
  }

  private static DurationRange checked(Integer minimum, Integer maximum) {
    if ((minimum != null && minimum < 1)
        || (maximum != null && maximum < 1)
        || (minimum != null && maximum != null && minimum > maximum)) {
      throw invalidDuration();
    }
    return new DurationRange(minimum, maximum);
  }

  private static InvalidRequestException invalidDuration() {
    return new InvalidRequestException(
        "Could you give me a duration in hours or minutes? For example, 'under 1.5 hours' or 'between 2 and 3 hours'.");
  }
}
