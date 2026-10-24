package com.gen3.recommenderagent.domain.session;

import static com.gen3.recommenderagent.domain.session.ConversationHistory.latestSearch;
import static com.gen3.recommenderagent.domain.session.SearchCriteriaMerger.merge;

import com.gen3.recommenderagent.domain.Intent;
import java.util.List;
import java.util.Locale;

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
    String title = normalizeTitle(target.getReferenceTitle());
    List<Recommendation> matches = page;
    if (!title.isEmpty()) {
      List<Recommendation> exact =
          page.stream().filter(book -> normalizeTitle(book.getTitle()).equals(title)).toList();
      matches =
          exact.isEmpty()
              ? page.stream()
                  .filter(book -> normalizeTitle(book.getTitle()).contains(title))
                  .toList()
              : exact;
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

  private String normalizeTitle(String title) {
    return title == null
        ? ""
        : title
            .replace("$b", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim();
  }

  public String retrievalMode(ConversationDraft result) {
    return "SESSION_EXPLANATION";
  }
}
