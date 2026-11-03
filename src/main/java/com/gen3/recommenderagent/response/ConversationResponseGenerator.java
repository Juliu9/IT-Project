package com.gen3.recommenderagent.response;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Answers fixed conversation decisions and delegates book narration to AI. */
@Component
public class ConversationResponseGenerator implements ResponseGenerator {
  private static final String PROCESSING_FALLBACK =
      "I'm having a little trouble with that right now. Please try again in a moment.";

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
    if (usesAi(recommendations, currentRequest)) {
      boolean explanation =
          responseAction(currentRequest) == ConversationAction.EXPLAIN_RECOMMENDATION;
      String notice = explanation ? "" : limitNotice(currentRequest);
      response =
          ai.boundedFallback(
              recommendations, currentRequest, explanation, notice, ResponseBudget.MAX_WORDS);
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

    boolean hasOutcome = currentRequest != null && currentRequest.getOutcome() != null;
    var outcome = currentRequest == null ? null : currentRequest.getOutcome();
    var action = responseAction(currentRequest);
    var pending = currentRequest == null ? null : currentRequest.getPendingQuestion();
    if (pending == null && currentRequest != null) {
      if (currentRequest.isSearchClarificationRequired()) {
        pending = ConversationQuestion.SEARCH_CLARIFICATION;
      } else if (currentRequest.isPreferenceClarificationRequired()) {
        pending = ConversationQuestion.PREFERENCE_CLARIFICATION;
      }
    }
    boolean activeQuestion =
        currentRequest != null
            && pending != null
            && !hasOutcome
            && action != ConversationAction.HELP
            && action != ConversationAction.UNKNOWN
            && switch (pending) {
              case PREFERENCE_CLARIFICATION -> currentRequest.isPreferenceClarificationRequired();
              case PREFERENCE_UPDATE_CLARIFICATION ->
                  action == ConversationAction.UPDATE_PREFERENCES
                      || currentRequest.getIntent() == ConversationAction.REPEAT;
              case EXPLANATION_TARGET_CLARIFICATION ->
                  action == ConversationAction.EXPLAIN_RECOMMENDATION
                      || currentRequest.getIntent() == ConversationAction.REPEAT;
              default -> currentRequest.isSearchClarificationRequired();
            };
    if (activeQuestion) {
      RequestTelemetry.responseMode(pending.name());
      return generateQuestionResponse(pending, currentRequest, labelWords);
    }
    String limitNotice =
        action == ConversationAction.EXPLAIN_RECOMMENDATION ? "" : limitNotice(currentRequest);
    String fixedResponse =
        outcome == null
            ? generateActionResponse(action, currentRequest)
            : generateOutcomeResponse(outcome, currentRequest);
    if (fixedResponse != null) {
      if ((action == ConversationAction.UNKNOWN
              || (action == ConversationAction.HELP
                  && (pending != ConversationQuestion.EXPLANATION_TARGET_CLARIFICATION
                      || currentRequest.getExplanationSelection() != null)))
          && pending != null) {
        String question = generateQuestionResponse(pending, currentRequest, labelWords);
        if (question != null) {
          fixedResponse =
              action == ConversationAction.UNKNOWN
                  ? "I didn't quite catch that. Just to clarify, " + question
                  : fixedResponse + " " + question;
        }
      }
      boolean noResults =
          outcome == ConversationOutcome.NOTHING_TO_REPEAT
              || outcome == ConversationOutcome.NO_RECOMMENDATIONS_TO_REPEAT;
      RequestTelemetry.responseMode(noResults ? "NO_RESULTS" : "ACTION_RESPONSE");
      return noResults ? fixedResponse : limitNotice + fixedResponse;
    }

    if (recommendations == null || recommendations.isEmpty()) {
      RequestTelemetry.responseMode("NO_RESULTS");
      if (currentRequest != null
          && (currentRequest.isNoMoreRecommendations()
              || action == ConversationAction.MORE_RESULTS)) {
        return limitNotice
            + "There are no more audiobooks matching your current search. Would you like to try a different author, genre, or filter?";
      }
      if (action == ConversationAction.REPEAT_RECOMMENDATIONS) {
        return "I don't have a list of recommendations to repeat right now. What kind of audiobook would you like?";
      }
      if (action == ConversationAction.REPEAT) {
        return "I don't have an earlier request to repeat yet. Would you like recommendations or help getting started?";
      }
      return limitNotice + generateFallbackResponse(currentRequest, labelWords);
    }

    String response =
        ai.generate(
            recommendations,
            currentRequest,
            action == ConversationAction.EXPLAIN_RECOMMENDATION,
            limitNotice);
    return response;
  }

  private String limitNotice(ConversationTurn request) {
    if (request != null && request.isInvalidBookCount()) {
      return responseAction(request) == ConversationAction.CHANGE_COUNT
          ? ""
          : "Recommendation counts must be whole numbers between 1 and "
              + MAX_BOOK_COUNT
              + ". I'll use your saved count of "
              + request.getBookCount()
              + ". ";
    }
    if (request == null
        || request.getBookCount() == null
        || request.getBookCount() <= MAX_BOOK_COUNT) {
      return "";
    }
    return "I can give up to " + MAX_BOOK_COUNT + " recommendations at a time. ";
  }

  private String generateOutcomeResponse(
      ConversationOutcome outcome, ConversationTurn currentRequest) {
    return switch (outcome) {
      case EMPTY_EXPLANATION_SELECTION ->
          "There aren't any books left to explain. Which books would you like me to explain?";
      case RECENCY_EXHAUSTED ->
          olderRequest(currentRequest)
              ? "There are no older books within the available publication years."
              : "There are no newer books within your selected period.";
      case SEARCH_DECLINED -> "No problem. Let me know when you’d like to try another search.";
      case NO_RECOMMENDATIONS_TO_REPEAT ->
          "I don't have a list of recommendations to repeat right now. What kind of audiobook would you like?";
      case NOTHING_TO_EXPLAIN ->
          "I haven't recommended any books in this session yet. What kind of audiobook would you like?";
      case EMPTY_EXPLANATION_PAGE ->
          "I didn't find any books in the latest search to explain. Would you like to try a different search?";
      case NOTHING_TO_REPEAT ->
          "I don't have an earlier request to repeat yet. Would you like recommendations or help getting started?";
      case UNAVAILABLE -> PROCESSING_FALLBACK;
    };
  }

  private String generateActionResponse(
      ConversationAction action, ConversationTurn currentRequest) {
    if (action == null) {
      return null;
    }
    return switch (action) {
      case CHANGE_COUNT ->
          currentRequest.isInvalidBookCount()
              ? "Recommendation counts must be whole numbers between 1 and "
                  + MAX_BOOK_COUNT
                  + ". Please try asking again with a number in this range. "
                  + "Your setting is still "
                  + currentRequest.getBookCount()
                  + "."
              : "I'll show "
                  + Math.clamp(currentRequest.getBookCount(), 1, MAX_BOOK_COUNT)
                  + " recommendation"
                  + (currentRequest.getBookCount() == 1 ? "" : "s")
                  + " at a time.";
      case EXPLAIN_PREFERENCES -> PreferenceResponse.render(currentRequest);
      case UPDATE_PREFERENCES -> "Thanks, I've updated your preferences for this session.";
      case RESET_PREFERENCES ->
          "All set, I've reset your preferences for this session and will show "
              + MAX_BOOK_COUNT
              + " recommendations at a time.";
      case CLEAR_HISTORY -> "All set, I've cleared this session's recommendation history.";
      case CANCEL -> "No problem, I've cancelled that. What would you like to do next?";
      case HELP ->
          "I can help you find audiobooks, adjust your search, or explore more books. You can ask me to "
              + "repeat my last reply or the last book list, explain a recommendation, or change how many books I show. "
              + "You can also view, update or reset your preferences, cancel a request, or clear this session's history.";
      case UNKNOWN ->
          "I'm not quite sure what you'd like to do. You can ask for audiobook recommendations or "
              + "say help to hear what I can do.";
      default -> null;
    };
  }

  private static ConversationAction responseAction(ConversationTurn request) {
    if (request == null) {
      return null;
    }
    return request.getResponseAction() == null ? request.getIntent() : request.getResponseAction();
  }

  private static boolean usesAi(List<Recommendation> recommendations, ConversationTurn request) {
    return request != null
        && request.getOutcome() == null
        && recommendations != null
        && !recommendations.isEmpty()
        && !isDirectAction(responseAction(request));
  }

  private static boolean isDirectAction(ConversationAction action) {
    return switch (action) {
      case CHANGE_COUNT,
          UPDATE_PREFERENCES,
          EXPLAIN_PREFERENCES,
          RESET_PREFERENCES,
          CLEAR_HISTORY,
          HELP,
          CANCEL,
          UNKNOWN ->
          true;
      case null, default -> false;
    };
  }

  private String generateQuestionResponse(
      ConversationQuestion question, ConversationTurn currentRequest, int labelWords) {
    return switch (question) {
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
      case SHORTER_DURATION_CLARIFICATION ->
          "How short would you like the audiobooks to be? For example, under 3 hours.";
      case RECENCY_RANGE_CONFIRMATION ->
          "Those years extend beyond your previous period. Would you like to use the new year range instead?";
      case RECENCY_YEAR_CLARIFICATION ->
          olderRequest(currentRequest)
              ? "I don't have publication years for those books. What ending year would you like?"
              : "I don't have publication years for those books. What year would you like me to start from? For example, from 2000.";
      case PREFERENCE_UPDATE_CLARIFICATION ->
          "What would you like to change about your preferences?";
      case EXPLANATION_TARGET_CLARIFICATION -> explanationClarification(currentRequest, labelWords);
      case SEARCH_CLARIFICATION -> "What kind of audiobook would you like?";
      case SEARCH_CHANGE_OFFER -> "Would you like to try a different author, genre, or filter?";
      case SEARCH_CHANGE_CLARIFICATION -> "What would you like to change about your search?";
      case PREFERENCE_CLARIFICATION -> "What genres do you like?";
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
