package com.gen3.recommenderagent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Recommendation;
import com.gen3.recommenderagent.inputparser.AiInputParser;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatClientConfigurationTest {
  @Test
  void parserAndGeneratorUseIndependentModelsWithoutMutatingTheSharedBuilder() {
    ChatModel model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    List<String> requestedModels = new ArrayList<>();
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              Prompt prompt = invocation.getArgument(0);
              String name = prompt.getOptions().getModel();
              requestedModels.add(name);
              String output =
                  "parser-model".equals(name)
                      ? "{\"intent\":\"RECOMMENDATION\",\"personalised\":false}"
                      : "Try this audiobook.";
              return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
            });
    ChatClient.Builder shared =
        ChatClient.builder(model).defaultOptions(ChatOptions.builder().model("provider-default"));
    new ApplicationContextRunner()
        .withUserConfiguration(
            ChatClientConfiguration.class, AiInputParser.class, AiResponseGenerator.class)
        .withBean(ChatClient.Builder.class, () -> shared)
        .withBean(AiOperationObserver.class, AiOperationObserver::noop)
        .withPropertyValues(
            "ai.input-parser.model=parser-model", "ai.response-generator.model=generator-model")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var parsed = context.getBean(AiInputParser.class).parse("Find an audiobook");
              assertThat(parsed.getIntent()).isEqualTo(Intent.RECOMMENDATION);
              Recommendation recommendation = new Recommendation();
              recommendation.setTitle("Book");
              assertThat(
                      context
                          .getBean(AiResponseGenerator.class)
                          .generate(List.of(recommendation), parsed))
                  .isEqualTo("Try this audiobook.");
              shared.build().prompt().user("default").call().chatResponse();
              assertThat(requestedModels)
                  .containsExactly("parser-model", "generator-model", "provider-default");
            });
  }

  @Test
  void aRoleCanReplaceItsProviderWithoutReplacingTheOtherRole() {
    ChatModel defaultModel = mock(ChatModel.class);
    ChatModel parserModel = mock(ChatModel.class);
    when(defaultModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(parserModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(defaultModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("default provider")))));
    when(parserModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("parser provider")))));
    ChatClient replacement = ChatClient.create(parserModel);
    new ApplicationContextRunner()
        .withUserConfiguration(ChatClientConfiguration.class)
        .withBean(ChatClient.Builder.class, () -> ChatClient.builder(defaultModel))
        .withBean("inputParserChatClient", ChatClient.class, () -> replacement)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean("inputParserChatClient", ChatClient.class))
                  .isSameAs(replacement);
              assertThat(replacement.prompt().user("parse").call().content())
                  .isEqualTo("parser provider");
              assertThat(
                      context
                          .getBean("responseGeneratorChatClient", ChatClient.class)
                          .prompt()
                          .user("respond")
                          .call()
                          .content())
                  .isEqualTo("default provider");
            });
  }
}
