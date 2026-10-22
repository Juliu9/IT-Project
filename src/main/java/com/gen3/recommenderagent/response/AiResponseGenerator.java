package com.gen3.recommenderagent.response;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AiResponseGenerator implements ResponseGenerator {

  private static final Logger LOGGER = LoggerFactory.getLogger(AiResponseGenerator.class);

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

    if (currentRequest != null && currentRequest.isSearchClarificationRequired()) {
      RequestTelemetry.responseMode("SEARCH_CLARIFICATION");
      return "What kind of audiobook would you like?";
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
      if (currentRequest != null && currentRequest.getIntent() == Intent.REPEAT) {
        return "There are no previous recommendations to repeat. Ask me for audiobooks first.";
      }
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

    ChatResponse aiResponse;
    try {
      aiResponse =
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
                            For each book, briefly explain why it is a good fit by connecting specific details in its supplied short description to the user's request and supplied preferences.
                            Speak directly to the user, for example, "You asked for adventure, and this story follows a dangerous expedition."
                            Ground every explanation in the supplied data. Never invent book details, user preferences, or claim a match that the short description does not support.
                            If the short description is missing or does not establish a clear match, introduce the book without inventing a reason it fits.
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
    } catch (RuntimeException exception) {
      LOGGER.warn(
          "Response model failed; using built-in response. exception={}",
          exception.getClass().getSimpleName());
      RequestTelemetry.responseMode("FALLBACK");
      return limitNotice + buildHumanReadableResponse(recommendations, currentRequest);
    }
    if (aiResponse == null
        || aiResponse.getResult() == null
        || aiResponse.getResult().getOutput() == null
        || aiResponse.getResult().getOutput().getText() == null
        || aiResponse.getResult().getOutput().getText().isBlank()) {
      LOGGER.warn("Response model returned no usable text; using built-in response");
      RequestTelemetry.responseMode("FALLBACK");
      return limitNotice + buildHumanReadableResponse(recommendations, currentRequest);
    }
    RequestTelemetry.responseMode("AI");
    return aiResponse.getResult().getOutput().getText();
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
    return resolvedCriteria(currentRequest)
        + String.format(
            "User request: %s. Intent: %s. Genres: %s. Recommendation count: %d. Limit notice: %s. Recommendations: %s.",
            requestSummary,
            intentSummary,
            genresSummary,
            topFive.size(),
            limitNotice.isBlank() ? "none" : limitNotice.trim(),
            recommendationsSummary);
  }

  private String resolvedCriteria(SessionRequest request) {
    if (request == null) {
      return "";
    }
    var include = request.getMustInclude();
    var exclude = request.getMustNotInclude();
    return String.format(
        "Resolved search: personalised=%s. Desired: %s. Avoided: %s. Must include: %s. Must exclude: %s. ",
        request.isPersonalised(),
        semanticSummary(request.getPositiveSemanticQuery()),
        semanticSummary(request.getNegativeSemanticQuery()),
        include == null
            ? "none"
            : String.format(
                "authors=%s, narrators=%s, language=%s, duration=%s, publicationYear=%s, source=%s",
                include.getAuthors(),
                include.getNarrators(),
                include.getLanguage(),
                include.getDuration(),
                include.getPublicationYear(),
                include.getSource()),
        exclude == null
            ? "none"
            : String.format(
                "authors=%s, narrators=%s, language=%s, duration=%s, publicationYear=%s, source=%s",
                exclude.getAuthors(),
                exclude.getNarrators(),
                exclude.getLanguage(),
                exclude.getDuration(),
                exclude.getPublicationYear(),
                exclude.getSource()));
  }

  private String semanticSummary(SemanticQuery query) {
    return query == null
        ? "none"
        : String.format(
            "topics=%s, genres=%s, keywords=%s, authors=%s, narrators=%s",
            query.getTopics(),
            query.getGenres(),
            query.getKeywords(),
            query.getAuthors(),
            query.getNarrators());
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
          "I can help you find audiobooks, refine a search, show more results, repeat the last recommendations, change the result "
              + "count, update your preferences, or clear this session's history.";
      case UNKNOWN ->
          "I'm not quite sure what you'd like to do. You can ask for audiobook recommendations or "
              + "say help to hear what I can do.";
      case RECOMMENDATION, REFINE, MORE_RESULTS, REPEAT -> null;
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
