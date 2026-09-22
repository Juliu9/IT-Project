package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SimilarToNarratorHandler implements IntentHandler {

  @Override
  public Intent supportedIntent() {
    return Intent.SIMILAR_TO_NARRATOR;
  }

  @Override
  public List<Recommendation> handle(SessionRequest request, Session session) {

    // TODO:
    // Extract narrator.
    // Build narrator query.
    // Retrieve candidates.
    // Rank candidates.

    return List.of();
  }
}
