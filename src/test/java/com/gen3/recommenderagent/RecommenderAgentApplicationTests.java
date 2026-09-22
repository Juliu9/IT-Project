package com.gen3.recommenderagent;

import com.gen3.recommenderagent.testsupport.SolrContainerTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RecommenderAgentApplicationTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

  @Container @ServiceConnection
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Container static SolrContainer solr = SolrContainerTestSupport.newContainer();

  @DynamicPropertySource
  static void configureTestProperties(DynamicPropertyRegistry registry) {
    registry.add("solr.url", () -> SolrContainerTestSupport.baseUrl(solr));
    registry.add("solr.username", () -> "");
    registry.add("solr.password", () -> "");
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("ai.api-key", () -> "test-key");
  }

  @Test
  void contextLoads() {}
}
