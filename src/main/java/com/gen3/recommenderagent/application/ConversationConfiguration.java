package com.gen3.recommenderagent.application;

import com.gen3.recommenderagent.domain.session.ConversationHandler;
import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.ExplanationConversationHandler;
import com.gen3.recommenderagent.domain.session.PreferenceConversationHandler;
import com.gen3.recommenderagent.domain.session.ReplayConversationHandler;
import com.gen3.recommenderagent.domain.session.SearchContextResolver;
import com.gen3.recommenderagent.domain.session.SessionActionHandler;
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
  ConversationState conversationState(List<ConversationHandler> handlers) {
    return new ConversationState(handlers);
  }
}
