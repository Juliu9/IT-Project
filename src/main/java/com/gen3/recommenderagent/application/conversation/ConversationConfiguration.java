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
  @Bean
  SearchContextResolver searchConversationHandler() {
    return new SearchContextResolver();
  }

  @Bean
  PreferenceConversationHandler preferenceConversationHandler() {
    return new PreferenceConversationHandler();
  }

  @Bean
  ExplanationConversationHandler explanationConversationHandler() {
    return new ExplanationConversationHandler();
  }

  @Bean
  ReplayConversationHandler replayConversationHandler() {
    return new ReplayConversationHandler();
  }

  @Bean
  SessionActionHandler sessionActionHandler() {
    return new SessionActionHandler();
  }

  @Bean
  ConversationDispatcher conversationDispatcher(List<ConversationHandler> handlers) {
    return new ConversationDispatcher(handlers);
  }
}
