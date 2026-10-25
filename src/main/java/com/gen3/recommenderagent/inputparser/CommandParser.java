package com.gen3.recommenderagent.inputparser;

import static com.gen3.recommenderagent.domain.conversation.ConversationText.normalizeCommand;
import static com.gen3.recommenderagent.domain.conversation.ConversationText.selectionReply;

import com.gen3.recommenderagent.domain.Intent;
import java.math.BigInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Recognises complete commands; unmatched text falls through to AI parsing. */
@Component
public class CommandParser {
  private static final String COUNT = "[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten";
  private static final Pattern COUNTED_MORE =
      Pattern.compile(
          "(?:show me |give me |can i have |could i have |i'd like |i would like |recommend )?"
              + "(?:("
              + COUNT
              + ") more|another(?: ("
              + COUNT
              + "))?)"
              + "(?: books?| audiobooks?| recommendations?)?");

  public ParsedRequest parse(String rawText) {
    String command = normalizeCommand(rawText);
    if (selectionReply(rawText.strip()) != null) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.UNKNOWN);
      return parsed;
    }
    if (command.startsWith("explain ")) {
      Integer rank = selectionReply(command.substring(8));
      if (rank != null) {
        var parsed = new ParsedRequest();
        parsed.setIntent(Intent.EXPLAIN_RECOMMENDATION);
        parsed.setReferenceRank(BigInteger.valueOf(rank));
        return parsed;
      }
    }
    if (java.util.Set.of(
            "make them shorter",
            "make it shorter",
            "make those audiobooks shorter",
            "make the previous books shorter",
            "shorter ones",
            "something shorter",
            "shorter audiobooks",
            "shorter books")
        .contains(command)) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.REFINE);
      parsed.setShorter(true);
      parsed.setExplicitSearch(true);
      return parsed;
    }
    var countedMore = COUNTED_MORE.matcher(command);
    if (countedMore.matches()) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.MORE_RESULTS);
      String count = countedMore.group(1) == null ? countedMore.group(2) : countedMore.group(1);
      parsed.setBookCount(
          count == null
              ? BigInteger.ONE
              : Character.isDigit(count.charAt(0))
                  ? new BigInteger(count)
                  : BigInteger.valueOf(selectionReply(count)));
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
              "show those books again",
              "show me the same books again",
              "show me the same audiobook list you gave me earlier" ->
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
          case "reset preferences",
              "reset my preferences",
              "reset session preferences",
              "clear preferences",
              "clear my preferences",
              "reset my tastes",
              "reset preferences and count" ->
              Intent.RESET_PREFERENCES;
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
