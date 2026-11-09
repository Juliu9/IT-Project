package com.gen3.recommenderagent.storage.user.redis;

import com.gen3.recommenderagent.storage.user.port.SearchHistoryRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** Switches on Redis search history only when the feature is turned on (MAPA-30). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "search-history.enabled", havingValue = "true")
@ConditionalOnMissingBean(SearchHistoryRepository.class)
public class SearchHistoryRedisConfiguration {

  // Redis only runs when it's the session store, so search history follows that choice.
  @Bean
  @ConditionalOnProperty(name = "session.store", havingValue = "redis", matchIfMissing = true)
  SearchHistoryRepository searchHistoryRepository(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      @Value("${search-history.expire-after-days:0}") int expireAfterDays) {
    if (expireAfterDays < 0) {
      throw new IllegalArgumentException("search-history.expire-after-days must not be negative");
    }
    return new RedisSearchHistoryRepository(redis, objectMapper, Duration.ofDays(expireAfterDays));
  }
}
