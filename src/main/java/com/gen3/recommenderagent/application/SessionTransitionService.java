package com.gen3.recommenderagent.application;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.clampOrDefault;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Owns one ordered, all-or-nothing transition of a user's conversational session. */
@Service
public class SessionTransitionService {

  private static final ConversationState CONVERSATION_STATE = new ConversationState();

  private final SessionRepository sessions;
  private final RecommendationWorkflow recommendationWorkflow;
  private final AudiobookRetrievalPlanner planner;

  public SessionTransitionService(
      SessionRepository sessions, RecommendationWorkflow recommendationWorkflow) {
    this(sessions, recommendationWorkflow, new AudiobookRetrievalPlanner());
  }

  @Autowired
  public SessionTransitionService(
      SessionRepository sessions,
      RecommendationWorkflow recommendationWorkflow,
      AudiobookRetrievalPlanner planner) {
    this.planner = planner;
    this.sessions = sessions;
    this.recommendationWorkflow = recommendationWorkflow;
  }

  public SessionRequest process(String sessionId, String userId, SessionRequest request) {
    return sessions.update(sessionId, userId, session -> apply(session, request));
  }

  private SessionRequest apply(Session session, SessionRequest original) {
    SessionRequest result =
        CONVERSATION_STATE.begin(
            session, original, resolved -> validate(resolved, original, session));
    if (CONVERSATION_STATE.needsRecommendations(result)) {
      result.setRecommendations(recommendationPage(result, session));
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

  private List<Recommendation> recommendationPage(SessionRequest request, Session session) {
    int limit = clampOrDefault(request.getBookCount());
    List<Recommendation> page = takePending(session, limit);
    if (page.size() < limit) {
      // Exclude the cached tail too when refilling; all changes commit together or not at all.
      session.addRecommendationIds(recommendationIds(page));
      session.setPendingRecommendations(recommendationWorkflow.recommend(request, session));
      page.addAll(takePending(session, limit - page.size()));
    } else {
      RequestTelemetry.retrieval("SESSION_CACHE", page.size());
    }
    return IntStream.range(0, page.size())
        .mapToObj(
            index -> {
              Recommendation item = page.get(index);
              Recommendation displayed =
                  new Recommendation(
                      item.getBookId(),
                      index + 1,
                      item.getScore(),
                      item.getTitle(),
                      item.getShortDescription());
              displayed.setReadingPreferencesUsed(item.isReadingPreferencesUsed());
              return displayed;
            })
        .toList();
  }

  private List<Recommendation> takePending(Session session, int limit) {
    List<Recommendation> page = new ArrayList<>();
    Set<String> seen =
        new HashSet<>(session.getShownBooks() == null ? List.of() : session.getShownBooks());
    List<Recommendation> pending = session.getPendingRecommendations();
    while (!pending.isEmpty() && page.size() < limit) {
      Recommendation item = pending.removeFirst();
      if (item != null
          && item.getBookId() != null
          && !item.getBookId().isBlank()
          && seen.add(item.getBookId())) {
        page.add(item);
      }
    }
    return page;
  }

  private List<String> recommendationIds(List<Recommendation> recommendations) {
    return recommendations.stream()
        .filter(recommendation -> recommendation != null)
        .map(Recommendation::getBookId)
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .toList();
  }
}
