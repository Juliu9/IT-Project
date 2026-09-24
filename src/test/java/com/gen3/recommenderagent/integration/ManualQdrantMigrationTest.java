package com.gen3.recommenderagent.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Provides a Maven-test entry point for an explicitly enabled live migration.
 *
 * <p>Spring runs {@code SolrToQdrantMigrator} while creating the context. The environment guard
 * keeps normal test runs disconnected from staging and Qdrant Cloud.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RUN_QDRANT_MIGRATION", matches = "true")
class ManualQdrantMigrationTest {

  /** Passes after the enabled application runner finishes the requested migration. */
  @Test
  void migrationCompletesDuringContextStartup() {}
}
