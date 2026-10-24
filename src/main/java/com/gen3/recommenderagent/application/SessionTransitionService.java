package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Owns one ordered, all-or-nothing transition of a user's conversational session. */
@Service
public class SessionTransitionService {

  private static final ConversationState CONVERSATION_STATE = new ConversationState();

  private final SessionRepository sessions;
  private final RecommendationPager pager;
  private final ExactFilterPolicy planner;

  public SessionTransitionService(
      SessionRepository sessions, RecommendationWorkflow recommendationWorkflow) {
    this(sessions, new RecommendationPager(recommendationWorkflow), new ExactFilterPolicy());
  }

  @Autowired
  public SessionTransitionService(
      SessionRepository sessions, RecommendationPager pager, ExactFilterPolicy planner) {
    this.planner = planner;
    this.sessions = sessions;
    this.pager = pager;
  }

  public SessionRequest process(String sessionId, String userId, SessionRequest request) {
    return sessions.update(sessionId, userId, session -> apply(session, request));
  }

  private SessionRequest apply(Session session, SessionRequest original) {
    SessionRequest result =
        CONVERSATION_STATE.begin(
            session, original, resolved -> validate(resolved, original, session));
    if (CONVERSATION_STATE.needsRecommendations(result)) {
      result.setRecommendations(pager.page(result, session));
    }
    CONVERSATION_STATE.finish(session, result, original);
    if (original != null
        && (original.getIntent() == Intent.REPEAT
            || original.getIntent() == Intent.REPEAT_RECOMMENDATIONS)) {
      RequestTelemetry.retrieval(
          original.getIntent() == Intent.REPEAT
              ? "SESSION_REPEAT"
              : "SESSION_REPEAT_RECOMMENDATIONS",
          result.getRecommendations().size());
    }
    if (result.getIntent() == Intent.EXPLAIN_RECOMMENDATION) {
      RequestTelemetry.retrieval("SESSION_EXPLANATION", result.getRecommendations().size());
    }
    return result;
  }

  private void validate(SessionRequest resolved, SessionRequest original, Session session) {
    if (resolved.getIntent() == Intent.UPDATE_PREFERENCES) {
      if (resolved.getDecision()
          == com.gen3.recommenderagent.domain.session.ConversationDecision
              .PREFERENCE_UPDATE_CLARIFICATION) {
        return;
      }
      var proposed = resolved.getPreferenceUpdates().asRequest();
      var updates =
          original != null && original.getPreferenceUpdates() != null
              ? original.getPreferenceUpdates().asRequest()
              : original == null
                  ? new SessionRequest()
                  : SessionPreferences.fromRequest(original).asRequest();
      planner.resolveFilterCorrections(proposed, updates);
      resolved.getPreferenceUpdates().setMustInclude(proposed.getMustInclude());
      resolved.getPreferenceUpdates().setMustNotInclude(proposed.getMustNotInclude());
      return;
    }
    var previous =
        original != null
                && (original.getIntent() == Intent.REFINE
                    || original.getIntent() == Intent.MORE_RESULTS)
            ? CONVERSATION_STATE.latestSearch(session)
            : null;
    if (previous != null
        && original.getIntent() == Intent.REFINE
        && previous.getSearchCriteria() != null) {
      previous = previous.getSearchCriteria().asRequest();
    }
    planner.resolveFilterCorrections(resolved, original, previous);
    if (resolved.getSearchCriteria() != null
        && (resolved.getIntent() == Intent.RECOMMENDATION
            || resolved.getIntent() == Intent.REFINE
            || resolved.getIntent() == Intent.MORE_RESULTS)) {
      var criteria = resolved.getSearchCriteria().asRequest();
      planner.resolveFilterCorrections(criteria, original, previous);
      resolved.setSearchCriteria(SessionPreferences.fromRequest(criteria));
    }
  }
}
