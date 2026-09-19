package com.gen3.recommenderagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "solr.url=http://localhost:8983/solr",
      "solr.username=test",
      "solr.password=test",
      "ai.api-key=test",
      "spring.ai.openai.api-key=test"
    })
@Testcontainers(disabledWithoutDocker = true)
class RecommenderAgentApplicationTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

  @Container @ServiceConnection
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @Test
  void contextLoads() {}
}
