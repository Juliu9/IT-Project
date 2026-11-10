package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.application.session.SessionTransitionService;
import com.gen3.recommenderagent.inputparser.CommandParser;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import com.gen3.recommenderagent.storage.session.SessionRepository;

/** Uses production handler registration for standalone conversation tests. */
public final class ConversationTestSupport {
  private ConversationTestSupport() {}

  public static ConversationEngine engine() {
    return com.gen3.recommenderagent.testsupport.TestHandlers.engine(
        com.gen3.recommenderagent.testsupport.TestHandlers.registry());
  }

  public static SessionTransitionService transitions(
      SessionRepository sessions, RecommendationWorkflow workflow) {
    return transitions(
        sessions,
        workflow,
        new com.gen3.recommenderagent.application.recommendation.ReadHistoryExclusions(
            java.util.Optional.empty(), 10000));
  }

  public static SessionTransitionService transitions(
      SessionRepository sessions,
      RecommendationWorkflow workflow,
      com.gen3.recommenderagent.application.recommendation.ReadHistoryExclusions history) {
    var engine =
        new ConversationEngine(
            com.gen3.recommenderagent.testsupport.TestHandlers.registry(),
            new RecommendationPager(workflow, history),
            new ExactFilterPolicy(),
            new com.gen3.recommenderagent.application.recommendation.SimilarBookResolver(
                org.mockito.Mockito.mock(
                    com.gen3.shared.audiobookcatalogue.port.AudiobookTitleLookup.class),
                org.mockito.Mockito.mock(
                    com.gen3.shared.audiobookcatalogue.port.AudiobookVectorRepository.class)));
    // Resolver conversion is deterministic and does not call this parser's AI client.
    var parser =
        new InputParser(
            new CommandParser(com.gen3.recommenderagent.testsupport.TestHandlers.registry()),
            com.gen3.recommenderagent.testsupport.TestHandlers.ai(
                (org.springframework.ai.chat.client.ChatClient.Builder) null),
            com.gen3.recommenderagent.testsupport.TestHandlers.registry());
    return new SessionTransitionService(sessions, engine, parser);
  }
}
