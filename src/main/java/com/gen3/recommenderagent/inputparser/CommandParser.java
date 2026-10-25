package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.Intent;
import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Recognises complete commands; unmatched text falls through to AI parsing. */
@Component
public class CommandParser {
  private static final Pattern COUNTED_MORE =
      Pattern.compile(
          "(?:show me |give me |can i have |could i have )?([0-9]+) more(?: books| audiobooks| recommendations)?");

  public ParsedRequest parse(String rawText) {
    String command =
        rawText
            .strip()
            .toLowerCase(Locale.ROOT)
            .replace('’', '\'')
            .replace('‘', '\'')
            .replaceAll("\\b(?:please|kindly)\\b", " ")
            .replaceAll("(?U)\\s+", " ")
            .strip()
            .replaceFirst(
                "^(?:(?:hi|hello|hey|sorry|excuse me|just|can you|could you|would you)[\\s,!.?…–—:;]+)+",
                "")
            .replaceFirst(
                "(?:[\\s,!.?…–—:;]+(?:cheers|ta|many thanks|thanks|thank you(?: very much)?))+[\\s,!.?…–—:;]*$",
                "")
            .replaceFirst("[.!?,…]+$", "")
            .stripTrailing();
    if (com.gen3.recommenderagent.domain.conversation.ClarificationResolver.selectionReply(
            rawText.strip())
        != null) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.UNKNOWN);
      return parsed;
    }
    var countedMore = COUNTED_MORE.matcher(command);
    if (countedMore.matches()) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.MORE_RESULTS);
      parsed.setBookCount(new BigInteger(countedMore.group(1)));
      return parsed;
    }
    Intent intent =
        switch (command) {
          case "help",
              "show help",
              "what can you do",
              "what else can you do",
              "what do you do",
              "how do i use this",
              "how does this work",
              "what can i ask" ->
              Intent.HELP;
          case "more",
              "some more",
              "more recommendations",
              "more books",
              "more audiobooks",
              "show me more",
              "give me more",
              "can i have more",
              "can i have some more",
              "could i have more",
              "could i have some more",
              "next",
              "next batch",
              "next page" ->
              Intent.MORE_RESULTS;
          case "repeat",
              "repeat that",
              "say that again",
              "say it again",
              "repeat yourself",
              "repeat your last response",
              "repeat the last response",
              "repeat your last answer" ->
              Intent.REPEAT;
          case "repeat the books",
              "repeat the recommendations",
              "repeat recommendations",
              "repeat the audiobooks",
              "repeat those books",
              "repeat those recommendations",
              "show those recommendations again",
              "show me the recommendations again",
              "show those books again" ->
              Intent.REPEAT_RECOMMENDATIONS;
          case "explain",
              "explain the recommendations",
              "explain these recommendations",
              "why these books",
              "why these recommendations",
              "why those recommendations",
              "why did i get these recommendations",
              "why did i get these books",
              "why did you recommend these books",
              "why did you recommend these audiobooks",
              "why did you recommend these",
              "why were these recommended",
              "why were these audiobooks recommended",
              "explain all the recommendations",
              "explain these books",
              "why were these books recommended" ->
              Intent.EXPLAIN_RECOMMENDATION;
          case "clear history",
              "clear my history",
              "clear recommendation history",
              "clear my recommendation history" ->
              Intent.CLEAR_HISTORY;
          case "cancel",
              "cancel that",
              "cancel this",
              "cancel the request",
              "never mind",
              "nevermind",
              "stop",
              "forget it" ->
              Intent.CANCEL;
          default -> null;
        };
    if (intent == null) {
      return null;
    }
    var parsed = new ParsedRequest();
    parsed.setIntent(intent);
    return parsed;
  }
}
