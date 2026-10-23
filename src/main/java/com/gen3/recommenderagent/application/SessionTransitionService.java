package com.gen3.recommenderagent.application;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.clampOrDefault;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** Owns one ordered, all-or-nothing transition of a user's conversational session. */
@Service
public class SessionTransitionService {

  private static final ConversationState CONVERSATION_STATE = new ConversationState();

  private final SessionRepository sessions;
  private final RecommendationWorkflow recommendationWorkflow;

  public SessionTransitionService(
      SessionRepository sessions, RecommendationWorkflow recommendationWorkflow) {
    this.sessions = sessions;
    this.recommendationWorkflow = recommendationWorkflow;
  }

  public SessionRequest process(String sessionId, String userId, SessionRequest request) {
    return sessions.update(sessionId, userId, session -> apply(session, request));
  }

  private SessionRequest apply(Session session, SessionRequest original) {
    SessionRequest result = CONVERSATION_STATE.begin(session, original);
    if (CONVERSATION_STATE.needsRecommendations(result)) {
      result.setRecommendations(recommendationPage(result, session));
    }
    CONVERSATION_STATE.finish(session, result, original);
    if (original != null && original.getIntent() == Intent.REPEAT) {
      RequestTelemetry.retrieval("SESSION_REPEAT", result.getRecommendations().size());
    }
    if (original != null && original.getIntent() == Intent.EXPLAIN_RECOMMENDATION) {
      RequestTelemetry.retrieval("SESSION_EXPLANATION", result.getRecommendations().size());
    }
    return result;
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
