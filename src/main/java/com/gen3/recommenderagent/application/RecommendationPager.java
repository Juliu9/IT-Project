package com.gen3.recommenderagent.application;

import static com.gen3.recommenderagent.domain.RecommendationPolicy.clampOrDefault;

import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Recommendation;
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
