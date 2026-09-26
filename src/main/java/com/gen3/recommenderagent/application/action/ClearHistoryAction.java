package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/** Clears previous conversational history without retrieving candidates. */
@Component
public class ClearHistoryAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, SessionContext context) {
    context.clearHistory();
    request.setRecommendations(List.of());
    request.setResponseMessage("Your recommendation history has been cleared.");
    return request;
  }
}
