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
        .replaceFirst("[.!?,…]+$", "")
        .stripTrailing();
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
