package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

/** Placeholder until session preference behaviour is defined. */
@Component
public class UpdatePreferencesAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, Session session) {
    return request;
  }
}
