package com.gen3.recommenderagent.response;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.workflow.ConversationRules;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationDecision;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Answers fixed conversation decisions and delegates book narration to AI. */
@Component
public class ConversationResponseGenerator implements ResponseGenerator {
  private static final String PROCESSING_FALLBACK =
      "I'm having a little trouble with that right now. Please try again in a moment.";
  private static final Pattern EXPLICIT_OVERSIZED_COUNT =
      Pattern.compile(
          "\\b(?:[6-9]|[1-9]\\d+|six|seven|eight|nine|ten|eleven|twelve|dozen|"
              + "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\\b",
          Pattern.CASE_INSENSITIVE);

  private final AiResponseGenerator ai;

  public ConversationResponseGenerator(AiResponseGenerator ai) {
    this.ai = ai;
  }

  @Override
  public String generate(List<Recommendation> recommendations, ConversationTurn currentRequest) {
    String response = render(recommendations, currentRequest, Integer.MAX_VALUE);
    if (ResponseBudget.wordCount(response) <= ResponseBudget.MAX_WORDS) {
      return response;
    }
    var decision =
        currentRequest != null && currentRequest.getDecision() != null
            ? currentRequest.getDecision()
            : ConversationRules.decisionFor(
                recommendations,
                currentRequest == null ? null : ConversationDraft.fromTurn(currentRequest));
    if (decision == ConversationDecision.RECOMMENDATIONS
        || decision == ConversationDecision.EXPLANATION) {
      String notice =
          decision == ConversationDecision.EXPLANATION ? "" : limitNotice(currentRequest);
      response =
          ai.boundedFallback(
              recommendations,
              currentRequest,
              decision == ConversationDecision.EXPLANATION,
              notice,
              ResponseBudget.MAX_WORDS);
      RequestTelemetry.responseMode("FALLBACK");
    } else {
      response = render(recommendations, currentRequest, 8);
    }
    return ResponseBudget.limitWords(response, ResponseBudget.MAX_WORDS);
  }

  private String render(
      List<Recommendation> recommendations, ConversationTurn currentRequest, int labelWords) {
    // if both inputs are missing, the method returns a polite error message instead
    // of crashing
    if (recommendations == null && currentRequest == null) {
      return PROCESSING_FALLBACK;
    }

    var decision =
        currentRequest != null && currentRequest.getDecision() != null
            ? currentRequest.getDecision()
            : ConversationRules.decisionFor(
                recommendations,
                currentRequest == null ? null : ConversationDraft.fromTurn(currentRequest));
    if (decision == ConversationDecision.SEARCH_CLARIFICATION
        || decision == ConversationDecision.PREFERENCE_CLARIFICATION) {
      RequestTelemetry.responseMode(decision.name());
      return generateOutcomeResponse(decision, currentRequest, labelWords);
    }
    String limitNotice =
        decision == ConversationDecision.EXPLANATION ? "" : limitNotice(currentRequest);
    String fixedResponse = generateOutcomeResponse(decision, currentRequest, labelWords);
    if (fixedResponse != null) {
      if ((decision == ConversationDecision.HELP || decision == ConversationDecision.UNKNOWN)
          && currentRequest.getPendingQuestion() != null) {
        String question =
            generateOutcomeResponse(
                currentRequest.getPendingQuestion(), currentRequest, labelWords);
        if (question != null) {
          fixedResponse =
              decision == ConversationDecision.UNKNOWN
                  ? "I didn't quite catch that. Just to clarify, " + question
                  : fixedResponse + " " + question;
        }
      }
      boolean noResults =
          switch (decision) {
            case NO_MORE_RESULTS, NOTHING_TO_REPEAT, NO_RECOMMENDATIONS_TO_REPEAT, NO_MATCHES ->
                true;
            default -> false;
          };
      RequestTelemetry.responseMode(noResults ? "NO_RESULTS" : "ACTION_RESPONSE");
      return switch (decision) {
        case NO_MORE_RESULTS, NOTHING_TO_REPEAT, NO_RECOMMENDATIONS_TO_REPEAT -> fixedResponse;
        default -> limitNotice + fixedResponse;
      };
    }

    String response =
        ai.generate(
            recommendations,
            currentRequest,
            decision == ConversationDecision.EXPLANATION,
            limitNotice);
    return response;
  }

  private String limitNotice(ConversationTurn request) {
    if (request == null
        || request.getBookCount() == null
        || request.getBookCount() <= MAX_BOOK_COUNT
        || request.getRawText() == null
        || !EXPLICIT_OVERSIZED_COUNT.matcher(request.getRawText()).find()) {
      return "";
    }
    return "I can give up to " + MAX_BOOK_COUNT + " recommendations at a time. ";
  }

  private String generateOutcomeResponse(
      ConversationDecision decision, ConversationTurn currentRequest, int labelWords) {
    return switch (decision) {
      case SIMILAR_BOOK_SELECTION ->
          "I found a few possible matches. Which book did you mean? "
              + java.util.stream.IntStream.range(0, currentRequest.getSimilarBookChoices().size())
                  .mapToObj(
                      index -> {
                        var book = currentRequest.getSimilarBookChoices().get(index);
                        return (index + 1)
                            + ". "
                            + ResponseBudget.label(
                                book.title()
                                    + (book.authors().isEmpty()
                                        ? ""
                                        : " by " + String.join(", ", book.authors())),
                                labelWords);
                      })
                  .collect(Collectors.joining(". "))
              + ". You can choose a number, or share a more specific title and author.";
      case SIMILAR_BOOK_NOT_FOUND ->
          "I couldn't find that book in the catalogue. What did you enjoy about it?";
      case SIMILAR_BOOK_REQUIRED ->
          "Which book would you like similar recommendations for? Please give me one title.";
      case SIMILAR_BOOK_UNAVAILABLE ->
          "I can't use that book to find similar audiobooks right now. Please give me another title.";
      case LONGER_DURATION_CLARIFICATION ->
          "How long would you like the audiobooks to be? For example, at least 3 hours.";
      case DURATION_RANGE_CONFIRMATION ->
          "That duration goes beyond your previous range. Would you like to extend the range?";
      case EMPTY_EXPLANATION_SELECTION ->
          "There aren't any books left to explain. Which books would you like me to explain?";
      case SHORTER_DURATION_CLARIFICATION ->
          "How short would you like the audiobooks to be? For example, under 3 hours.";
      case RECENCY_RANGE_CONFIRMATION ->
          "Those years extend beyond your previous period. Would you like to use the new year range instead?";
      case RECENCY_EXHAUSTED ->
          olderRequest(currentRequest)
              ? "There are no older books within the available publication years."
              : "There are no newer books within your selected period.";
      case RECENCY_YEAR_CLARIFICATION ->
          olderRequest(currentRequest)
              ? "I don't have publication years for those books. What ending year would you like?"
              : "I don't have publication years for those books. What year would you like me to start from? For example, from 2000.";
      case COUNT_CLARIFICATION ->
          "How many books would you like? Please choose a whole number from one to five.";
      case COUNT_CHANGED -> {
        int count = Math.clamp(currentRequest.getBookCount(), 1, MAX_BOOK_COUNT);
        yield "I'll show " + count + " recommendation" + (count == 1 ? "" : "s") + " at a time.";
      }
      case PREFERENCE_UPDATE_CLARIFICATION ->
          "What would you like to change about your preferences?";
      case PREFERENCES_UPDATED -> "Thanks, I've updated your preferences for this session.";
      case PREFERENCES_RESET ->
          "All set, I've reset your preferences for this session and will show "
              + MAX_BOOK_COUNT
              + " recommendations at a time.";
      case HISTORY_CLEARED -> "All set, I've cleared this session's recommendation history.";
      case SEARCH_DECLINED -> "No problem. Let me know when you’d like to try another search.";
      case CANCELLED -> "No problem, I've cancelled that. What would you like to do next?";
      case HELP ->
          "I can help you find audiobooks, adjust your search, or explore more books. You can ask me to "
              + "repeat my last reply or the last book list, explain a recommendation, or change how many books I show. "
              + "You can also update or reset your preferences, cancel a request, or clear this session's history.";
      case UNKNOWN ->
          "I'm not quite sure what you'd like to do. You can ask for audiobook recommendations or "
              + "say help to hear what I can do.";
      case NO_RECOMMENDATIONS_TO_REPEAT ->
          "I don't have a list of recommendations to repeat right now. What kind of audiobook would you like?";
      case NOTHING_TO_EXPLAIN ->
          "I haven't recommended any books in this session yet. What kind of audiobook would you like?";
      case EMPTY_EXPLANATION_PAGE ->
          "I didn't find any books in the latest search to explain. Would you like to try a different search?";
      case EXPLANATION_TARGET_CLARIFICATION -> explanationClarification(currentRequest, labelWords);
      case NO_MORE_RESULTS ->
          "There are no more audiobooks matching your current search. Would you like to try a different author, genre, or filter?";
      case NOTHING_TO_REPEAT ->
          "I don't have an earlier request to repeat yet. Would you like recommendations or help getting started?";
      case NO_MATCHES -> generateFallbackResponse(currentRequest, labelWords);
      case UNAVAILABLE -> PROCESSING_FALLBACK;
      case SEARCH_CLARIFICATION -> "What kind of audiobook would you like?";
      case SEARCH_CHANGE_OFFER -> "Would you like to try a different author, genre, or filter?";
      case SEARCH_CHANGE_CLARIFICATION -> "What would you like to change about your search?";
      case PREFERENCE_CLARIFICATION -> "What genres do you like?";
      case RECOMMENDATIONS, EXPLANATION -> null;
    };
  }

  private boolean olderRequest(ConversationTurn request) {
    return request.getMustInclude() != null
        && com.gen3.recommenderagent.inputparser.ConversationText.isOlderRefinement(
            request.getMustInclude().getPublicationYear());
  }

  private String explanationClarification(ConversationTurn request, int labelWords) {
    List<Recommendation> page =
        request.getRecommendations() == null ? List.of() : request.getRecommendations();
    String titles =
        page.stream()
            .map(book -> book.getRank() + ". " + ResponseBudget.label(book.getTitle(), labelWords))
            .collect(Collectors.joining(". "));
    var selection = request.getExplanationSelection();
    String unresolved =
        selection == null
            ? null
            : java.util.stream.Stream.concat(
                    selection.include().stream(), selection.exclude().stream())
                .filter(
                    value ->
                        page.stream().noneMatch(book -> book.getRank().toString().equals(value)))
                .findFirst()
                .orElse(null);
    return (unresolved == null
            ? ""
            : "I couldn't uniquely identify " + ResponseBudget.label(unresolved, labelWords) + ". ")
        + "I shared "
        + page.size()
        + " book"
        + (page.size() == 1 ? "" : "s")
        + " with you: "
        + titles
        + ". Which one would you like me to explain?";
  }

  // Safe node output
  private String generateFallbackResponse(ConversationTurn currentRequest, int labelWords) {
    if (currentRequest != null && currentRequest.isPersonalised()) {
      return "I couldn't find audiobooks matching your current search and saved preferences. "
          + "Would you like to try a different author, genre, or filter?";
    }
    if (currentRequest != null && currentRequest.getIntent() == ConversationAction.REPEAT) {
      return "I couldn't find any audiobooks matching those search criteria. Would you like to try a different genre, author, narrator, or description?";
    }
    if (currentRequest != null
        && (currentRequest.getIntent() == ConversationAction.REFINE
            || (currentRequest.getMustInclude() != null
                && currentRequest.getMustInclude().getPublicationYear() != null))) {
      return "I couldn't find books matching those criteria. Would you like to try a different author, genre, or filter?";
    }
    if (currentRequest != null
        && currentRequest.getRawText() != null
        && !currentRequest.getRawText().isBlank()) {
      return "I couldn't find a good match for "
          + ResponseBudget.label(currentRequest.getRawText(), labelWords)
          + " just yet. Would you like to try a different genre, author, narrator, or description?";
    }

    return PROCESSING_FALLBACK;
  }
}
