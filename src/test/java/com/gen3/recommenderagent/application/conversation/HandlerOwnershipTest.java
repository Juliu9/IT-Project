package com.gen3.recommenderagent.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gen3.recommenderagent.application.config.ConversationConfiguration;
import com.gen3.recommenderagent.application.conversation.intent.ExtensionProbeHandler;
import com.gen3.recommenderagent.application.conversation.intent.RecommendationHandler;
import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.search.SearchCriteriaMerger;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.application.recommendation.SimilarBookResolver;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.AiParser;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import com.gen3.recommenderagent.response.ConversationResponseGenerator;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.jsontype.NamedType;
import tools.jackson.databind.module.SimpleModule;

class HandlerOwnershipTest {
  @Test
  void discoversAndExecutesASingleFileIntentWithoutCentralRegistrationChanges() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("handler-extension-proof");
      context.registerBean(String.class, () -> "Session probe complete: ");
      context.register(ConversationConfiguration.class);
      context.refresh();
      var registry = context.getBean(HandlerRegistry.class);
      var parser = new InputParser(new CommandParser(registry), null, registry);
      var request = parser.parse("probe this session");
      assertThat(request.getIntent()).isSameAs(ExtensionProbeHandler.ACTION);
      var session = new Session();
      session.setPendingQuestionKind(RecommendationHandler.SEARCH_CLARIFICATION);
      var pending = session.getPendingQuestion();
      var pager = mock(RecommendationPager.class);
      var filters = mock(ExactFilterPolicy.class);
      var references = mock(SimilarBookResolver.class);
      var engine = new ConversationEngine(registry, pager, filters, references);
      var result = engine.process(session, request);
      assertThat(result.getPositiveSemanticQuery().getTopics())
          .containsExactly("handler-owned criteria");
      var ai = mock(AiResponseGenerator.class);
      assertThat(
              new ConversationResponseGenerator(ai, registry)
                  .generate(result.getRecommendations(), result))
          .isEqualTo("Session probe complete: 2");
      assertThat(session.getPendingQuestion()).isSameAs(pending);
      assertThat(session.getLastOutcome().turn().getIntent())
          .isSameAs(ExtensionProbeHandler.ACTION);
      assertThat(AiParser.requestConverter(registry).getJsonSchema()).contains("EXTENSION_PROBE");
      assertThat(registry.instructions()).contains("EXTENSION_PROBE=Probe this session");
      var followUp = new com.gen3.recommenderagent.application.conversation.model.SessionRequest();
      followUp.setIntent(UnknownHandler.ACTION);
      followUp.setRawText("resume probe");
      var unrelatedReply = new ClarificationResolver(registry).resolve(session, followUp);
      assertThat(unrelatedReply.getIntent()).isSameAs(UnknownHandler.ACTION);
      assertThat(session.getPendingQuestion()).isSameAs(pending);

      session.setPendingQuestionKind(ExtensionProbeHandler.QUESTION);
      var probeQuestion = session.getPendingQuestion();
      var resumed = engine.process(session, followUp);
      assertThat(resumed.getIntent()).isSameAs(ExtensionProbeHandler.ACTION);
      assertThat(resumed.getReferenceRank()).isEqualTo(3);
      assertThat(
              SearchCriteriaMerger.copyOf(ConversationDraft.fromTurn(resumed)).getReferenceRank())
          .isEqualTo(3);
      assertThat(session.getPendingQuestion()).isSameAs(probeQuestion);
      assertThat(
              new ConversationResponseGenerator(ai, registry)
                  .generate(resumed.getRecommendations(), resumed))
          .isEqualTo("Session probe complete: 2");
      verifyNoInteractions(ai, pager, filters, references);
      var mapper = new ObjectMapper();
      assertThat(mapper.writeValueAsString(result.getIntent())).isEqualTo("\"EXTENSION_PROBE\"");
      assertThat(mapper.readValue("\"EXTENSION_PROBE\"", ConversationAction.class))
          .isSameAs(ExtensionProbeHandler.ACTION);
      assertThat(
              new ObjectMapper()
                  .readValue(mapper.writeValueAsString(session), Session.class)
                  .getLastOutcome()
                  .turn()
                  .getIntent())
          .isSameAs(ExtensionProbeHandler.ACTION);
    }
  }

  @Test
  void aSingleHandlerDeclaresSerializableOutcomesAndQuestions() {
    var definitions =
        new java.util.ArrayList<>(
            com.gen3.recommenderagent.testsupport.TestHandlers.registry().definitions());
    definitions.add(new ExtensionProbeHandler("Probe: "));
    var registry = new HandlerRegistry(definitions);
    var mapper = new ObjectMapper();
    var session = new Session();
    session.setPendingQuestionKind(ExtensionProbeHandler.QUESTION);
    var turn = new com.gen3.recommenderagent.domain.conversation.ConversationTurn();
    turn.setIntent(ExtensionProbeHandler.ACTION);
    session.setLastOutcome(
        new com.gen3.recommenderagent.domain.session.LastOutcome(
            turn, ExtensionProbeHandler.OUTCOME));
    var restored = mapper.readValue(mapper.writeValueAsString(session), Session.class);
    assertThat(restored.getPendingQuestionKind()).isSameAs(ExtensionProbeHandler.QUESTION);
    assertThat(restored.getLastOutcome().outcome()).isSameAs(ExtensionProbeHandler.OUTCOME);
    var help = new ConversationDraft();
    help.setIntent(com.gen3.recommenderagent.application.conversation.intent.HelpHandler.ACTION);
    new ClarificationResolver(registry).complete(restored, help);
    assertThat(help.getPendingQuestion()).isSameAs(ExtensionProbeHandler.QUESTION);
    assertThat(help.getReferenceRank()).isEqualTo(4);
    assertThat(registry.outcomeHandler(ExtensionProbeHandler.OUTCOME))
        .isInstanceOf(ExtensionProbeHandler.class);
    assertThat(registry.questionHandler(ExtensionProbeHandler.QUESTION))
        .isInstanceOf(ExtensionProbeHandler.class);
    turn.setPendingQuestion(ExtensionProbeHandler.QUESTION);
    turn.setSearchClarificationRequired(true);
    assertThat(
            new ConversationResponseGenerator(null, registry).generate(java.util.List.of(), turn))
        .isEqualTo("Resume the probe?");
    turn.setPendingQuestion(null);
    turn.setSearchClarificationRequired(false);
    turn.setOutcome(ExtensionProbeHandler.OUTCOME);
    assertThat(
            new ConversationResponseGenerator(null, registry).generate(java.util.List.of(), turn))
        .isEqualTo("Probe finished.");
  }

  @Test
  void restoresAnIndependentlyRegisteredPendingPayloadWithTheApplicationMapper() {
    var question =
        ConversationQuestion.of(
            "PAYLOAD_PROBE", ConversationQuestion.Context.SEARCH, ProbePayload::new);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withBean(
            JacksonModule.class,
            () -> new SimpleModule().registerSubtypes(new NamedType(ProbePayload.class, "probe")))
        .run(
            context -> {
              var mapper = context.getBean(ObjectMapper.class);
              var session = new Session();
              session.setPendingQuestionKind(question);
              ((ProbePayload) session.getPendingQuestion()).setMarker("retained");
              var json = mapper.writeValueAsString(session);
              assertThat(mapper.readTree(json).path("pendingQuestion").path("type").asString())
                  .isEqualTo("probe");
              var restored = mapper.readValue(json, Session.class);
              assertThat(restored.getPendingQuestionKind()).isSameAs(question);
              assertThat(((ProbePayload) restored.getPendingQuestion()).getMarker())
                  .isEqualTo("retained");
            });
  }

  public static final class ProbePayload extends PendingQuestion {
    private String marker;

    public String getMarker() {
      return marker;
    }

    public void setMarker(String value) {
      marker = value;
    }
  }

  @Test
  void builtInWireIdentifiersRemainCompatible() {
    var mapper = new ObjectMapper();
    for (var action :
        com.gen3.recommenderagent.testsupport.TestHandlers.registry()
            .actions()
            .toArray(ConversationAction[]::new)) {
      assertThat(mapper.writeValueAsString(action)).isEqualTo("\"" + action.name() + "\"");
      assertThat(mapper.readValue("\"" + action.name() + "\"", ConversationAction.class))
          .isSameAs(action);
    }
  }

  @Test
  void rejectsAnAmbiguousExactCommandFromANewHandler() {
    var defaults = com.gen3.recommenderagent.testsupport.TestHandlers.registry();
    var definitions =
        new java.util.ArrayList<>(
            defaults.actions().stream().map(defaults::handler).distinct().toList());
    definitions.add(
        new ConversationHandler() {
          public java.util.Set<ConversationAction> intents() {
            return java.util.Set.of(ConversationAction.of("AMBIGUOUS_PROBE"));
          }

          public java.util.Set<String> commands() {
            return java.util.Set.of("help");
          }

          public ConversationDraft resolve(Session session, ConversationDraft input) {
            return input;
          }
        });
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new HandlerRegistry(definitions))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate intent command: help");
  }
}
