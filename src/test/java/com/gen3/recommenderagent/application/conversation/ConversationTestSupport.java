package com.gen3.recommenderagent.application.conversation;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.recommendation.RecommendationPager;
import com.gen3.recommenderagent.application.recommendation.RecommendationWorkflow;
import com.gen3.recommenderagent.domain.conversation.ConversationDispatcher;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import com.gen3.recommenderagent.storage.session.SessionRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Uses production handler registration for standalone conversation tests. */
public final class ConversationTestSupport {
  private ConversationTestSupport() {}

  public static ConversationDispatcher dispatcher() {
    try (var context = new AnnotationConfigApplicationContext(ConversationConfiguration.class)) {
      return context.getBean(ConversationDispatcher.class);
    }
  }

  public static SessionTransitionService transitions(
      SessionRepository sessions, RecommendationWorkflow workflow) {
    return new SessionTransitionService(
        sessions,
        new ConversationEngine(
            dispatcher(), new RecommendationPager(workflow), new ExactFilterPolicy()));
  }
}
