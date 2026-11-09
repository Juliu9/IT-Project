package com.gen3.recommenderagent.application.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChatClientConfiguration {
  /** Builds the chat client for intent extraction with its optional model override. */
  @Bean
  @ConditionalOnMissingBean(name = "inputParserChatClient")
  public ChatClient inputParserChatClient(
      ChatClient.Builder builder, @Value("${ai.input-parser.model:}") String model) {
    return build(builder, model);
  }

  /** Builds the narration client with its optional model override. */
  @Bean
  @ConditionalOnMissingBean(name = "responseGeneratorChatClient")
  public ChatClient responseGeneratorChatClient(
      ChatClient.Builder builder, @Value("${ai.response-generator.model:}") String model) {
    return build(builder, model);
  }

  /** Builds a chat client using the selected model when a nonblank override is configured. */
  private ChatClient build(ChatClient.Builder builder, String model) {
    ChatClient.Builder independent = builder.clone();
    if (model != null && !model.isBlank()) {
      independent.defaultOptions(ChatOptions.builder().model(model));
    }
    return independent.build();
  }
}
