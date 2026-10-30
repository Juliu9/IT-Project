package com.gen3.shortdescriptionjob.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookEmbedding;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.qdrant.Bm25SparseTextEncoder;
import com.gen3.catalogue.qdrant.QdrantAudiobookRepository;
import com.gen3.catalogue.qdrant.QdrantPointMapper;
import com.gen3.shortdescriptionjob.ShortDescriptionEnricher;
import com.gen3.shortdescriptionjob.ShortDescriptionMode;
import com.gen3.shortdescriptionjob.provider.ShortDescriptionGenerator;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QdrantShortDescriptionIntegrationTest {
  @Container
  static final GenericContainer<?> SERVER =
      new GenericContainer<>("qdrant/qdrant:v1.19.1").withExposedPorts(6334);

  @Test
  void missingOnlyFillsOnceAndRebuildAllReplacesExistingSummariesWithoutChangingVectors()
      throws Exception {
    try (var client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(SERVER.getHost(), SERVER.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var mapper = new QdrantPointMapper(encoder);
      var catalogue = new QdrantAudiobookRepository(client, mapper, encoder, "summary-modes", 2);
      catalogue.importStoredBooks(
          List.of(
              new AudiobookEmbedding(
                  new AudiobookRecord("missing", "s", "Title", List.of(), "Description"),
                  new float[] {1, 0}),
              new AudiobookEmbedding(
                  new AudiobookRecord(
                      "present",
                      "s",
                      "Title",
                      List.of(),
                      "Description",
                      List.of(),
                      "en",
                      60,
                      "2020-01-01",
                      "Old summary"),
                  new float[] {0, 1})));
      var ids = List.of(mapper.pointId("missing"), mapper.pointId("present"));
      var before = client.retrieveAsync("summary-modes", ids, true, true, null).get();
      var store = new QdrantShortDescriptionStore(client, mapper, "summary-modes");
      var generator = mock(ShortDescriptionGenerator.class);
      when(generator.generate(anyList()))
          .thenAnswer(
              call -> {
                List<AudiobookRecord> input = call.getArgument(0);
                return input.stream()
                    .collect(
                        java.util.stream.Collectors.toMap(
                            AudiobookRecord::id, book -> "Generated summary"));
              });
      new ShortDescriptionEnricher(store, generator, 100, 1, "missing-only").run(null);
      new ShortDescriptionEnricher(store, generator, 100, 1, "missing-only").run(null);
      verify(generator, times(1)).generate(anyList());
      var filled =
          client
              .retrieveAsync("summary-modes", List.of(mapper.pointId("present")), true, true, null)
              .get()
              .getFirst();
      assertThat(filled.getPayloadOrThrow("short_description").getStringValue())
          .isEqualTo("Old summary");
      assertThat(store.findRecordsForShortDescription(1, ShortDescriptionMode.REBUILD_ALL))
          .hasSize(1);
      new ShortDescriptionEnricher(store, generator, 100, 1, "rebuild-all").run(null);
      verify(generator, times(2)).generate(anyList());
      var after = client.retrieveAsync("summary-modes", ids, true, true, null).get();
      for (var point : after) {
        var original =
            before.stream().filter(p -> p.getId().equals(point.getId())).findFirst().orElseThrow();
        assertThat(point.getVectors()).isEqualTo(original.getVectors());
        var expected = new java.util.HashMap<>(original.getPayloadMap());
        expected.put("short_description", io.qdrant.client.ValueFactory.value("Generated summary"));
        assertThat(point.getPayloadMap()).containsExactlyInAnyOrderEntriesOf(expected);
      }
      assertThat(store.findRecordsForShortDescription(100, ShortDescriptionMode.MISSING_ONLY))
          .isEmpty();
    }
  }
}
