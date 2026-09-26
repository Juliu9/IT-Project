package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/** Provides an explicit safe result for intents whose business operation is not implemented yet. */
@Component
public class UnsupportedIntentAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, SessionContext context) {
    request.setRecommendations(List.of());
    request.setResponseMessage(
        "I understood the request, but that action is not available yet. Try asking for new recommendations or help.");
    return request;
  }
}
