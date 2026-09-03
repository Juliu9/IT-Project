package com.gen3.recommenderagent.response;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Constraints;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Recommendations;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(ResponseGeneratorTest.TestConfiguration.class)
class ResponseGeneratorTest {

    @Autowired
    private ResponseGenerator generator;

    @DynamicPropertySource
    static void configureOpenAi(DynamicPropertyRegistry registry) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }

        registry.add("spring.ai.openai.api-key", () -> apiKey);
        registry.add("spring.ai.openai.chat.model", () -> "gpt-4o-mini");
    }

    @Test
    void shouldBuildAHumanReadableRecommendationMessage() {
        SessionRequest request = new SessionRequest();
        request.setIntent(Intent.NEW_RECOMMENDATION);
        request.setRawText("Recommend me an action audiobook");

        Query query = new Query();
        query.setGenres(List.of("action", "sci-fi"));
        request.setQuery(query);

        Constraints constraints = new Constraints();
        constraints.setCount(3);
        request.setConstraints(constraints);

        Recommendations recommendations = new Recommendations();
        recommendations.setRecommendations(List.of(
                new Recommendation("book-101", 1, 0.91),
                new Recommendation("book-202", 2, 0.84)));

        long start = System.nanoTime();

        ResponseEntity<ChatResponse, String> aiResponse =
                generator.generateWithMetadata(recommendations, request);
        String response = aiResponse.entity();

        assertNotNull(aiResponse.response(), "Expected a response from the AI model");
        Usage chatUsage = aiResponse.response().getMetadata().getUsage();
        assertNotNull(chatUsage, "Expected token usage metadata from the AI model");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("\n=== Agent Metrics ===");
        System.out.println("Agent request time: " + elapsedMs + " ms");
        System.out.println("Prompt tokens: " + chatUsage.getPromptTokens());
        System.out.println("Completion tokens: " + chatUsage.getCompletionTokens());
        System.out.println("Total tokens: " + chatUsage.getTotalTokens());
        System.out.println("\n=== Agent Response ===");
        System.out.println(response);

        assertNotNull(response);
        assertTrue(response.toLowerCase().contains("action"));
        assertTrue(response.toLowerCase().contains("book-101"));
        assertTrue(response.toLowerCase().contains("recommend"));
    }

    @Test
    void shouldRejectRequestsForMoreThanFiveRecommendations() {
        SessionRequest request = new SessionRequest();
        request.setIntent(Intent.NEW_RECOMMENDATION);
        request.setRawText("Give me 10 recommendations");

        Constraints constraints = new Constraints();
        constraints.setCount(10);
        request.setConstraints(constraints);

        Recommendations recommendations = new Recommendations();
        recommendations.setRecommendations(List.of(
                new Recommendation("book-101", 1, 0.91),
                new Recommendation("book-202", 2, 0.84),
                new Recommendation("book-303", 3, 0.80),
                new Recommendation("book-404", 4, 0.76),
                new Recommendation("book-505", 5, 0.72),
                new Recommendation("book-606", 6, 0.68)));

        String response = generator.generate(recommendations, request);

        assertNotNull(response);
        assertTrue(response.toLowerCase().contains("maximum of 5"));
        assertTrue(response.toLowerCase().contains("recommendations"));
    }

    @Test
    void shouldReturnFriendlyFallbackWhenNoDataIsAvailable() {
        String response = generator.generate(null, null);

        assertNotNull(response);
        assertTrue(response.toLowerCase().contains("sorry"));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ResponseGenerator.class)
    @ImportAutoConfiguration({
            ToolCallingAutoConfiguration.class,
            OpenAiChatAutoConfiguration.class,
            ChatClientAutoConfiguration.class
    })
    static class TestConfiguration {
    }
}
