package com.gen3.recommenderagent.response;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.domain.conversation.ConversationRules;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Recommendation;
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
    // if both inputs are missing, the method returns a polite error message instead
    // of crashing
    if (recommendations == null && currentRequest == null) {
      return PROCESSING_FALLBACK;
    }

    var decision =
        currentRequest != null && currentRequest.getDecision() != null
            ? currentRequest.getDecision()
            : ConversationRules.decisionFor(
                recommendations, currentRequest == null ? null : currentRequest.toDraft());
    if (decision == ConversationDecision.SEARCH_CLARIFICATION) {
      RequestTelemetry.responseMode("SEARCH_CLARIFICATION");
      return "What kind of audiobook would you like?";
    }
    if (decision == ConversationDecision.PREFERENCE_CLARIFICATION) {
      RequestTelemetry.responseMode("PREFERENCE_CLARIFICATION");
      return "I'd love to find something you'll enjoy. What genres, authors, or narrators do you like?";
    }
    String limitNotice =
        decision == ConversationDecision.EXPLANATION ? "" : limitNotice(currentRequest);
    String fixedResponse = generateOutcomeResponse(decision, currentRequest);
    if (fixedResponse != null) {
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

    return ai.generate(
        recommendations, currentRequest, decision == ConversationDecision.EXPLANATION, limitNotice);
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
      ConversationDecision decision, ConversationTurn currentRequest) {
    return switch (decision) {
      case COUNT_CLARIFICATION -> "How many recommendations would you like at a time?";
      case COUNT_CHANGED -> {
        int count = Math.clamp(currentRequest.getBookCount(), 1, MAX_BOOK_COUNT);
        yield "I'll show " + count + " recommendation" + (count == 1 ? "" : "s") + " at a time.";
      }
      case PREFERENCE_UPDATE_CLARIFICATION ->
          "What would you like to change about your preferences?";
      case PREFERENCES_UPDATED -> "Thanks, I've updated your preferences for this session.";
      case HISTORY_CLEARED -> "All set, I've cleared this session's recommendation history.";
      case CANCELLED -> "Okay, cancelled. Let me know what you would like next.";
      case HELP ->
          "I can help you find audiobooks, refine a search, show more results, repeat the last request or book page, change the result "
              + "count, explain why books were recommended, update your preferences, cancel, or clear this session's history.";
      case UNKNOWN ->
          "I'm not quite sure what you'd like to do. You can ask for audiobook recommendations or "
              + "say help to hear what I can do.";
      case NO_RECOMMENDATIONS_TO_REPEAT ->
          "There are no recommendations to repeat. Ask me for audiobooks first.";
      case NOTHING_TO_EXPLAIN ->
          "I haven't recommended any books yet. Ask me for recommendations first.";
      case EMPTY_EXPLANATION_PAGE -> "The latest request returned no recommendations to explain.";
      case EXPLANATION_TARGET_CLARIFICATION -> explanationClarification(currentRequest);
      case NO_MORE_RESULTS ->
          "There are no more audiobooks matching your current search. Try changing your criteria.";
      case NOTHING_TO_REPEAT ->
          "There is no previous request to repeat. Ask me for recommendations or help first.";
      case NO_MATCHES -> generateFallbackResponse(currentRequest);
      case UNAVAILABLE -> PROCESSING_FALLBACK;
      case SEARCH_CLARIFICATION, PREFERENCE_CLARIFICATION, RECOMMENDATIONS, EXPLANATION -> null;
    };
  }

  private String explanationClarification(ConversationTurn request) {
    List<Recommendation> page = request.getRecommendations();
    String titles =
        page.stream()
            .map(book -> book.getRank() + ". " + book.getTitle())
            .collect(Collectors.joining(". "));
    return "I returned "
        + page.size()
        + " book"
        + (page.size() == 1 ? "" : "s")
        + ": "
        + titles
        + ". Which one did you mean?";
  }

  // Safe node output
  private String generateFallbackResponse(ConversationTurn currentRequest) {
    if (currentRequest != null
        && currentRequest.getMustInclude() != null
        && currentRequest.getMustInclude().getPublicationYear() != null) {
      return "I couldn't find books matching those criteria. Try changing your criteria.";
    }
    if (currentRequest != null
        && currentRequest.getRawText() != null
        && !currentRequest.getRawText().isBlank()) {
      return "I couldn't find a good match for "
          + currentRequest.getRawText()
          + " just yet. Try a genre, author, narrator, or a different description.";
    }

    return PROCESSING_FALLBACK;
  }
}
