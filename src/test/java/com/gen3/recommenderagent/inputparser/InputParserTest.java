package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.junit.jupiter.api.Test;
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
    private InputParser parser;

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
                () -> "gpt-5-mini"
        );
    }

    @Test
    void shouldConvertRawTextIntoSessionRequest() {
        // Arrange
        String rawText = "Recommend three English science fiction audiobooks.";

        // Act
        SessionRequest result = parser.parse(rawText);

        printAgentResponse(result);

        // Assert
        assertNotNull(result);
        assertEquals(rawText, result.getRawText());

        assertNotNull(result.getRequestId());
        UUID requestId = UUID.fromString(result.getRequestId());
        assertEquals(7, requestId.version());
        assertEquals(2, requestId.variant());

        assertEquals(Intent.NEW_RECOMMENDATION, result.getIntent());

        assertNotNull(result.getQuery());
        List<String> genres = result.getQuery().getGenres();
        assertNotNull(genres);
        assertTrue(genres.stream().anyMatch(genre ->
                genre.equalsIgnoreCase("science fiction")
                        || genre.equalsIgnoreCase("sci-fi")
        ));

        assertNotNull(result.getConstraints());
        assertEquals(3, result.getConstraints().getCount());
        assertTrue("English".equalsIgnoreCase(
                result.getConstraints().getLanguage()
        ));
    }

    private void printAgentResponse(SessionRequest result) {
        System.out.println("\n=== Agent response ===");
        System.out.println("rawText: " + result.getRawText());
        System.out.println("intent: " + result.getIntent());
        System.out.println("personalised: " + result.isPersonalised());

        if (result.getQuery() != null) {
            System.out.println("query.topics: " + result.getQuery().getTopics());
            System.out.println("query.genres: " + result.getQuery().getGenres());
            System.out.println("query.authors: " + result.getQuery().getAuthors());
            System.out.println("query.keywords: " + result.getQuery().getKeywords());
        } else {
            System.out.println("query: null");
        }

        if (result.getPreferences() != null) {
            System.out.println("preferences.include: " + result.getPreferences().getInclude());
            System.out.println("preferences.exclude: " + result.getPreferences().getExclude());
        } else {
            System.out.println("preferences: null");
        }

        if (result.getConstraints() != null) {
            System.out.println("constraints.count: " + result.getConstraints().getCount());
            System.out.println("constraints.duration: " + result.getConstraints().getDuration());
            System.out.println("constraints.language: " + result.getConstraints().getLanguage());
        } else {
            System.out.println("constraints: null");
        }

        if (result.getFeedback() != null) {
            System.out.println("feedback.type: " + result.getFeedback().getType());
            System.out.println("feedback.reason: " + result.getFeedback().getReason());
        } else {
            System.out.println("feedback: null");
        }

        System.out.println("requestId: " + result.getRequestId());
        System.out.println("createdAt: " + result.getCreatedAt());
        System.out.println("recommendations: " + result.getRecommendations());
        System.out.println("======================\n");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(InputParser.class)
    @ImportAutoConfiguration({
            ToolCallingAutoConfiguration.class,
            OpenAiChatAutoConfiguration.class,
            ChatClientAutoConfiguration.class
    })
    static class TestConfiguration {
    }
}
