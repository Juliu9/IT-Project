package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(InputParserTest.TestConfiguration.class)
class InputParserTest {

    @Autowired
    private AiInputParser parser;

    @DynamicPropertySource
    static void configureOpenAi(DynamicPropertyRegistry registry) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not set"
            );
        }

        registry.add(
                "spring.ai.openai.api-key",
                () -> apiKey
        );

        registry.add(
                "spring.ai.openai.chat.model",
                () -> "gpt-4o-mini"
        );
    }

    @Test
    void shouldConvertRawTextIntoSessionRequest() {
        // Arrange
        String rawText = "Recommend three English science fiction audiobooks.";

        // Time
        long start = System.nanoTime();

        // Act
        var response = parser.parse(rawText);
        SessionRequest sessionRequest = response.entity();
        Usage chatUsage = response.response().getMetadata().getUsage();

        long end = System.nanoTime();
        long elapsedMs = (end - start) / 1_000_000;

        // Print results
        System.out.println("\n=== Agent Metrics ===");
        System.out.println("Agent request time: " + elapsedMs + " ms");
        System.out.println(chatUsage);
        printAgentResponse(sessionRequest);

        // Assert
        assertNotNull(sessionRequest);
        assertEquals(rawText, sessionRequest.getRawText());

        assertNotNull(sessionRequest.getRequestId());
        UUID requestId = UUID.fromString(sessionRequest.getRequestId());
        assertEquals(7, requestId.version());
        assertEquals(2, requestId.variant());

        assertEquals(Intent.NEW_RECOMMENDATION, sessionRequest.getIntent());

        assertNotNull(sessionRequest.getQuery());
        List<String> genres = sessionRequest.getQuery().getGenres();
        assertNotNull(genres);
        assertTrue(genres.stream().anyMatch(genre ->
                genre.equalsIgnoreCase("science fiction")
                        || genre.equalsIgnoreCase("sci-fi")
        ));

    }

    private void printAgentResponse(SessionRequest sessionRequest) {
        System.out.println("\n=== Agent response ===");
        System.out.println("rawText: " + sessionRequest.getRawText());
        System.out.println("intent: " + sessionRequest.getIntent());
        System.out.println("personalised: " + sessionRequest.isPersonalised());

        if (sessionRequest.getQuery() != null) {
            System.out.println("query.topics: " + sessionRequest.getQuery().getTopics());
            System.out.println("query.genres: " + sessionRequest.getQuery().getGenres());
            System.out.println("query.authors: " + sessionRequest.getQuery().getAuthors());
            System.out.println("query.keywords: " + sessionRequest.getQuery().getKeywords());
        } else {
            System.out.println("query: null");
        }

        if (sessionRequest.getPreferences() != null) {
            System.out.println("preferences.include: " + sessionRequest.getPreferences().getInclude());
            System.out.println("preferences.exclude: " + sessionRequest.getPreferences().getExclude());
        } else {
            System.out.println("preferences: null");
        }

        if (sessionRequest.getConstraints() != null) {
            System.out.println("constraints.count: " + sessionRequest.getConstraints().getCount());
            System.out.println("constraints.duration: " + sessionRequest.getConstraints().getDuration());
            System.out.println("constraints.language: " + sessionRequest.getConstraints().getLanguage());
        } else {
            System.out.println("constraints: null");
        }

        if (sessionRequest.getFeedback() != null) {
            System.out.println("feedback.type: " + sessionRequest.getFeedback().getType());
            System.out.println("feedback.reason: " + sessionRequest.getFeedback().getReason());
        } else {
            System.out.println("feedback: null");
        }

        System.out.println("requestId: " + sessionRequest.getRequestId());
        System.out.println("createdAt: " + sessionRequest.getCreatedAt());
        System.out.println("recommendations: " + sessionRequest.getRecommendations());
        System.out.println("======================\n");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AiInputParser.class)
    @ImportAutoConfiguration({
            ToolCallingAutoConfiguration.class,
            OpenAiChatAutoConfiguration.class,
            ChatClientAutoConfiguration.class
    })
    static class TestConfiguration {
    }
}
