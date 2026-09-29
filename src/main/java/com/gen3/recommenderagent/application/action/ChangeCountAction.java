package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

/** Placeholder until count-changing behaviour is defined. */
@Component
public class ChangeCountAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, SessionContext context) {
    return request;
  }
}
