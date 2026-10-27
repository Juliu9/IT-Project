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

  private static final Pattern TEMPORARY_MORE_COUNT =
      Pattern.compile(
          "^(.+?)(?:, (?:and )?| and )(?:make (?:it|that) )?("
              + COUNT
              + ")(?: books?| audiobooks?| recommendations?)? (?:at a time|per page|per request)$");

  private static final Pattern APPROXIMATE_YEAR_REQUEST =
      Pattern.compile(
          "(?:(?:recommend|show me|give me|i want|i'd like|i would like) )?"
              + "(?:some )?(?:books|audiobooks) from ((?:around|(?:the )?period) [0-9]{4})");

  public ParsedRequest parse(String rawText) {
    String command = normalizeCommand(rawText);
    if (ConversationText.isCancellationReply(command)) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.CANCEL);
      return parsed;
    }
    var temporaryCount = TEMPORARY_MORE_COUNT.matcher(command);
    if (temporaryCount.matches()) {
      var action = parse(temporaryCount.group(1));
      if (action != null && action.getIntent() == Intent.MORE_RESULTS) {
        action.setBookCount(countValue(temporaryCount.group(2)));
        return action;
      }
    }
    var removal =
        Pattern.compile(
                "^(?:remove (.+) from (?:my |the )?(?:session )?preferences|i no longer like (.+))$")
            .matcher(command);
    if (removal.matches()) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.UPDATE_PREFERENCES);
      String term =
          (removal.group(1) == null ? removal.group(2) : removal.group(1))
              .replaceFirst("^(?:the )?(?:author|narrator|genre|topic|keyword) ", "");
      if (term.contains(" and ")) {
        return null;
      }
      parsed.setPreferenceRemovalTerms(java.util.List.of(term));
      parsed.setPositivePreferenceRemovalOnly(removal.group(2) != null);
      return parsed;
    }
    var negative =
        Pattern.compile("^(?:the same but )?less (violent|dark|scary|romantic)$").matcher(command);
    var without =
        Pattern.compile(
                "^(?:only )?(?:books|audiobooks) without (romance|horror|fantasy|mystery|crime|comedy|science fiction|thrillers?)$")
            .matcher(command);
    if (negative.matches() || without.matches()) {
      var parsed = new ParsedRequest();
      parsed.setIntent(negative.matches() ? Intent.REFINE : Intent.RECOMMENDATION);
      var query = new com.gen3.recommenderagent.domain.session.SemanticQuery();
      if (negative.matches()) {
        query.setKeywords(java.util.List.of(negative.group(1)));
      } else {
        query.setGenres(java.util.List.of(without.group(1)));
      }
      parsed.setNegativeSemanticQuery(query);
      parsed.setExplicitSearch(true);
      return parsed;
    }
    var approximateYear = APPROXIMATE_YEAR_REQUEST.matcher(command);
    if (approximateYear.matches()) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.RECOMMENDATION);
      parsed.setExplicitSearch(true);
      var criteria = new ExactCriteria();
      criteria.setPublicationYear(approximateYear.group(1));
      parsed.setMustInclude(criteria);
      return parsed;
    }
    if (ConversationText.isOlderRefinement(command)) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.REFINE);
      parsed.setExplicitSearch(true);
      var criteria = new ExactCriteria();
      criteria.setPublicationYear("older");
      parsed.setMustInclude(criteria);
      return parsed;
    }
    if (java.util.Set.of(
            "make them longer",
            "make it longer",
            "longer ones",
            "longer books",
            "longer audiobooks")
        .contains(command)) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.REFINE);
      parsed.setLonger(true);
      parsed.setExplicitSearch(true);
      return parsed;
    }
    var explanation = explanation(command);
    if (explanation != null) {
      return explanation;
    }
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
      criteria.setPublicationYear("more recent");
      parsed.setMustInclude(criteria);
      return parsed;
    }
    String cleared =
        switch (command) {
          case "any author",
              "any author is fine",
              "any author is fine now",
              "remove the author filter" ->
              "mustInclude.authors";
          case "any narrator", "any narrator is fine", "remove the narrator filter" ->
              "mustInclude.narrators";
          case "remove the time limit", "remove the duration limit", "any duration" ->
              "mustInclude.duration";
          case "any publication year", "remove the year filter" -> "mustInclude.publicationYear";
          default -> null;
        };
    if (cleared != null) {
      var parsed = new ParsedRequest();
      parsed.setIntent(Intent.REFINE);
      parsed.setReplaceSearchFields(java.util.List.of(cleared));
      return parsed;
    }
    if (ConversationText.replacementReferenceTitle(rawText) != null
        || ConversationText.relativeSelectionReply(rawText, 2) != null
        || ConversationText.isChoiceRejection(rawText)
        || ConversationText.isWholePageReply(rawText)
        || ConversationText.isAffirmativeReply(rawText)
        || selectionReply(rawText.strip()) != null
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
              "which operations do you support",
              "what operations do you support",
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
          default -> null;
        };
    if (intent == null) {
      return null;
    }
    var parsed = new ParsedRequest();
    parsed.setIntent(intent);
    return parsed;
  }

  private ParsedRequest explanation(String command) {
    String target;
    if (command.startsWith("explain ")) {
      target = command.substring(8);
    } else if (command.startsWith("why ")) {
      target = command.substring(4);
    } else {
      return null;
    }
    String[] sides = target.split(" (?:except|except for|but|but not) ", 2);
    var included = selectors(sides[0]);
    var excluded =
        sides.length == 2
            ? ConversationText.isWholePageReply(sides[1])
                ? java.util.List.of("*")
                : selectors(sides[1])
            : java.util.List.<String>of();
    if (included == null || excluded == null) {
      return null;
    }
    var parsed = new ParsedRequest();
    parsed.setIntent(Intent.EXPLAIN_RECOMMENDATION);
    if (sides.length == 1 && included.size() == 1) {
      Integer rank = ConversationText.selectionReply(included.getFirst());
      if (rank != null) {
        parsed.setReferenceRank(BigInteger.valueOf(rank));
      } else {
        parsed.setExplanationSelection(
            new com.gen3.recommenderagent.domain.session.ExplanationSelection(included, excluded));
      }
    } else {
      parsed.setExplanationSelection(
          new com.gen3.recommenderagent.domain.session.ExplanationSelection(included, excluded));
    }
    return parsed;
  }

  private java.util.List<String> selectors(String text) {
    text = text.replaceFirst(" (?:more|in more detail)$", "");
    if (ConversationText.isWholePageReply(text)
        || java.util.Set.of("these", "these books", "these recommendations", "the recommendations")
            .contains(text)) {
      return java.util.List.of();
    }
    var values = text.replaceFirst("^books? ", "").split("\\s*(?:,| and )\\s*");
    for (String value : values) {
      if (ConversationText.selectionReply(value) == null
          && ConversationText.relativeSelectionReply(value, 2) == null) {
        return null;
      }
    }
    return java.util.List.of(values);
  }

  private static BigInteger countValue(String count) {
    return Character.isDigit(count.charAt(0))
        ? new BigInteger(count)
        : BigInteger.valueOf(selectionReply(count));
  }
}
