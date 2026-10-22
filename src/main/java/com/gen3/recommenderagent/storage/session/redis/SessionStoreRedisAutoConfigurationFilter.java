package com.gen3.recommenderagent.storage.session.redis;

import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/** Keep Boot's connection, template and repository setup behind the same adapter selection. */
public class SessionStoreRedisAutoConfigurationFilter
    implements AutoConfigurationImportFilter, EnvironmentAware {
  private static final Set<String> REDIS_CONFIGURATIONS =
      Set.of(
          DataRedisAutoConfiguration.class.getName(),
          DataRedisReactiveAutoConfiguration.class.getName(),
          DataRedisRepositoriesAutoConfiguration.class.getName());
  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
    boolean redis = "redis".equalsIgnoreCase(environment.getProperty("session.store", "redis"));
    boolean[] matches = new boolean[autoConfigurationClasses.length];
    for (int i = 0; i < autoConfigurationClasses.length; i++) {
      matches[i] =
          redis
              || autoConfigurationClasses[i] == null
              || !REDIS_CONFIGURATIONS.contains(autoConfigurationClasses[i]);
    }
    return matches;
  }
}
