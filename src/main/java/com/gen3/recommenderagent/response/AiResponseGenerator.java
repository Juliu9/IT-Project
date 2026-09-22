package com.gen3.recommenderagent.response;

import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AiResponseGenerator implements ResponseGenerator {

    // Holds the AI
    private final ChatClient chatClient;

    public AiResponseGenerator() {
        this(null);
    }

    public AiResponseGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = (chatClientBuilder != null) ? chatClientBuilder.build() : null;
    }

    /**
     * Translates structured recommendations and user request context into a natural
     * language response.
     *
     * @param recommendations The evaluated recommendation data payload.
     * @param currentRequest  The parsed metadata and intent from the user's raw
     *                        input.
     * @return A natural language string tailored for the end user.
     */
    @Override
    public String generate(List<Recommendation> recommendations, SessionRequest currentRequest) {

        // if both inputs are missing, the method returns a polite error message instead
        // of crashing
        if (recommendations == null && currentRequest == null) {
            return "I'm sorry, I couldn't process your request right now.";
        }

        if (currentRequest != null && currentRequest.getConstraints() != null
                && currentRequest.getConstraints().getCount() != null
                && currentRequest.getConstraints().getCount() > 5) {
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
            return chatClient.prompt()
                    .system("""
                            You are a helpful audiobook recommendation assistant.
                            Rewrite the structured recommendation data into a short, natural-sounding response.
                            Tone should be friendly, concise, and easy to read.
                            Include the user's intent and mention each recommendation by its id or ranking when possible.
                            """)
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception exception) {
            return buildHumanReadableResponse(recommendations, currentRequest);
        }
    }

    // Turns structure Java objects into a text prompt
    private String compilePrompt(List<Recommendation> recommendations, SessionRequest currentRequest) {
        // Extracts the original user text if available
        String requestSummary = (currentRequest != null && currentRequest.getRawText() != null)
                ? currentRequest.getRawText()
                : "No raw request provided";

        // Reads the intent type
        String intentSummary = (currentRequest != null && currentRequest.getIntent() != null)
                ? currentRequest.getIntent().name()
                : "UNKNOWN";

        // Pulls genres from the parsed request
        String genresSummary = (currentRequest != null && currentRequest.getQuery() != null
                && currentRequest.getQuery().getGenres() != null)
                        ? String.join(", ", currentRequest.getQuery().getGenres())
                        : "not specified";

        // Converts only the top five reccomendation objects into a plain text summary
        // for AI to understand
        List<Recommendation> topFive = getTopRecommendations(recommendations);
        String recommendationsSummary = topFive.stream()
                .map(this::formatRecommendationForPrompt)
                .collect(Collectors.joining("; "));

        // Produces the final prompt string the AI sees
        return String.format(
                "User request: %s. Intent: %s. Genres: %s. Recommendations: %s.",
                requestSummary,
                intentSummary,
                genresSummary,
                recommendationsSummary);
    }

    // Turns one Reccomendation object into a readable text fragment
    private String formatRecommendationForPrompt(Recommendation recommendation) {
        // Safeguard for null entries
        if (recommendation == null) {
            return "empty recommendation";
        }

        // Format each recommendation into a compact line
        return String.format(
                "rank=%s bookId=%s score=%s",
                recommendation.getRank() != null ? recommendation.getRank() : "n/a",
                recommendation.getBookId() != null ? recommendation.getBookId() : "unknown",
                recommendation.getScore() != null ? recommendation.getScore() : "n/a");
    }

    // Creates a clean message when AI is not used
    private String buildHumanReadableResponse(List<Recommendation> recommendations, SessionRequest currentRequest) {
        StringBuilder response = new StringBuilder();
        response.append("Here are my recommendations");

        if (currentRequest != null && currentRequest.getIntent() != null) {
            response.append(" for your ")
                    .append(currentRequest.getIntent().name().toLowerCase().replace("_", " "))
                    .append(" request");
        }

        if (currentRequest != null && currentRequest.getQuery() != null
                && currentRequest.getQuery().getGenres() != null
                && !currentRequest.getQuery().getGenres().isEmpty()) {
            response.append(" in ")
                    .append(String.join(", ", currentRequest.getQuery().getGenres()));
        }

        response.append(":\n");

        List<Recommendation> items = getTopRecommendations(recommendations);
        for (Recommendation recommendation : items) {
            if (recommendation == null) {
                continue;
            }

            response.append("- ")
                    .append(recommendation.getRank() != null ? recommendation.getRank() : "?")
                    .append(". ")
                    .append(recommendation.getBookId() != null ? recommendation.getBookId() : "Unknown title");

            if (recommendation.getScore() != null) {
                response.append(" (match score: ")
                        .append(recommendation.getScore())
                        .append(")");
            }

            response.append("\n");
        }

        return response.toString().trim();
    }

    private String buildRecommendationLimitResponse(SessionRequest currentRequest) {
        if (currentRequest != null && currentRequest.getRawText() != null && !currentRequest.getRawText().isBlank()) {
            return "I can only give a maximum of 5 recommendations at a time, so I can't fulfil that request for "
                    + currentRequest.getRawText() + ".";
        }

        return "I can only give a maximum of 5 recommendations at a time.";
    }

    private List<Recommendation> getTopRecommendations(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }

        if (recommendations.size() <= 5) {
            return recommendations;
        }

        return recommendations.subList(0, 5);
    }

    // Safe node output
    private String generateFallbackResponse(SessionRequest currentRequest) {
        if (currentRequest != null && currentRequest.getRawText() != null && !currentRequest.getRawText().isBlank()) {
            return "Sorry, I couldn't find any recommendations for: " + currentRequest.getRawText()
                    + ". Please try a different request.";
        }

        return "I'm sorry, I couldn't process your request right now.";
    }
}
