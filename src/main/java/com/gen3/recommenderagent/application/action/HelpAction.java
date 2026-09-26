package com.gen3.recommenderagent.application.action;

import com.gen3.recommenderagent.application.IntentAction;
import com.gen3.recommenderagent.domain.session.SessionContext;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/** Returns product guidance without retrieving or ranking candidates. */
@Component
public class HelpAction implements IntentAction {

  private static final String HELP_MESSAGE =
      "I can recommend audiobooks, find similar titles, and filter by author, narrator, "
          + "language, or listening length. You can also update preferences or clear your history.";

  @Override
  public SessionRequest execute(SessionRequest request, SessionContext context) {
    request.setRecommendations(List.of());
    request.setResponseMessage(HELP_MESSAGE);
    return request;
  }
}
