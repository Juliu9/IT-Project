package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.application.recommendation.SimilarBookResolver;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import java.util.Set;

/** One family of session actions. Implementations mutate only the supplied transaction state. */
public interface ConversationHandler {
  /** Returns the conversation actions handled by this handler. */
  Set<ConversationAction> intents();

  /** Resolves this action family against the supplied mutable session state. */
  ConversationDraft resolve(Session session, ConversationDraft input);

  /** Applies completion changes after validation and any required recommendation retrieval. */
  default void finish(Session session, ConversationDraft result, SessionRequest original) {}

  /** Reports whether the resolved action requires a recommendation page. */
  default boolean needsRecommendations(ConversationDraft result) {
    return false;
  }

  /** Reports whether the incoming action makes the cached recommendation queue obsolete. */
  default boolean invalidatesBatch(SessionRequest original) {
    return false;
  }

  /** Reports whether this handler retains reusable search context after completion. */
  default boolean retainsHistory() {
    return false;
  }

  /** Returns the telemetry label describing how this action obtained its results. */
  default String retrievalMode(ConversationDraft result) {
    return null;
  }

  /** Reports whether the completed result should replace the repeatable last outcome. */
  default boolean remembersTurn(ConversationDraft result) {
    return true;
  }

  enum QuestionPolicy {
    REPLACE,
    PRESERVE,
    ATTACH
  }

  /** Supplies recognition guidance alongside this handler's executable behavior. */
  default String instructions() {
    return "";
  }

  /** Recognizes a complete normalized command, or defers to shared grammar and AI. */
  default UserIntent recognize(String command) {
    if (!commands().contains(command)) {
      return null;
    }
    var parsed = new UserIntent();
    parsed.setIntent(intents().iterator().next());
    return parsed;
  }

  /** Applies this intent's parsed-input rules before shared validation and normalization. */
  default void prepare(
      String rawText,
      com.gen3.recommenderagent.inputparser.UserIntent parsed,
      SessionRequest request) {}

  /** Applies this intent's field precedence after shared input validation. */
  default void normalizeFields(SessionRequest request) {}

  /** Keeps this intent when its explicit recommendation quantity is invalid. */
  default boolean retainsInvalidCountIntent() {
    return false;
  }

  /**
   * Claims invalid quantity wording when shared count validation must correct the parsed intent.
   */
  default boolean recognizesInvalidCount(String rawText, String modelText) {
    return false;
  }

  /** Owns the count-error wording instead of receiving the common count notice. */
  default boolean suppressesCountNotice() {
    return false;
  }

  /** Decides whether missing saved tastes require a question before semantic retrieval. */
  default boolean requiresSavedPreferences(ConversationDraft request, Session session) {
    return false;
  }

  /** Decides whether the computed semantic evidence is sufficient for this intent. */
  default boolean requiresSemanticPreferences(
      ConversationDraft request, com.gen3.recommenderagent.embedding.SemanticQueryVectors vectors) {
    return false;
  }

  /** Claims an exhausted-search response even when another action is replaying that state. */
  default String exhaustedResponse(ConversationTurn turn) {
    return null;
  }

  /** Identifies this intent's empty exceptional result for response telemetry and count notices. */
  default boolean emptyOutcome(ConversationOutcome outcome) {
    return false;
  }

  /** Supplies an intent-specific fallback after shared personalisation handling. */
  default String fallbackResponse(ConversationTurn turn, int labelWords) {
    return null;
  }

  /** Returns a handled failure outcome, or null to propagate the original exception. */
  default ConversationTurn recoverFailure(
      Session session,
      ConversationAction action,
      com.gen3.recommenderagent.domain.session.PendingQuestion pending,
      RuntimeException exception) {
    return null;
  }

  /** Lets the pending question owner repair fields before classifying the reply. */
  default void preparePendingReply(Session session, ConversationDraft input, String reply) {}

  /** Reports whether this intent recognizes a reply to an unfinished action. */
  default boolean isContextualReply(Session session, ConversationDraft input, String reply) {
    return false;
  }

  /** Interprets an owned reply or a global interruption, or returns null to continue dispatch. */
  default ConversationDraft resolvePendingReply(
      Session session, ConversationDraft input, String reply) {
    return null;
  }

  /** Gives cancellation precedence over abandoning the current question. */
  default boolean handlesInterruptions() {
    return false;
  }

  /** Keeps an unanswered question when another action leaves it untouched. */
  default boolean keepsUnansweredQuestion(ConversationQuestion question) {
    return false;
  }

  /** Attaches the context needed to repeat this intent's pending question. */
  default void attachQuestionContext(Session session, ConversationDraft request) {}

  /** Chooses the next question after this intent completes. */
  default void finishQuestion(ConversationDraft request) {
    if (request.getOutcome() != null) {
      request.setPendingQuestion(null);
    }
  }

  /** Stores the context required to resume this intent's question. */
  default void retainQuestion(Session session, ConversationDraft request) {}

  default boolean explainsBooks() {
    return false;
  }

  default boolean includesPendingQuestion(ConversationTurn turn) {
    return questionPolicy() == QuestionPolicy.ATTACH;
  }

  default String withQuestion(String response, String question) {
    return response + " " + question;
  }

  default Set<String> commands() {
    return Set.of();
  }

  default QuestionPolicy questionPolicy() {
    return QuestionPolicy.REPLACE;
  }

  default boolean bypassesClarification() {
    return questionPolicy() != QuestionPolicy.REPLACE;
  }

  default boolean abandonsPendingQuestion(SessionRequest request) {
    return questionPolicy() == QuestionPolicy.REPLACE;
  }

  default boolean acceptsSearchSemantics() {
    return false;
  }

  default boolean retainsExplanationSelection() {
    return false;
  }

  /** Uses active search criteria as the starting point for this intent. */
  default boolean inheritsSearch() {
    return false;
  }

  /** Reuses the active preferences and similarity vector when fetching another page. */
  default boolean reusesSearchSnapshot() {
    return false;
  }

  default boolean isSearchAction() {
    return false;
  }

  default boolean ownsResponseBudget() {
    return false;
  }

  default boolean narratesBooks() {
    return true;
  }

  /** Validates the resolved action using shared services before any candidates are consumed. */
  default void validate(
      Session session,
      ConversationDraft result,
      SessionRequest original,
      ExactFilterPolicy filters,
      SimilarBookResolver similarBooks,
      com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers) {}

  /**
   * Returns fixed wording, or null when shared question, outcome, or narration handling applies.
   */
  default String response(ConversationTurn turn) {
    return null;
  }

  /** Returns intent-specific wording when retrieval or replay produces no books. */
  default String emptyResponse(ConversationTurn turn) {
    return null;
  }

  default String outcomeResponse(ConversationTurn turn) {
    return null;
  }

  /** Declares exceptional results owned and interpreted by this intent. */
  default Set<ConversationOutcome> outcomes() {
    return Set.of();
  }

  /** Lists the questions whose wording and activation belong to this intent. */
  default Set<ConversationQuestion> questions() {
    return Set.of();
  }

  /** Reports whether this intent's question should replace the normal response. */
  default boolean questionActive(ConversationQuestion question, ConversationTurn turn) {
    return turn.isSearchClarificationRequired();
  }

  default String questionResponse(
      ConversationQuestion question, ConversationTurn turn, int labelWords) {
    return null;
  }
}
