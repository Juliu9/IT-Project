package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

/** Placeholder until refinement behaviour is defined. */
@Component
public class RefineAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, Session session) {
    // Future implementation will combine the current refinement with the previous request.
    return request;
  }
}
