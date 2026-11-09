package com.gen3.recommenderagent.storage.user.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.recommenderagent.storage.user.port.SearchHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class SearchHistoryRedisConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(SearchHistoryRedisConfiguration.class)
          .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
          .withBean(ObjectMapper.class, ObjectMapper::new);

  @Test
  void switchedOffByDefault() {
    runner.run(context -> assertThat(context).doesNotHaveBean(SearchHistoryRepository.class));
  }

  @Test
  void switchingOnCreatesTheRedisRepository() {
    runner
        .withPropertyValues("search-history.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(SearchHistoryRepository.class)
                    .getBean(SearchHistoryRepository.class)
                    .isInstanceOf(RedisSearchHistoryRepository.class));
  }

  @Test
  void staysOffWhenRedisIsNotTheSessionStore() {
    runner
        .withPropertyValues("search-history.enabled=true", "session.store=other")
        .run(context -> assertThat(context).doesNotHaveBean(SearchHistoryRepository.class));
  }

  @Test
  void clientRepositoryReplacesRedis() {
    var clientRepository = mock(SearchHistoryRepository.class);
    runner
        .withPropertyValues("search-history.enabled=true")
        .withBean(SearchHistoryRepository.class, () -> clientRepository)
        .run(
            context ->
                assertThat(context.getBean(SearchHistoryRepository.class))
                    .isSameAs(clientRepository));
  }

  @Test
  void negativeExpiryStopsStartup() {
    runner
        .withPropertyValues("search-history.enabled=true", "search-history.expire-after-days=-1")
        .run(context -> assertThat(context).hasFailed());
  }
}
