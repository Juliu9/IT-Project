package com.gen3.recommenderagent.domain.conversation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared courtesy normalization for complete commands and short selection replies. */
public final class ConversationText {
  private static final Pattern SELECTION_REPLY =
      Pattern.compile(
          "(?i)^(?:(?:choose|pick|select)\\s+)?(?:the\\s+)?(?:(?:book|audiobook)\\s+)?(?:number\\s+)?([0-9]+(?:st|nd|rd|th)?|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?:\\s+(?:one|book|audiobook|recommendation))?(?:\\s+please)?[.!?]?$");

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
        .replaceFirst("^[,!.?…–—:;]+\\s*", "")
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
          "the final one" ->
          size;
      case "latter", "the latter", "the latter one" -> size == 2 ? 2 : null;
      case "former", "the former", "the former one" -> size == 2 ? 1 : null;
      default -> null;
    };
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
                "each one")
            .contains(normalizeCommand(text));
  }

  public static boolean isAffirmativeReply(String text) {
    return text != null
        && java.util.Set.of("yes", "yeah", "yep", "sure", "go ahead", "ok", "okay")
            .contains(normalizeCommand(text));
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
