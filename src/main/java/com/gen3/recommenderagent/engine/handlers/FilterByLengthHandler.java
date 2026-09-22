package com.gen3.recommenderagent.engine.handlers;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FilterByLengthHandler implements IntentHandler {

  @Override
  public Intent supportedIntent() {
    return Intent.FILTER_BY_LENGTH;
  }

  @Override
  public List<Recommendation> handle(SessionRequest request, Session session) {

    // TODO:
    // Filter candidates by audiobook duration.

    return List.of();
  }
}
