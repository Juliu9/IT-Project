package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/** Updates session preferences without entering the recommendation workflow. */
@Component
public class UpdatePreferencesAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, SessionContext context) {
    context.updatePreferences(request.getPreferences());
    request.setRecommendations(List.of());
    request.setResponseMessage("Your audiobook preferences have been updated.");
    return request;
  }
}
