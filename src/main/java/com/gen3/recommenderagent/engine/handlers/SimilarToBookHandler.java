package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimilarToBookHandler implements IntentHandler {

    @Override
    public Intent supportedIntent() {
        return Intent.SIMILAR_TO_BOOK;
    }

    @Override
    public List<Recommendation> handle(SessionRequest request, Session session) {

        // TODO:
        // Extract the referenced book.
        // Build a MoreLikeThis Solr query.
        // Retrieve candidates.
        // Rank candidates.

        return List.of();
    }
}