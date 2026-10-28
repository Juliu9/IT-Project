package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.MAX_SHOWN_BOOKS;
import static com.gen3.recommenderagent.domain.recommendation.RecommendationPolicy.RECOMMENDATION_BATCH_SIZE;

import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import java.util.ArrayList;
import java.util.List;

/** The one search that can be refined, paged, or used as displayed-page context. */
public class ActiveSearch {
  private ConversationTurn search;
  private List<String> shownBookIds = new ArrayList<>();
  private List<Recommendation> pendingRecommendations = new ArrayList<>();

  public ConversationTurn getSearch() {
    return search;
  }

  public void setSearch(ConversationTurn value) {
    search = value;
  }

  public List<String> getShownBookIds() {
    return shownBookIds;
  }

  public void setShownBookIds(List<String> value) {
    shownBookIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
  }

  public List<Recommendation> getPendingRecommendations() {
    return pendingRecommendations;
  }

  public void setPendingRecommendations(List<Recommendation> value) {
    pendingRecommendations =
        value == null
            ? new ArrayList<>()
            : new ArrayList<>(value.stream().limit(RECOMMENDATION_BATCH_SIZE).toList());
  }

  public void addShownBookIds(List<String> bookIds) {
    if (bookIds == null) {
      return;
    }
    bookIds.stream()
        .filter(bookId -> bookId != null && !bookId.isBlank())
        .filter(bookId -> !shownBookIds.contains(bookId))
        .forEach(
            bookId -> {
              if (shownBookIds.size() == MAX_SHOWN_BOOKS) {
                shownBookIds.removeFirst();
              }
              shownBookIds.add(bookId);
            });
  }
}
