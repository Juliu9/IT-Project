package com.gen3.recommenderagent.storage.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.RecommendationPager;
import com.gen3.recommenderagent.application.RecommendationWorkflow;
import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.ConversationDraft;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlanner;
import com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy;
import com.gen3.recommenderagent.storage.session.redis.RedisConfig;
import com.gen3.recommenderagent.storage.session.redis.RedisSessionRepository;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class SessionStoreConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              RedisConfig.class,
              RedisSessionRepository.class,
              SessionTransitionService.class,
              com.gen3.recommenderagent.application.ConversationEngine.class,
              com.gen3.recommenderagent.application.ConversationConfiguration.class,
              AudiobookRetrievalPlanner.class,
              ExactFilterPolicy.class,
              RecommendationPager.class)
          .withBean(RecommendationWorkflow.class, () -> mock(RecommendationWorkflow.class));

  @TestConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class BootInfrastructure {}

  private ApplicationContextRunner bootRunner() {
    return runner
        .withUserConfiguration(BootInfrastructure.class)
        .withPropertyValues(
            "spring.ai.openai.api-key=unused",
            "spring.ai.model.chat=none",
            "spring.ai.model.embedding=none");
  }

  @Test
  void bootDoesNotCreateRedisInfrastructureForAReplacementStore() {
    bootRunner()
        .withPropertyValues("session.store=other")
        .withBean(SessionRepository.class, () -> mock(SessionRepository.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(RedisConnectionFactory.class)
                    .doesNotHaveBean(ReactiveRedisConnectionFactory.class)
                    .doesNotHaveBean(RedisTemplate.class)
                    .doesNotHaveBean(StringRedisTemplate.class)
                    .doesNotHaveBean("redisHealthContributor")
                    .doesNotHaveBean("redisReactiveHealthContributor"));
  }

  @Test
  void bootStillCreatesTheDefaultRedisAdapterAndConnection() {
    bootRunner()
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(RedisConnectionFactory.class)
                    .hasSingleBean(StringRedisTemplate.class)
                    .hasSingleBean(RedisSessionRepository.class));
  }

  @Test
  void usesRedisByDefault() {
    runner
        .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(SessionRepository.class);
              assertThat(context.getBean(SessionRepository.class))
                  .isInstanceOf(RedisSessionRepository.class);
            });
  }

  @Test
  void usesReplacementWithoutRedisConfigurationOrConnections() {
    SessionRepository replacement = mock(SessionRepository.class);
    when(replacement.update(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<Session, ConversationDraft> change = invocation.getArgument(2);
              Session session = new Session();
              session.setSessionId(invocation.getArgument(0));
              session.setUserId(invocation.getArgument(1));
              return change.apply(session);
            });
    runner
        .withPropertyValues("session.store=other")
        .withBean(SessionRepository.class, () -> replacement)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(SessionRepository.class)
                  .doesNotHaveBean(RedisSessionRepository.class)
                  .doesNotHaveBean(RedisConfig.class)
                  .doesNotHaveBean(RedisConnectionFactory.class)
                  .doesNotHaveBean(RedisTemplate.class);
              assertThat(context.getBean(SessionRepository.class)).isSameAs(replacement);
              ConversationDraft request = new ConversationDraft();
              request.setIntent(Intent.HELP);
              var result =
                  context
                      .getBean(SessionTransitionService.class)
                      .process("session", "user", request);
              assertThat(result.getIntent()).isEqualTo(Intent.HELP);
              assertThat(result.getRecommendations()).isEmpty();
            });
  }
}
