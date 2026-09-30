package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/** Updates search preferences that are applied to later recommendation requests in the session. */
@Component
public class UpdatePreferencesAction implements IntentAction {

  private final RequestContextMerger requestContextMerger;

  public UpdatePreferencesAction(RequestContextMerger requestContextMerger) {
    this.requestContextMerger = requestContextMerger;
  }

  @Override
  public SessionRequest execute(SessionRequest request, Session session) {
    requestContextMerger.updatePreferences(session, request);
    request.setRecommendations(List.of());
    return request;
  }
}
