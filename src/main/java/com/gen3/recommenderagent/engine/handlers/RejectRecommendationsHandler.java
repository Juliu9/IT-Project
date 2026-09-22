package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RejectRecommendationsHandler implements IntentHandler {

  @Override
  public Intent supportedIntent() {
    return Intent.REJECT_RECOMMENDATIONS;
  }

  @Override
  public List<Recommendation> handle(SessionRequest request, Session session) {

    // TODO:
    // Record rejected recommendations.
    // Generate a new candidate set.

    return List.of();
  }
}
