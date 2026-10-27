package com.gen3.recommenderagent.domain.conversation;

import com.gen3.recommenderagent.domain.session.DurationRange;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared courtesy normalization for complete commands and short selection replies. */
public final class ConversationText {
  private static final Pattern SELECTION_REPLY =
      Pattern.compile(
          "(?i)^(?:(?:choose|pick|select)\\s+)?(?:the\\s+)?(?:(?:book|audiobook)\\s+)?(?:number\\s+)?([0-9]+(?:st|nd|rd|th)?|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?:\\s+(?:one|book|audiobook|recommendation))?(?:\\s+please)?[.!?]?$");

  private static final Pattern REPLACEMENT_REFERENCE =
      Pattern.compile(
          "(?i)^(?:none of (?:these|those|them)|neither(?: of them)?)[,;.!]?\\s+(?:try|use)\\s+(.+)$");

  private ConversationText() {}

  public static String normalizeCommand(String rawText) {
    return rawText
        .strip()
        .toLowerCase(Locale.ROOT)
        .replace('’', '\'')
        .replace('‘', '\'')
        .replaceAll("\\b(?:please|kindly)\\b", " ")
        .replaceAll("(?U)\\s+", " ")
        .strip()
        .replaceFirst("^(?:[,!?…–—:;]|\\.(?![0-9]))+\\s*", "")
        .replaceFirst(
            "^(?:(?:hi|hello|hey|sorry|excuse me|just|can you|could you|would you)[\\s,!.?…–—:;]+)+",
            "")
        .replaceFirst(
            "(?:[\\s,!.?…–—:;]+(?:cheers|ta|many thanks|thanks|thank you(?: very much)?))+[\\s,!.?…–—:;]*$",
            "")
        .replaceFirst("[\\s.!?,…]+$", "")
        .stripTrailing();
  }

  public static Integer relativeSelectionReply(String text, int size) {
    if (text == null || size < 1) {
      return null;
    }
    return switch (normalizeCommand(text)) {
      case "last",
          "the last",
          "last one",
          "the last one",
          "last book",
          "the last book",
          "the final one",
          "final",
          "the final",
          "final one",
          "final book",
          "the final book",
          "last audiobook",
          "the last audiobook" ->
          size;
      case "latter", "the latter", "the latter one", "latter book", "the latter book" -> size;
      case "former", "the former", "the former one", "former book", "the former book" -> 1;
      default -> null;
    };
  }

  public static boolean isCancellationReply(String text) {
    return text != null
        && java.util.Set.of(
                "cancel",
                "cancel that",
                "cancel this",
                "cancel the request",
                "never mind",
                "nevermind",
                "stop",
                "forget it",
                "not now",
                "leave it")
            .contains(normalizeCommand(text));
  }

  public static String replacementReferenceTitle(String text) {
    if (text == null) {
      return null;
    }
    var match = REPLACEMENT_REFERENCE.matcher(text.strip());
    return match.matches() ? match.group(1).strip() : null;
  }

  public static boolean isChoiceRejection(String text) {
    return text != null
        && java.util.Set.of(
                "none",
                "none of them",
                "none of these",
                "none of those",
                "neither",
                "neither of them")
            .contains(normalizeCommand(text));
  }

  public static boolean isWholePageReply(String text) {
    return text != null
        && java.util.Set.of(
                "all",
                "all of them",
                "all of these",
                "all of those",
                "all the books",
                "all the recommendations",
                "every book",
                "every displayed book",
                "all displayed books",
                "all the displayed books",
                "every recommendation",
                "each one")
            .contains(normalizeCommand(text));
  }

  public static boolean isAffirmativeReply(String text) {
    return text != null
        && java.util.Set.of("yes", "yeah", "yep", "sure", "go ahead", "ok", "okay")
            .contains(normalizeCommand(text));
  }

  public static String durationReply(String text, boolean longer) {
    String reply = DurationRange.normalize(normalizeCommand(text));
    if (reply.isBlank()) {
      return null;
    }
    if (reply.matches(".*[0-9]$")) {
      reply += " hours";
    }
    try {
      var range = DurationRange.parse(reply);
      if (range.minimum() != null
          && range.minimum().equals(range.maximum())
          && !reply.startsWith("exactly ")
          && !reply.startsWith("between ")
          && !reply.startsWith("from ")
          && !reply.contains("-")
          && !reply.contains("to")) {
        reply = (longer ? "at least " : "at most ") + reply;
      }
      return reply;
    } catch (InvalidRequestException exception) {
      return null;
    }
  }

  public static boolean isRecencyRefinement(String text) {
    return text != null
        && java.util.Set.of(
                "more recent",
                "more recent books",
                "more recent audiobooks",
                "i want books from more recent times")
            .contains(normalizeCommand(text));
  }

  public static boolean isOlderRefinement(String text) {
    return text != null
        && java.util.Set.of("older", "older ones", "older books", "older audiobooks", "less recent")
            .contains(normalizeCommand(text));
  }

  public static boolean isYearRefinement(String text) {
    return isRecencyRefinement(text) || isOlderRefinement(text);
  }

  public static boolean isPreferenceDecline(String reply) {
    return java.util.Set.of(
            "nothing",
            "no changes",
            "nothing to change",
            "leave them as they are",
            "no",
            "not now",
            "keep my preferences",
            "keep my current preferences")
        .contains(normalizeCommand(reply));
  }

  public static Integer selectionReply(String reply) {
    var match = SELECTION_REPLY.matcher(ConversationText.normalizeCommand(reply));
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
}
