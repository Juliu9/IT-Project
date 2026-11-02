package com.gen3.recommenderagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    scanBasePackages = {"com.gen3.recommenderagent", "com.gen3.shared.audiobookcatalogue"})
@org.springframework.context.annotation.Import(
    com.gen3.shared.readhistory.postgres.ReadHistoryConfiguration.class)
public class RecommenderAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(RecommenderAgentApplication.class, args);
  }
}
