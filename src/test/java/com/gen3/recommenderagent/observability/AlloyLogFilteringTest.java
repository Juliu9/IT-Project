package com.gen3.recommenderagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AlloyLogFilteringTest {
  @TempDir Path directory;

  @Test
  void exportsOnlyValidRecommendationSummaries() throws Exception {
    String config = Files.readString(Path.of("observability/alloy/config.alloy"));
    // Run the production pipeline with a local echo destination; no cloud connection is made.
    config =
        config
                .substring(0, config.indexOf("loki.write \"grafana_cloud\""))
                .replace("loki.write.grafana_cloud.receiver", "loki.echo.audit.receiver")
                .replace("/var/log/recommender-agent/recommender-agent.log", "/audit/input.log")
            + "\nloki.echo \"audit\" {}\n";
    Path configuration = directory.resolve("config.alloy");
    Files.writeString(configuration, config);
    Path input = directory.resolve("input.log");
    Files.writeString(
        input,
        summary("allowed-first")
            + "\n"
            + "INFO ordinary-private-marker\n"
            + "WARN recommendation.completed mentioned-private-marker\n"
            + "INFO {\"event\":\"recommendation.completed\",\"requestId\":\"malformed-private-marker\",}\n"
            + "INFO {\"event\":\"recommendation.completed\",\"requestId\":\"wrong-shape-private-marker\"}\n"
            + summary("extra-field-private-marker")
                .replace("\"outcome\":", "\"rawText\":\"private-marker\",\"outcome\":")
            + "\n"
            + summary("allowed-failure").replace("SUCCESS", "FAILURE")
            + "\n"
            + summary("allowed-last")
            + "\n");
    try (GenericContainer<?> alloy =
        new GenericContainer<>("grafana/alloy:v1.20.0")
            .withFileSystemBind(directory.toAbsolutePath().toString(), "/audit", BindMode.READ_ONLY)
            .withCommand("run", "--storage.path=/tmp/alloy-data", "/audit/config.alloy")
            .waitingFor(
                Wait.forLogMessage(".*allowed-last.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(45)))) {
      alloy.start();
      assertThat(alloy.getLogs())
          .contains("allowed-first", "allowed-failure", "allowed-last")
          .doesNotContain("private-marker");
    }
  }

  private String summary(String id) {
    return "INFO {\"event\":\"recommendation.completed\",\"requestId\":\""
        + id
        + "\",\"outcome\":\"SUCCESS\",\"durationMs\":10,\"intent\":\"RECOMMENDATION\","
        + "\"responseMode\":\"AI\",\"stages\":{},\"retrieval\":{},\"ai\":{},\"recommendationCount\":1}";
  }
}
