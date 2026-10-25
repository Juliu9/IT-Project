package com.gen3.recommenderagent.domain.conversation;

import static com.gen3.recommenderagent.domain.conversation.ConversationHistory.latestSearch;
import static com.gen3.recommenderagent.domain.conversation.SearchCriteriaMerger.merge;

import com.gen3.catalogue.model.AudiobookTitle;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDecision;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;

/** Resolves explanation targets exclusively against the displayed recommendation page. */
public final class ExplanationConversationHandler implements ConversationHandler {
  public java.util.Set<Intent> intents() {
    return java.util.Set.of(Intent.EXPLAIN_RECOMMENDATION);
  }

  public ConversationDraft resolve(Session session, ConversationDraft input) {
    if (session == null) {
      input.setDecision(ConversationDecision.NOTHING_TO_EXPLAIN);
      return input;
    }
    resolveExplanation(input, session);
    return input;
  }

  private void resolveExplanation(ConversationDraft target, Session session) {
    target.setRecommendations(List.of());
    target.setPreferenceClarificationRequired(false);
    target.setMustInclude(null);
    target.setMustNotInclude(null);
    target.setBookCount(null);
    ConversationDraft previous = latestSearch(session);
    if (previous == null) {
      target.setDecision(ConversationDecision.NOTHING_TO_EXPLAIN);
      return;
    }
    merge(previous, target);
    List<Recommendation> page =
        previous.getRecommendations() == null
            ? List.of()
            : previous.getRecommendations().stream()
                .sorted(java.util.Comparator.comparing(Recommendation::getRank))
                .toList();
    target.setRecommendations(page);
    if (page.isEmpty()) {
      target.setDecision(ConversationDecision.EMPTY_EXPLANATION_PAGE);
      return;
    }
    Integer rank = target.getReferenceRank();
    String title = AudiobookTitle.normalize(target.getReferenceTitle());
    List<Recommendation> matches = page;
    if (!title.isEmpty()) {
      matches = matchingTitles(page, title);
    }
    // Resolve a title independently before applying rank; an ambiguous title needs clarification.
    if ((title.isEmpty()
            && target.getReferenceTitle() != null
            && !target.getReferenceTitle().isBlank())
        || (!title.isEmpty() && matches.size() != 1)
        || (rank != null && matches.stream().noneMatch(book -> rank.equals(book.getRank())))) {
      target.setDecision(ConversationDecision.EXPLANATION_TARGET_CLARIFICATION);
      return;
    }
    if (rank != null) {
      matches = matches.stream().filter(book -> rank.equals(book.getRank())).toList();
    }
    target.setRecommendations(matches);
    target.setDecision(ConversationDecision.EXPLANATION);
  }

  static boolean matchesDisplayedTitle(Session session, String title) {
    var previous = latestSearch(session);
    String normalized = AudiobookTitle.normalize(title);
    return previous != null
        && previous.getRecommendations() != null
        && !normalized.isEmpty()
        && !matchingTitles(previous.getRecommendations(), normalized).isEmpty();
  }

  private static List<Recommendation> matchingTitles(List<Recommendation> page, String title) {
    var exact =
        page.stream()
            .filter(book -> AudiobookTitle.normalize(book.getTitle()).equals(title))
            .toList();
    return exact.isEmpty()
        ? page.stream()
            .filter(book -> AudiobookTitle.normalize(book.getTitle()).contains(title))
            .toList()
        : exact;
  }

  public String retrievalMode(ConversationDraft result) {
    return "SESSION_EXPLANATION";
  }
}
