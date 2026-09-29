package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

/** Leaves requests unchanged for informational or currently unsupported intents. */
@Component
public class NoOpAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, Session session) {
    return request;
  }
}
