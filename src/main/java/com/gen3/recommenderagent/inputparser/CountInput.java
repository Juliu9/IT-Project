package com.gen3.recommenderagent.inputparser;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Checks explicit count wording before a model can round it or reinterpret zero as replay. */
final class CountInput {
  private static final String WORD = "zero|one|two|three|four|five|six|seven|eight|nine|ten";
  private static final String NUMBER =
      "(?:minus\\s+(?:"
          + WORD
          + "|[0-9]+)|(?:"
          + WORD
          + ")\\s+and\\s+a\\s+half|(?:"
          + WORD
          + ")|[-−]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))";
  private static final Pattern COUNT =
      Pattern.compile(
          "(?i)(?:\\bcount\\s+to\\s+("
              + NUMBER
              + ")|(?<![\\p{L}\\p{N}])("
              + NUMBER
              + ")\\s+(?:(?:more\\s+)?(?:(?!(?:hours?|minutes?|years?|hrs?|mins?)\\b)[\\p{L}-]+\\s+){0,3}(?:books?|audiobooks?|recommendations?)\\b|more\\b|(?:at a time|per page|per request)\\b))");

  record Check(boolean invalid, String modelText) {}

  static Check check(String text) {
    if (text.matches("(?is).*\\b(?:explain|why|reason)\\b.*")) {
      return new Check(false, text);
    }
    if (text.strip().matches("[-−]?(?:[0-9]+\\.[0-9]+|\\.[0-9]+)")) {
      var value = new BigDecimal(text.strip().replace('−', '-'));
      if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
        return new Check(true, "1");
      }
    }
    var match = COUNT.matcher(text);
    while (match.find()) {
      int group = match.group(1) == null ? 2 : 1;
      String number = match.group(group).toLowerCase(java.util.Locale.ROOT).replace('−', '-');
      // Descriptive phrases such as "zero gravity books" need semantic parsing, not count
      // rewriting.
      if (group == 2
          && number.equals("zero")
          && !text.substring(match.end(group), match.end())
              .strip()
              .matches(
                  "(?i)(?:(?:more )?(?:books?|audiobooks?|recommendations?)|more|at a time|per page|per request)")) {
        continue;
      }
      boolean invalid =
          number.equals("zero") || number.startsWith("minus") || number.contains("half");
      if (number.matches("[-0-9.]+")) {
        var value = new BigDecimal(number);
        invalid = value.signum() <= 0 || value.stripTrailingZeros().scale() > 0;
      }
      if (invalid) {
        // A synthetic numeric count can distract the model from the remaining search criteria.
        String replacement = setting(text) || more(text) ? "1" : "some";
        return new Check(
            true,
            text.substring(0, match.start(group)) + replacement + text.substring(match.end(group)));
      }
    }
    return new Check(false, text);
  }

  static boolean describesZero(String text, ParsedRequest parsed) {
    String normalized = text.toLowerCase(java.util.Locale.ROOT).replaceAll("(?U)\\s+", " ");
    return java.util.stream.Stream.of(
            parsed.getPositiveSemanticQuery(), parsed.getNegativeSemanticQuery())
        .filter(java.util.Objects::nonNull)
        .flatMap(
            query ->
                java.util.stream.Stream.of(
                    query.getTopics(), query.getGenres(), query.getKeywords()))
        .filter(java.util.Objects::nonNull)
        .flatMap(java.util.Collection::stream)
        .filter(java.util.Objects::nonNull)
        .map(value -> value.toLowerCase(java.util.Locale.ROOT).replaceAll("(?U)\\s+", " ").strip())
        .anyMatch(value -> value.matches("zero\\s+.+") && normalized.contains(value));
  }

  static boolean more(String text) {
    return text.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(?:more|another)\\b.*");
  }

  static boolean setting(String text) {
    return text.toLowerCase(java.util.Locale.ROOT)
        .matches("(?s).*\\b(?:count|at a time|per page|per request)\\b.*");
  }
}
