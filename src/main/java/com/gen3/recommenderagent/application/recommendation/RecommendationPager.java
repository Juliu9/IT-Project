package com.gen3.recommenderagent.application.recommendation;

import static com.gen3.recommenderagent.domain.recommendation.Recommendation.bookIds;
import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.clampOrDefault;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** Owns cached-page consumption, refills, shown-ID exclusion and displayed ranks. */
@Service
public class RecommendationPager {
  private final RecommendationWorkflow recommendationWorkflow;

  public RecommendationPager(RecommendationWorkflow recommendationWorkflow) {
    this.recommendationWorkflow = recommendationWorkflow;
  }

  public List<Recommendation> page(ConversationDraft request, Session session) {
    int limit = clampOrDefault(request.getBookCount());
    List<Recommendation> page = takePending(session, limit);
    if (page.size() < limit) {
      // Exclude the cached tail too when refilling; all changes commit together or not at all.
      session.getActiveSearch().addShownBookIds(bookIds(page));
      session
          .getActiveSearch()
          .setPendingRecommendations(recommendationWorkflow.recommend(request, session));
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
                      item.getId(),
                      index + 1,
                      item.getScore(),
                      item.getTitle(),
                      item.getShortDescription());
              displayed.setDurationMinutes(item.getDurationMinutes());
              displayed.setPubdate(item.getPubdate());
              displayed.setReadingPreferencesUsed(item.isReadingPreferencesUsed());
              return displayed;
            })
        .toList();
  }

  private List<Recommendation> takePending(Session session, int limit) {
    List<Recommendation> page = new ArrayList<>();
    Set<String> seen =
        new HashSet<>(
            session.getActiveSearch().getShownBookIds() == null
                ? List.of()
                : session.getActiveSearch().getShownBookIds());
    List<Recommendation> pending = session.getActiveSearch().getPendingRecommendations();
    while (!pending.isEmpty() && page.size() < limit) {
      Recommendation item = pending.removeFirst();
      if (item != null
          && item.getId() != null
          && !item.getId().isBlank()
          && seen.add(item.getId())) {
        page.add(item);
      }
    }
    return page;
  }
}
