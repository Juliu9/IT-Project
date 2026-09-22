package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LikeRecommendationHandler implements IntentHandler {

    @Override
    public Intent supportedIntent() {
        return Intent.LIKE_RECOMMENDATION;
    }

    @Override
    public List<Recommendation> handle(SessionRequest request, Session session) {

        // TODO:
        // Record liked recommendation.
        // Potentially update user profile.

        return List.of();
    }
}