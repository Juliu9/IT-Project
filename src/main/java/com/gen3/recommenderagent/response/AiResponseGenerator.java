package com.gen3.recommenderagent.response;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.openai.errors.OpenAIException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AiResponseGenerator implements ResponseGenerator {

  private static final String PROCESSING_FALLBACK =
      "I'm having a little trouble with that right now. Please try again in a moment.";
  private static final Pattern EXPLICIT_OVERSIZED_COUNT =
      Pattern.compile(
          "\\b(?:[6-9]|[1-9]\\d+|six|seven|eight|nine|ten|eleven|twelve|dozen|"
              + "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\\b",
          Pattern.CASE_INSENSITIVE);

  // Holds the AI
  private final ChatClient chatClient;
  private final AiOperationObserver aiOperationObserver;

  @Autowired
  public AiResponseGenerator(
      @Qualifier("responseGeneratorChatClient") ChatClient chatClient,
      AiOperationObserver aiOperationObserver) {
    this.chatClient = chatClient;
    this.aiOperationObserver = aiOperationObserver;
  }

  public AiResponseGenerator(ChatClient.Builder chatClientBuilder) {
    this(chatClientBuilder != null ? chatClientBuilder.build() : null, AiOperationObserver.noop());
  }

  /**
   * Translates structured recommendations and user request context into a natural language
   * response.
   *
   * @param recommendations The evaluated recommendation data payload.
   * @param currentRequest The parsed metadata and intent from the user's raw input.
   * @return A natural language string tailored for the end user.
   */
  @Override
  public String generate(List<Recommendation> recommendations, SessionRequest currentRequest) {

    // if both inputs are missing, the method returns a polite error message instead
    // of crashing
    if (recommendations == null && currentRequest == null) {
      return PROCESSING_FALLBACK;
    }

    if (currentRequest != null && currentRequest.isPreferenceClarificationRequired()) {
      RequestTelemetry.responseMode("PREFERENCE_CLARIFICATION");
      return "I'd love to find something you'll enjoy. What genres, authors, or narrators do you like?";
    }

    String limitNotice = limitNotice(currentRequest);
    String actionResponse = generateActionResponse(currentRequest);
    if (actionResponse != null) {
      RequestTelemetry.responseMode("ACTION_RESPONSE");
      return limitNotice + actionResponse;
    }

    // If there are no recommendations, it returns a fallback message
    if (recommendations == null || recommendations.isEmpty()) {
      RequestTelemetry.responseMode("NO_RESULTS");
      return limitNotice + generateFallbackResponse(currentRequest);
    }

    // Builds a text summary of the request + top recommendation list to send to the
    // AI
    // model
    String prompt = compilePrompt(recommendations, currentRequest, limitNotice);

    // If the AI call fails, don’t crash — fall back to a simple built-in response.
    if (chatClient == null) {
      RequestTelemetry.responseMode("FALLBACK");
      return limitNotice + buildHumanReadableResponse(recommendations, currentRequest);
    }

    try {
      ChatResponse aiResponse =
          aiOperationObserver.observe(
              "generate_response",
              () ->
                  chatClient
                      .prompt()
                      .system(
                          """
                            You are a helpful audiobook recommendation assistant speaking directly to a user through a voice interface.
                            Rewrite the structured recommendation data into a short, natural-sounding spoken response.
                            Tone should be friendly, conversational, and concise.
                            Include the user's intent, each recommendation's title, and its supplied short description when available.
                            CRITICAL INSTRUCTIONS FOR OUTPUT:
                            - Do NOT use any markdown formatting (no asterisks, bold, or italics).
                            - Do NOT use quotation marks around book titles. Just state the title naturally.
                            - Clean up book titles by removing any weird catalog artifacts, symbols, or subtitle separators (for example, remove "$b" or colons).
                            - Use simple sentences with commas and periods to create natural breathing pauses for the text-to-speech engine.
                            - Avoid complex punctuation like em-dashes or semicolons.
                            - Mention exactly the number of supplied recommendations; never say or imply a larger number.
                            - If a limit notice is supplied, start with it verbatim, then continue naturally without repeating it.
                            """)
                      .user(prompt)
                      .call()
                      .chatResponse(),
              AiOperationObserver.AiUsage::from);
      RequestTelemetry.responseMode("AI");
      return aiResponse.getResult().getOutput().getText();
    } catch (OpenAIException exception) {
      RequestTelemetry.responseMode("FALLBACK");
      return limitNotice + buildHumanReadableResponse(recommendations, currentRequest);
    }
  }

  // Turns structure Java objects into a text prompt
  String compilePrompt(
      List<Recommendation> recommendations, SessionRequest currentRequest, String limitNotice) {
    // Extracts the original user text if available
    String requestSummary =
        (currentRequest != null && currentRequest.getRawText() != null)
            ? currentRequest.getRawText()
            : "No raw request provided";

    // Reads the intent type
    String intentSummary =
        (currentRequest != null && currentRequest.getIntent() != null)
            ? currentRequest.getIntent().name()
            : "UNKNOWN";

    // Pulls genres from the parsed request
    String genresSummary =
        (currentRequest != null
                && currentRequest.getPositiveSemanticQuery() != null
                && currentRequest.getPositiveSemanticQuery().getGenres() != null)
            ? String.join(", ", currentRequest.getPositiveSemanticQuery().getGenres())
            : "not specified";

    // Converts only the top five reccomendation objects into a plain text summary
    // for AI to understand
    List<Recommendation> topFive = getTopRecommendations(recommendations);
    String recommendationsSummary =
        topFive.stream().map(this::formatRecommendationForPrompt).collect(Collectors.joining("; "));

    // Produces the final prompt string the AI sees
    return String.format(
        "User request: %s. Intent: %s. Genres: %s. Recommendation count: %d. Limit notice: %s. Recommendations: %s.",
        requestSummary,
        intentSummary,
        genresSummary,
        topFive.size(),
        limitNotice.isBlank() ? "none" : limitNotice.trim(),
        recommendationsSummary);
  }

  // Turns one Reccomendation object into a readable text fragment
  private String formatRecommendationForPrompt(Recommendation recommendation) {
    // Safeguard for null entries
    if (recommendation == null) {
      return "empty recommendation";
    }

    return String.format(
        "rank=%s title=\"%s\" description=\"%s\" score=%s",
        recommendation.getRank() != null ? recommendation.getRank() : "n/a",
        recommendation.getTitle() != null ? recommendation.getTitle() : "unknown",
        recommendation.getShortDescription() != null
            ? recommendation.getShortDescription()
            : "not available",
        recommendation.getScore() != null ? recommendation.getScore() : "n/a");
  }

  // Creates a clean message when AI is not used
  private String buildHumanReadableResponse(
      List<Recommendation> recommendations, SessionRequest currentRequest) {
    StringBuilder response = new StringBuilder();
    response.append("Here are a few recommendations");

    if (currentRequest != null && currentRequest.getIntent() != null) {
      String intentName = currentRequest.getIntent().name().toLowerCase().replace("_", " ");
      // Skip saying "for your recommendation request" as it sounds redundant when spoken
      if (!intentName.equals("recommendation")) {
        response.append(" for your ").append(intentName).append(" request");
      }
    }

    if (currentRequest != null
        && currentRequest.getPositiveSemanticQuery() != null
        && currentRequest.getPositiveSemanticQuery().getGenres() != null
        && !currentRequest.getPositiveSemanticQuery().getGenres().isEmpty()) {
      response
          .append(" in ")
          .append(String.join(" and ", currentRequest.getPositiveSemanticQuery().getGenres()));
    }

    response.append(". ");

    List<Recommendation> items = getTopRecommendations(recommendations);
    for (Recommendation recommendation : items) {
      if (recommendation == null) {
        continue;
      }

      response
          .append("Number ")
          .append(recommendation.getRank() != null ? recommendation.getRank() : "unknown")
          .append(", ");

      String title =
          recommendation.getTitle() != null ? recommendation.getTitle() : "Unknown title";

      title = title.replace("$b", "").trim();

      response.append(title);
      if (recommendation.getShortDescription() != null
          && !recommendation.getShortDescription().isBlank()) {
        response.append(", ").append(recommendation.getShortDescription());
      }
      if (recommendation.getBookId() != null && !recommendation.getBookId().isBlank()) {
        response.append(" (").append(recommendation.getBookId()).append(")");
      }
      response.append(". ");
    }

    return response.toString().trim();
  }

  private List<Recommendation> getTopRecommendations(List<Recommendation> recommendations) {
    if (recommendations == null || recommendations.isEmpty()) {
      return List.of();
    }

    if (recommendations.size() <= MAX_BOOK_COUNT) {
      return recommendations;
    }

    return recommendations.subList(0, MAX_BOOK_COUNT);
  }

  private String limitNotice(SessionRequest request) {
    if (request == null
        || request.getBookCount() == null
        || request.getBookCount() <= MAX_BOOK_COUNT
        || request.getRawText() == null
        || !EXPLICIT_OVERSIZED_COUNT.matcher(request.getRawText()).find()) {
      return "";
    }
    return "I can give up to " + MAX_BOOK_COUNT + " recommendations at a time. ";
  }

  private String generateActionResponse(SessionRequest currentRequest) {
    if (currentRequest == null || currentRequest.getIntent() == null) {
      return null;
    }

    return switch (currentRequest.getIntent()) {
      case CHANGE_COUNT -> {
        if (currentRequest.getBookCount() == null) {
          yield "How many recommendations would you like at a time?";
        }
        int count = Math.clamp(currentRequest.getBookCount(), 1, MAX_BOOK_COUNT);
        yield "I'll show " + count + " recommendation" + (count == 1 ? "" : "s") + " at a time.";
      }
      case UPDATE_PREFERENCES -> "Thanks, I've updated your preferences for this session.";
      case CLEAR_HISTORY -> "All set, I've cleared this session's recommendation history.";
      case HELP ->
          "I can help you find audiobooks, refine a search, show more results, change the result "
              + "count, update your preferences, or clear this session's history.";
      case UNKNOWN ->
          "I'm not quite sure what you'd like to do. You can ask for audiobook recommendations or "
              + "say help to hear what I can do.";
      case RECOMMENDATION, REFINE, MORE_RESULTS -> null;
    };
  }

  // Safe node output
  private String generateFallbackResponse(SessionRequest currentRequest) {
    if (hasPublicationYearFilter(currentRequest)) {
      return "Sorry, we don't have books from that time period.";
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

  private boolean hasPublicationYearFilter(SessionRequest request) {
    return request != null
        && request.getMustInclude() != null
        && request.getMustInclude().getPublicationYear() != null
        && !request.getMustInclude().getPublicationYear().isBlank();
  }
}
