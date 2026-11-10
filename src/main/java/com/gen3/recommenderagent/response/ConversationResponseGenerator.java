package com.gen3.recommenderagent.response;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.util.List;
import org.springframework.stereotype.Component;

/** Answers fixed conversation decisions and delegates book narration to AI. */
@Component
public class ConversationResponseGenerator implements ResponseGenerator {
  private static final String PROCESSING_FALLBACK =
      "I'm having a little trouble with that right now. Please try again in a moment.";

  private final AiResponseGenerator ai;
  private final com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers;

  @org.springframework.beans.factory.annotation.Autowired
  public ConversationResponseGenerator(
      AiResponseGenerator ai,
      com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers) {
    this.ai = ai;
    this.handlers = handlers;
  }

  /** {@inheritDoc} */
  @Override
  public String generate(List<Recommendation> recommendations, ConversationTurn currentRequest) {
    String response = render(recommendations, currentRequest, Integer.MAX_VALUE);
    // Preference reports enforce their own budget so exact constraints are never cut off.
    if ((responseAction(currentRequest) != null
            && handlers.handler(responseAction(currentRequest)).ownsResponseBudget())
        || ResponseBudget.wordCount(response) <= ResponseBudget.MAX_WORDS) {
      return response;
    }
    if (usesAi(recommendations, currentRequest)) {
      boolean explanation = explainsBooks(currentRequest);
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

  /**
   * Chooses fixed action, outcome, clarification, or AI narration wording for the completed turn.
   */
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
        pending = RecommendationHandler.SEARCH_CLARIFICATION;
      } else if (currentRequest.isPreferenceClarificationRequired()) {
        pending = RecommendationHandler.PREFERENCE_CLARIFICATION;
      }
    }
    boolean activeQuestion =
        currentRequest != null
            && pending != null
            && !hasOutcome
            && handlers.handler(action).questionPolicy()
                != com.gen3.recommenderagent.application.conversation.ConversationHandler
                    .QuestionPolicy.ATTACH
            && handlers.questionHandler(pending).questionActive(pending, currentRequest);
    if (activeQuestion) {
      RequestTelemetry.responseMode(pending.name());
      return generateQuestionResponse(pending, currentRequest, labelWords);
    }
    String limitNotice =
        (action != null && handlers.handler(action).explainsBooks())
            ? ""
            : limitNotice(currentRequest);
    String fixedResponse = generateActionResponse(action, currentRequest);
    if (fixedResponse == null && outcome != null) {
      fixedResponse = generateOutcomeResponse(outcome, currentRequest);
    }
    if (fixedResponse != null) {
      if (pending != null
          && action != null
          && handlers.handler(action).includesPendingQuestion(currentRequest)) {
        String question = generateQuestionResponse(pending, currentRequest, labelWords);
        if (question != null) {
          fixedResponse = handlers.handler(action).withQuestion(fixedResponse, question);
        }
      }
      boolean noResults = outcome != null && handlers.outcomeHandler(outcome).emptyOutcome(outcome);
      RequestTelemetry.responseMode(noResults ? "NO_RESULTS" : "ACTION_RESPONSE");
      return noResults ? fixedResponse : limitNotice + fixedResponse;
    }

    if (recommendations == null || recommendations.isEmpty()) {
      RequestTelemetry.responseMode("NO_RESULTS");
      var exhausted = currentRequest == null ? null : handlers.exhaustedResponse(currentRequest);
      if (exhausted != null) {
        return limitNotice + exhausted;
      }
      String emptyResponse =
          action == null ? null : handlers.handler(action).emptyResponse(currentRequest);
      if (emptyResponse != null) {
        return limitNotice + emptyResponse;
      }
      return limitNotice + generateFallbackResponse(currentRequest, labelWords);
    }

    String response =
        ai.generate(
            recommendations,
            currentRequest,
            (action != null && handlers.handler(action).explainsBooks()),
            limitNotice);
    return response;
  }

  /** Explains unsupported recommendation counts while retaining the resolved page count. */
  private String limitNotice(ConversationTurn request) {
    if (request != null && request.isInvalidBookCount()) {
      return handlers.handler(responseAction(request)).suppressesCountNotice()
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

  /** Formats the built-in response for a completed conversation outcome. */
  private String generateOutcomeResponse(
      ConversationOutcome outcome, ConversationTurn currentRequest) {
    var response = handlers.outcomeResponse(currentRequest);
    return response == null ? PROCESSING_FALLBACK : response;
  }

  /** Formats built-in session-action responses without AI narration. */
  private String generateActionResponse(
      ConversationAction action, ConversationTurn currentRequest) {
    if (action == null) {
      return null;
    }
    return handlers.handler(action).response(currentRequest);
  }

  /** Resolves the original response action when a retained turn is being replayed. */
  private static ConversationAction responseAction(ConversationTurn request) {
    if (request == null) {
      return null;
    }
    return request.getResponseAction() == null ? request.getIntent() : request.getResponseAction();
  }

  private boolean explainsBooks(ConversationTurn request) {
    var action = responseAction(request);
    return action != null && handlers.handler(action).explainsBooks();
  }

  /** Reports whether this response requires model-generated book narration. */
  private boolean usesAi(List<Recommendation> recommendations, ConversationTurn request) {
    return request != null
        && request.getOutcome() == null
        && recommendations != null
        && !recommendations.isEmpty()
        && !isDirectAction(responseAction(request));
  }

  /** Identifies actions with built-in responses that do not require book narration. */
  private boolean isDirectAction(ConversationAction action) {
    return action != null && !handlers.handler(action).narratesBooks();
  }

  /** Uses the question owner's wording even when help, unknown, or replay attaches it. */
  private String generateQuestionResponse(
      ConversationQuestion question, ConversationTurn turn, int labelWords) {
    return handlers.questionHandler(question).questionResponse(question, turn, labelWords);
  }

  // Safe node output
  /** Builds deterministic wording when no recommendation narration is available. */
  private String generateFallbackResponse(ConversationTurn currentRequest, int labelWords) {
    if (currentRequest != null && currentRequest.getIntent() != null) {
      var fallback =
          handlers.handler(currentRequest.getIntent()).fallbackResponse(currentRequest, labelWords);
      if (fallback != null) {
        return fallback;
      }
    }
    return PROCESSING_FALLBACK;
  }
}
