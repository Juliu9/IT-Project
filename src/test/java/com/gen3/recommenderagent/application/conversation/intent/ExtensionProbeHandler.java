package com.gen3.recommenderagent.application.conversation.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.session.Session;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A complete test-only intent whose identifier, recognition, policy and response live in one file.
 */
@Component
@Profile("handler-extension-proof")
public final class ExtensionProbeHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("EXTENSION_PROBE");
  public static final com.gen3.recommenderagent.domain.conversation.ConversationOutcome OUTCOME =
      com.gen3.recommenderagent.domain.conversation.ConversationOutcome.of(
          "EXTENSION_PROBE_COMPLETE");
  public static final com.gen3.recommenderagent.domain.conversation.ConversationQuestion QUESTION =
      com.gen3.recommenderagent.domain.conversation.ConversationQuestion.of(
          "EXTENSION_PROBE_QUESTION",
          com.gen3.recommenderagent.domain.conversation.ConversationQuestion.Context.SEARCH,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Search::new);
  private final String responsePrefix;

  public ExtensionProbeHandler(String responsePrefix) {
    this.responsePrefix = responsePrefix;
  }

  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  public Set<String> commands() {
    return Set.of("probe this session");
  }

  public String instructions() {
    return "Probe this session without retrieving books or abandoning its question.";
  }

  public ConversationDraft resolve(Session session, ConversationDraft request) {
    return request;
  }

  public void finish(Session session, ConversationDraft result, SessionRequest original) {
    session.setBookCount(2);
    result.setBookCount(session.getBookCount());
    result.setRecommendations(List.of());
  }

  public boolean acceptsSearchSemantics() {
    return true;
  }

  public com.gen3.recommenderagent.inputparser.UserIntent recognize(String command) {
    var parsed = ConversationHandler.super.recognize(command);
    if (parsed != null) {
      var query = new com.gen3.recommenderagent.domain.search.SemanticQuery();
      query.setTopics(java.util.List.of("handler-owned criteria"));
      parsed.setPositiveSemanticQuery(query);
    }
    return parsed;
  }

  public QuestionPolicy questionPolicy() {
    return QuestionPolicy.PRESERVE;
  }

  public boolean bypassesClarification() {
    return true;
  }

  public boolean abandonsPendingQuestion(SessionRequest request) {
    return false;
  }

  public boolean narratesBooks() {
    return false;
  }

  public String response(ConversationTurn turn) {
    return turn.getOutcome() == null ? responsePrefix + turn.getBookCount() : null;
  }

  public boolean retainsExplanationSelection() {
    return true;
  }

  public boolean isContextualReply(Session session, ConversationDraft input, String reply) {
    return session.getPendingQuestion() != null && reply.equals("resume probe");
  }

  public ConversationDraft resolvePendingReply(
      Session session, ConversationDraft input, String reply) {
    if (!isContextualReply(session, input, reply)) {
      return null;
    }
    input.setIntent(ACTION);
    input.setReferenceRank(3);
    return input;
  }

  public Set<com.gen3.recommenderagent.domain.conversation.ConversationOutcome> outcomes() {
    return Set.of(OUTCOME);
  }

  public Set<com.gen3.recommenderagent.domain.conversation.ConversationQuestion> questions() {
    return Set.of(QUESTION);
  }

  public String outcomeResponse(ConversationTurn turn) {
    return turn.getOutcome() == OUTCOME ? "Probe finished." : null;
  }

  public String questionResponse(
      com.gen3.recommenderagent.domain.conversation.ConversationQuestion question,
      ConversationTurn turn,
      int labelWords) {
    return question == QUESTION ? "Resume the probe?" : null;
  }
}
