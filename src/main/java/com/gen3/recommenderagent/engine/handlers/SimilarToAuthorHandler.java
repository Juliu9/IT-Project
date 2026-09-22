package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimilarToAuthorHandler implements IntentHandler {

    @Override
    public Intent supportedIntent() {
        return Intent.SIMILAR_TO_AUTHOR;
    }

    @Override
    public List<Recommendation> handle(SessionRequest request, Session session) {

        // TODO:
        // Extract author.
        // Build author query.
        // Retrieve candidates.
        // Rank candidates.

        return List.of();
    }
}