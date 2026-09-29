package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

/** Placeholder until refinement behaviour is defined. */
@Component
public class RefineAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, SessionContext context) {
      // load and use the previous' sessionRequest + somehow make whatever semantic idea the user requested more powerful
      // Prev: "I want sad", Current: "Make it sadder/less sad"
      // somehow strengthen the vector to make it 'more/less sad'
    return request;
  }
}
