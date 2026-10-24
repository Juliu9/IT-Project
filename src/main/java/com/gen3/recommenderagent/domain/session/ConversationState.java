package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.domain.Intent;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Dispatches conversation policy; persistence and retrieval are owned by the application layer. */
public final class ConversationState {
  private final Map<Intent, ConversationHandler> handlers = new EnumMap<>(Intent.class);
  private final ClarificationResolver clarification = new ClarificationResolver();

  public ConversationState() {
    this(
        List.of(
            new SearchContextResolver(),
            new PreferenceConversationHandler(),
            new ExplanationConversationHandler(),
            new ReplayConversationHandler(),
            new SessionActionHandler()));
  }

  public ConversationState(Collection<ConversationHandler> handlers) {
    for (var handler : handlers) {
      for (var intent : handler.intents()) {
        if (this.handlers.putIfAbsent(intent, handler) != null) {
          throw new IllegalArgumentException("Duplicate conversation handler: " + intent);
        }
      }
    }
    var missing = EnumSet.allOf(Intent.class);
    missing.removeAll(this.handlers.keySet());
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Missing conversation handlers: " + missing);
    }
  }

  public ConversationDraft begin(Session session, SessionRequest original) {
    return begin(session, original, request -> {});
  }

  public ConversationDraft begin(
      Session session, SessionRequest original, Consumer<ConversationDraft> validate) {
    var request = resolve(session, original);
    validate.accept(request);
    if (!request.isSearchClarificationRequired() && handler(request).invalidatesBatch(original)) {
      session.setPendingRecommendations(List.of());
    }
    return request;
  }

  ConversationDraft resolve(Session session, SessionRequest original) {
    var input = clarification.resolve(session, original);
    return handler(input).resolve(session, input);
  }

  private ConversationHandler handler(ConversationDraft request) {
    return handlers.get(request.getIntent());
  }

  public String retrievalMode(ConversationDraft request) {
    return handler(request).retrievalMode(request);
  }

  public boolean needsRecommendations(ConversationDraft request) {
    return handler(request).needsRecommendations(request);
  }

  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    var handler = handler(request);
    handler.finish(session, request, original);
    ConversationHistory.complete(request);
    if (request.getDecision() == null) {
      request.setDecision(decisionFor(request.getRecommendations(), request));
    }
    if (handler.retainsHistory() && !request.isSearchClarificationRequired()) {
      ConversationHistory.retainRequest(session, ConversationHistory.historyEntry(request));
    }
    if (handler.remembersTurn(request)) {
      ConversationHistory.remember(session, request);
    }
  }

  public ConversationDraft latestSearch(Session session) {
    return ConversationHistory.latestSearch(session);
  }

  public static SessionRequest normalizeInput(SessionRequest request) {
    return ConversationRules.normalizeInput(request);
  }

  public static ConversationDecision decisionFor(
      List<Recommendation> recommendations, ConversationDraft request) {
    return ConversationRules.decisionFor(recommendations, request);
  }

  static void retainRequest(Session session, ConversationTurn request) {
    ConversationHistory.retainRequest(session, request);
  }
}
