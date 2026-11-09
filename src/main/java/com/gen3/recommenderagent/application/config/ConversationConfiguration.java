package com.gen3.recommenderagent.application.config;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.HandlerRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Discovers intent handlers; adding a component requires no registration-list change. */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.gen3.recommenderagent.application.conversation.intent")
public class ConversationConfiguration {
  @Bean
  HandlerRegistry handlerRegistry(List<ConversationHandler> handlers) {
    return new HandlerRegistry(handlers);
  }
}
