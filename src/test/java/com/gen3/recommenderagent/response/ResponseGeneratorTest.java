package com.gen3.recommenderagent.response;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Constraints;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseGeneratorTest {

    @Test
    void shouldBuildAHumanReadableRecommendationMessage() {
        AiResponseGenerator generator = new AiResponseGenerator(null);

        SessionRequest request = new SessionRequest();
        request.setIntent(Intent.NEW_RECOMMENDATION);
        request.setRawText("Recommend me an action audiobook");

        Query query = new Query();
        query.setGenres(List.of("action", "sci-fi"));
        request.setQuery(query);

        Constraints constraints = new Constraints();
        constraints.setCount(3);
        request.setConstraints(constraints);

        List<Recommendation> recommendations = List.of(
            new Recommendation("book-101", 1, 0.91, "book1"),
            new Recommendation("book-202", 2, 0.84, "book2"));

        String response = generator.generate(recommendations, request);

        assertNotNull(response);
        assertTrue(response.toLowerCase().contains("action"));
        assertTrue(response.toLowerCase().contains("book-101"));
        assertTrue(response.toLowerCase().contains("recommend"));
    }

    @Test
    void shouldRejectRequestsForMoreThanFiveRecommendations() {
        AiResponseGenerator generator = new AiResponseGenerator(null);

        SessionRequest request = new SessionRequest();
        request.setIntent(Intent.NEW_RECOMMENDATION);
        request.setRawText("Give me 10 recommendations");

        Constraints constraints = new Constraints();
        constraints.setCount(10);
        request.setConstraints(constraints);

        List<Recommendation> recommendations = List.of(
                new Recommendation("book-101", 1, 0.91, "Book1"),
                new Recommendation("book-202", 2, 0.84, "Book2"),
                new Recommendation("book-303", 3, 0.80, "Book3"),
                new Recommendation("book-404", 4, 0.76, "Book4"),
                new Recommendation("book-505", 5, 0.72, "Book5"),
                new Recommendation("book-606", 6, 0.68, "Book6"));

        String response = generator.generate(recommendations, request);

        assertNotNull(response);
        assertTrue(response.toLowerCase().contains("maximum of 5"));
        assertTrue(response.toLowerCase().contains("recommendations"));
    }

    @Test
    void shouldReturnFriendlyFallbackWhenNoDataIsAvailable() {
        AiResponseGenerator generator = new AiResponseGenerator(null);

        String response = generator.generate(null, null);

        assertNotNull(response);
        assertTrue(response.toLowerCase().contains("sorry"));
    }
}
