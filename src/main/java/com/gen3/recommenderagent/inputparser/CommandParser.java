package com.gen3.recommenderagent.inputparser;

import static com.gen3.recommenderagent.domain.conversation.ConversationText.normalizeCommand;
import static com.gen3.recommenderagent.domain.conversation.ConversationText.selectionReply;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.conversation.ConversationText;
import com.gen3.recommenderagent.domain.session.ExactCriteria;
import java.math.BigInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Recognises complete commands; unmatched text falls through to AI parsing. */
@Component
public class CommandParser {
  private static final String COUNT = "[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten";
  private static final Pattern CHANGE_COUNT =
      Pattern.compile(
          "(?:(?:change|set)(?: the| my)? (?:recommendation )?count to ("
              + COUNT
              + ")(?: books?| audiobooks?| recommendations?)?(?: at a time| per request| per page)?"
              + "|(?:give me |show me |i want |i'd like |i would like )?(?:only )?("
              + COUNT
              + ")(?: books?| audiobooks?| recommendations?)? (?:at a time|per request|per page))");
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
    if (java.util.Set.of(
            "give me some books",
            "give me some audiobooks",
            "recommend some books",
            "recommend some audiobooks")
        .contains(command)) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.RECOMMENDATION);
      parsed.setPersonalised(true);
      parsed.setExplicitSearch(true);
      return parsed;
    }
    if (ConversationText.isRecencyRefinement(command)) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.REFINE);
      parsed.setExplicitSearch(true);
      var criteria = new ExactCriteria();
      criteria.setPublicationYear("recent");
      parsed.setMustInclude(criteria);
      return parsed;
    }
    if (selectionReply(rawText.strip()) != null
        || com.gen3.recommenderagent.domain.conversation.ConversationText.isPreferenceDecline(
            rawText)) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.UNKNOWN);
      return parsed;
    }
    var changedCount = CHANGE_COUNT.matcher(command);
    if (changedCount.matches()) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.CHANGE_COUNT);
      parsed.setBookCount(
          countValue(
              changedCount.group(1) == null ? changedCount.group(2) : changedCount.group(1)));
      return parsed;
    }
    if (command.startsWith("explain ")) {
      Integer rank =
          selectionReply(command.substring(8).replaceFirst(" (?:more|in more detail)$", ""));
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
      parsed.setBookCount(count == null ? BigInteger.ONE : countValue(count));
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
          case "reset",
              "reset preferences",
              "reset my preferences",
              "reset session preferences",
              "clear preferences",
              "clear my preferences",
              "reset my tastes",
              "reset preferences and count" ->
              Intent.RESET_PREFERENCES;
          case "clear",
              "clear history",
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

  private static BigInteger countValue(String count) {
    return Character.isDigit(count.charAt(0))
        ? new BigInteger(count)
        : BigInteger.valueOf(selectionReply(count));
  }
}
