package com.gen3.recommenderagent.response;

import static com.gen3.recommenderagent.common.ApplicationConstants.MAX_BOOK_COUNT;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.openai.errors.OpenAIException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class AiResponseGenerator implements ResponseGenerator {

  // Holds the AI
  private final ChatClient chatClient;

  public AiResponseGenerator(ChatClient.Builder chatClientBuilder) {
    this.chatClient = (chatClientBuilder != null) ? chatClientBuilder.build() : null;
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
      return "I'm sorry, I couldn't process your request right now.";
    }

    if (currentRequest != null
        && currentRequest.getBookCount() != null
        && currentRequest.getBookCount() > MAX_BOOK_COUNT) {
      return buildRecommendationLimitResponse(currentRequest);
    }

    // If there are no reccomendations, it returns a fallback message
    if (recommendations == null || recommendations.isEmpty()) {
      return generateFallbackResponse(currentRequest);
    }

    // Builds a text summary of the request + top recommendation list to send to the
    // AI
    // model
    String prompt = compilePrompt(recommendations, currentRequest);

    // If the AI call fails, don’t crash — fall back to a simple built-in response.
    if (chatClient == null) {
      return buildHumanReadableResponse(recommendations, currentRequest);
    }

    try {
      return chatClient
          .prompt()
          .system(
              """
                            You are a helpful audiobook recommendation assistant speaking directly to a user through a voice interface.
                            Rewrite the structured recommendation data into a short, natural-sounding spoken response.
                            Tone should be friendly, conversational, and concise.
                            Include the user's intent and mention each recommendation by its title.
                            CRITICAL INSTRUCTIONS FOR OUTPUT:
                            - Do NOT use any markdown formatting (no asterisks, bold, or italics).
                            - Do NOT use quotation marks around book titles. Just state the title naturally.
                            - Clean up book titles by removing any weird catalog artifacts, symbols, or subtitle separators (for example, remove "$b" or colons).
                            - Use simple sentences with commas and periods to create natural breathing pauses for the text-to-speech engine.
                            - Avoid complex punctuation like em-dashes or semicolons.
                            """)
          .user(prompt)
          .call()
          .content();
    } catch (OpenAIException exception) {
      return buildHumanReadableResponse(recommendations, currentRequest);
    }
  }

  // Turns structure Java objects into a text prompt
  private String compilePrompt(
      List<Recommendation> recommendations, SessionRequest currentRequest) {
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
        "User request: %s. Intent: %s. Genres: %s. Recommendations: %s.",
        requestSummary, intentSummary, genresSummary, recommendationsSummary);
  }

  // Turns one Reccomendation object into a readable text fragment
  private String formatRecommendationForPrompt(Recommendation recommendation) {
    // Safeguard for null entries
    if (recommendation == null) {
      return "empty recommendation";
    }

    return String.format(
        "rank=%s title=\"%s\" score=%s",
        recommendation.getRank() != null ? recommendation.getRank() : "n/a",
        recommendation.getTitle() != null ? recommendation.getTitle() : "unknown",
        recommendation.getScore() != null ? recommendation.getScore() : "n/a");
  }

  // Creates a clean message when AI is not used
  private String buildHumanReadableResponse(
      List<Recommendation> recommendations, SessionRequest currentRequest) {
    StringBuilder response = new StringBuilder();
    response.append("Here are my recommendations");

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

        // Format as "Number 1, Title."
        response
                .append("Number ")
                .append(recommendation.getRank() != null ? recommendation.getRank() : "unknown")
                .append(", ");

        String title = recommendation.getTitle() != null ? recommendation.getTitle() : "Unknown title";

        // Strip out common catalog artifacts (like $b) so the voice doesn't read them out loud
        title = title.replace("$b", "").trim();

        response.append(title).append(". ");
    }

    return response.toString().trim();
  }

  private String buildRecommendationLimitResponse(SessionRequest currentRequest) {
    if (currentRequest != null
        && currentRequest.getRawText() != null
        && !currentRequest.getRawText().isBlank()) {
      return "I can only give a maximum of "
          + MAX_BOOK_COUNT
          + " recommendations at a time, so I can't fulfil that request for "
          + currentRequest.getRawText()
          + ".";
    }

    return "I can only give a maximum of " + MAX_BOOK_COUNT + " recommendations at a time.";
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

  // Safe node output
  private String generateFallbackResponse(SessionRequest currentRequest) {
    if (currentRequest != null
        && currentRequest.getRawText() != null
        && !currentRequest.getRawText().isBlank()) {
      return "Sorry, I couldn't find any recommendations for: "
          + currentRequest.getRawText()
          + ". Please try a different request.";
    }

    return "I'm sorry, I couldn't process your request right now.";
  }
}
