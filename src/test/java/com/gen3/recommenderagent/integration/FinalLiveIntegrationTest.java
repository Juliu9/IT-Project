package com.gen3.recommenderagent.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gen3.recommenderagent.storage.audiobook.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.qdrant.Bm25SparseTextEncoder;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantConfiguration;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantPointMapper;
import com.gen3.recommenderagent.storage.audiobook.qdrant.SparseTextEncoder;
import io.qdrant.client.QdrantClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in, read-only smoke test for the real Qdrant collection. */
@Tag("external")
@EnabledIfEnvironmentVariable(named = "QDRANT_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QDRANT_API", matches = ".+")
class FinalLiveIntegrationTest {

  private static final int CONNECTION_TIMEOUT_SECONDS = 20;

  @Test
  void shouldReadCandidatesFromConfiguredQdrantCollection() throws Exception {
    String collection = environment("QDRANT_COLLECTION", "audiobooks_hybrid");
    int grpcPort = Integer.parseInt(environment("QDRANT_GRPC_PORT", "6334"));
    int dimension = Integer.parseInt(environment("QDRANT_EMBEDDING_DIMENSION", "1536"));

    QdrantClient client =
        new QdrantConfiguration()
            .qdrantClient(
                environment("QDRANT_URL", ""), grpcPort, environment("QDRANT_API", ""));
    try {
      boolean collectionExists =
          client
              .collectionExistsAsync(collection)
              .get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(collectionExists, "Configured Qdrant collection does not exist");

      SparseTextEncoder encoder = new Bm25SparseTextEncoder();
      QdrantAudiobookRepository repository =
          new QdrantAudiobookRepository(
              client, new QdrantPointMapper(encoder), encoder, collection, dimension);

      List<AudiobookCandidate> candidates =
          repository.searchKeyword("science fiction", AudiobookFilters.empty(), 5);

      assertFalse(candidates.isEmpty(), "Qdrant returned no candidates for the smoke query");
      assertTrue(
          candidates.stream().allMatch(candidate -> candidate.audiobook() != null),
          "Qdrant returned a candidate without audiobook payload data");
      System.out.println("Qdrant live smoke test returned " + candidates.size() + " candidates");
    } finally {
      client.close();
    }
  }

  private static String environment(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
