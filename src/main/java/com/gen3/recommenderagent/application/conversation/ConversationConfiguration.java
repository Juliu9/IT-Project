package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.domain.conversation.ConversationDispatcher;
import com.gen3.recommenderagent.domain.conversation.ConversationHandler;
import com.gen3.recommenderagent.domain.conversation.ExplanationConversationHandler;
import com.gen3.recommenderagent.domain.conversation.PreferenceConversationHandler;
import com.gen3.recommenderagent.domain.conversation.ReplayConversationHandler;
import com.gen3.recommenderagent.domain.conversation.SearchContextResolver;
import com.gen3.recommenderagent.domain.conversation.SessionActionHandler;
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
