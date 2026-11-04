package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.conversation.workflow.ConversationDispatcher;
import com.gen3.recommenderagent.application.conversation.workflow.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.workflow.ExplanationConversationHandler;
import com.gen3.recommenderagent.application.conversation.workflow.PreferenceConversationHandler;
import com.gen3.recommenderagent.application.conversation.workflow.ReplayConversationHandler;
import com.gen3.recommenderagent.application.conversation.workflow.SearchContextResolver;
import com.gen3.recommenderagent.application.conversation.workflow.SessionActionHandler;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition; the dispatcher itself does not depend on concrete handlers. */
@Configuration(proxyBeanMethods = false)
public class ConversationConfiguration {
  /** Registers the handler that resolves recommendation searches and refinements. */
  @Bean
  SearchContextResolver searchConversationHandler() {
    return new SearchContextResolver();
  }

  /** Registers the handler for explicit saved-preference edits. */
  @Bean
  PreferenceConversationHandler preferenceConversationHandler() {
    return new PreferenceConversationHandler();
  }

  /** Registers the handler for explaining displayed recommendations. */
  @Bean
  ExplanationConversationHandler explanationConversationHandler() {
    return new ExplanationConversationHandler();
  }

  /** Registers the handler for replaying retained conversation results. */
  @Bean
  ReplayConversationHandler replayConversationHandler() {
    return new ReplayConversationHandler();
  }

  /** Registers the handler for count, preference-report, and other session control actions. */
  @Bean
  SessionActionHandler sessionActionHandler() {
    return new SessionActionHandler();
  }

  /** Builds the dispatcher from all registered conversation handlers. */
  @Bean
  ConversationDispatcher conversationDispatcher(List<ConversationHandler> handlers) {
    return new ConversationDispatcher(handlers);
  }
}
