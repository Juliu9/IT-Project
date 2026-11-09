package com.gen3.recommenderagent.testsupport;

import com.gen3.recommenderagent.application.config.ConversationConfiguration;
import com.gen3.recommenderagent.application.conversation.ConversationEngine;
import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.HandlerRegistry;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.util.Collection;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Explicit standalone fixtures using the same handler discovery as application wiring. */
public final class TestHandlers {
  private TestHandlers() {}

  private static final HandlerRegistry REGISTRY = discover();
  public static final BeanOutputConverter<UserIntent> CONVERTER =
      AiParser.requestConverter(REGISTRY);

  private static HandlerRegistry discover() {
    try (var context = new AnnotationConfigApplicationContext(ConversationConfiguration.class)) {
      return context.getBean(HandlerRegistry.class);
    }
  }

  public static HandlerRegistry registry() {
    return REGISTRY;
  }

  public static AiParser ai(ChatClient.Builder builder) {
    return ai(builder == null ? null : builder.build(), AiOperationObserver.noop());
  }

  public static AiParser ai(ChatClient client, AiOperationObserver observer) {
    return new AiParser(client, observer, REGISTRY);
  }

  public static ConversationEngine engine(Collection<ConversationHandler> handlers) {
    return engine(new HandlerRegistry(handlers));
  }

  public static ConversationEngine engine(HandlerRegistry handlers) {
    return new ConversationEngine(
        handlers,
        org.mockito.Mockito.mock(
            com.gen3.recommenderagent.application.recommendation.RecommendationPager.class),
        org.mockito.Mockito.mock(com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy.class),
        org.mockito.Mockito.mock(
            com.gen3.recommenderagent.application.recommendation.SimilarBookResolver.class));
  }

  public static void retainRequest(
      com.gen3.recommenderagent.domain.session.Session session,
      com.gen3.recommenderagent.domain.conversation.ConversationTurn turn) {
    com.gen3.recommenderagent.application.conversation.ConversationHistory.retainRequest(
        session, turn, registry().handler(turn.getIntent()).isSearchAction());
  }

  public static com.gen3.recommenderagent.inputparser.SessionContext parserContext(
      com.gen3.recommenderagent.domain.session.Session session) {
    var sessions =
        org.mockito.Mockito.mock(com.gen3.recommenderagent.storage.session.SessionRepository.class);
    org.mockito.Mockito.when(sessions.snapshot("session", "user")).thenReturn(session);
    return new com.gen3.recommenderagent.application.session.SessionTransitionService(
            sessions, null, null)
        .parserContext("session", "user");
  }
}
