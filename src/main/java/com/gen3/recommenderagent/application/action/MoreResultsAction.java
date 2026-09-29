package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

/** Placeholder until pagination behaviour is defined. */
@Component
public class MoreResultsAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, Session session) {
    return request;
  }
}
