package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UnknownHandler implements IntentHandler {

  @Override
  public Intent supportedIntent() {
    return Intent.UNKNOWN;
  }

  @Override
  public List<Recommendation> handle(SessionRequest request, Session session) {

    // TODO:
    // Return a fallback response.

    return List.of();
  }
}
