package com.gen3.recommenderagent.application.conversation.workflow;

import com.gen3.recommenderagent.application.conversation.ConversationDraft;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.search.SimilarBookReference;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.shared.audiobookcatalogue.model.AudiobookTitle;
import com.gen3.shared.audiobookcatalogue.model.PersonNameNormalizer;
import java.util.List;

/** Resolves a reply to a pending similar-book choice or replacement request. */
final class SimilarBookPendingQuestionHandler
    implements PendingQuestionHandler<PendingQuestion.SimilarBook> {

  /** {@inheritDoc} */
  @Override
  public Class<PendingQuestion.SimilarBook> questionType() {
    return PendingQuestion.SimilarBook.class;
  }

  /** {@inheritDoc} */
  @Override
  public boolean isContextualReply(Session session, ConversationDraft input, String reply) {
    return ConversationText.selectionReply(reply) != null
        || ConversationText.relativeSelectionReply(reply, 5) != null;
  }

  /** {@inheritDoc} */
  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input, String reply) {
    if (!(session.getPendingQuestion() instanceof PendingQuestion.SimilarBook pending)) {
      return input;
    }
    var choices = pending.getRequest().getSimilarBookChoices();
    if (!input.startsNewSearch() && input.getSimilarToTitle() != null) {
      var named = anotherReference(pending.getRequest(), input);
      named.setSimilarToTitle(input.getSimilarToTitle());
      named.setSimilarToAuthor(input.getSimilarToAuthor());
      named.setSimilarToClarificationRequired(false);
      return named;
    }
    String replacement = ConversationText.replacementReferenceTitle(reply);
    if (replacement != null) {
      var named = anotherReference(pending.getRequest(), input);
      named.setSimilarToTitle(replacement);
      named.setSimilarToClarificationRequired(false);
      return named;
    }
    if ((!choices.isEmpty() && ConversationText.isChoiceRejection(reply))
        || (pending.getKind() == ConversationQuestion.SIMILAR_BOOK_UNAVAILABLE
            && ConversationText.isAffirmativeReply(reply))) {
      return anotherReference(pending.getRequest(), input);
    }
    Integer selection = ConversationText.selectionReply(reply);
    if (selection == null) {
      selection = ConversationText.relativeSelectionReply(reply, choices.size());
    }
    if (selection == null
        && (input.startsNewSearch()
            || input.getSimilarToTitle() != null
            || input.isSimilarToClarificationRequired())) {
      return input;
    }
    if (selection == null
        && input.getIntent() != ConversationAction.UNKNOWN
        && input.getIntent() != ConversationAction.RECOMMENDATION
        && input.getIntent() != ConversationAction.REFINE) {
      return input;
    }
    AudiobookTitle selected = select(choices, reply, selection);
    if (selected == null) {
      return unresolved(session, pending.getRequest(), pending.getKind(), input, selection);
    }
    var resumed = ClarificationResolver.restoreSearchCriteria(pending.getRequest(), input);
    resumed.setIntent(ConversationAction.RECOMMENDATION);
    resumed.setStartsNewSearch(true);
    resumed.setSearchClarificationRequired(false);
    resumed.setOutcome(null);
    resumed.setSimilarBookChoices(List.of());
    resumed
        .getSearchContext()
        .setSimilarBook(
            new SimilarBookReference(
                selected.bookId(), selected.title(), selected.authors(), null));
    return resumed;
  }

  /** Restores the pending search with the selected reference book. */
  private AudiobookTitle select(List<AudiobookTitle> choices, String reply, Integer selection) {
    if (selection != null && selection > 0 && selection <= choices.size()) {
      return choices.get(selection - 1);
    }
    if (reply.isBlank()) {
      return null;
    }
    var matches =
        choices.stream()
            .filter(
                book ->
                    AudiobookTitle.normalize(book.title()).equals(AudiobookTitle.normalize(reply))
                        || book.authors().stream()
                            .anyMatch(
                                author ->
                                    PersonNameNormalizer.comparisonKey(author)
                                        .equals(PersonNameNormalizer.comparisonKey(reply))))
            .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  /** Keeps the reference-book question active when the reply does not identify a choice. */
  private ConversationDraft unresolved(
      Session session,
      ConversationTurn pending,
      ConversationQuestion decision,
      ConversationDraft input,
      Integer selection) {
    var choices = pending.getSimilarBookChoices();
    if (!choices.isEmpty() && selection != null) {
      var again = ClarificationResolver.restoreSearchCriteria(pending);
      again.setPendingQuestion(ConversationQuestion.SIMILAR_BOOK_SELECTION);
      again.setSearchClarificationRequired(true);
      again.setSimilarBookChoices(choices);
      again.setRequestId(input.getRequestId());
      again.setRawText(input.getRawText());
      return again;
    }
    if (choices.isEmpty()
        && decision == ConversationQuestion.SIMILAR_BOOK_NOT_FOUND
        && (input.getIntent() == ConversationAction.RECOMMENDATION
            || input.getIntent() == ConversationAction.REFINE)) {
      var tastes = SearchCriteriaMerger.copyOf(input);
      SearchCriteriaMerger.merge(ClarificationResolver.restoreSearchCriteria(pending), tastes);
      tastes.setIntent(ConversationAction.RECOMMENDATION);
      tastes.setStartsNewSearch(true);
      session.setPendingQuestion(null);
      return tastes;
    }
    return input;
  }

  /** Interprets wording that supplies a different reference title. */
  private ConversationDraft anotherReference(ConversationTurn pending, ConversationDraft input) {
    var another = ClarificationResolver.restoreSearchCriteria(pending, input);
    another.setIntent(ConversationAction.RECOMMENDATION);
    another.setStartsNewSearch(true);
    another.setSimilarToTitle(null);
    another.setSimilarToAuthor(null);
    another.setSimilarToClarificationRequired(true);
    another.getSearchContext().setSimilarBook(null);
    another.getSearchContext().setSimilarityQueryVector(null);
    another.setSimilarBookChoices(List.of());
    another.setSearchClarificationRequired(false);
    another.setOutcome(null);
    return another;
  }
}
