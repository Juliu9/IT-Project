package com.gen3.recommenderagent.storage.sessionrepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationWorkflow;
import com.gen3.recommenderagent.storage.sessionrepository.redis.RedisConfig;
import com.gen3.recommenderagent.storage.sessionrepository.redis.RedisSessionRepository;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class SessionStoreConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              RedisConfig.class, RedisSessionRepository.class, SessionTransitionService.class)
          .withBean(RecommendationWorkflow.class, () -> mock(RecommendationWorkflow.class));

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
              Function<Session, SessionRequest> change = invocation.getArgument(2);
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
              SessionRequest request = new SessionRequest();
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
