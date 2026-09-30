package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/** Updates the bounded recommendation count used by subsequent requests. */
@Component
public class ChangeCountAction implements IntentAction {

  @Override
  public SessionRequest execute(SessionRequest request, Session session) {
    if (session != null) {
      if (request.getBookCount() != null) {
        session.setBookCount(request.getBookCount());
      }
      request.setBookCount(session.getBookCount());
    }
    request.setRecommendations(List.of());
    return request;
  }
}
