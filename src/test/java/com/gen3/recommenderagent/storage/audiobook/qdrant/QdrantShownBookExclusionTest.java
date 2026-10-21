package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.embedding.VectorMath;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilterConditions;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookFilters;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class QdrantShownBookExclusionTest {
  @Container
  static GenericContainer<?> server =
      new GenericContainer<>("qdrant/qdrant:v1.15.4").withExposedPorts(6334);

  @Test
  void everySearchModeFindsTheUnseenBookBeyondTheFirstFifty() throws Exception {
    try (QdrantClient client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(server.getHost(), server.getMappedPort(6334), false)
                .build())) {
      var encoder = new Bm25SparseTextEncoder();
      var repository =
          new QdrantAudiobookRepository(
              client, new QdrantPointMapper(encoder), encoder, "books", 2);
      repository.indexEmbeddings(
          IntStream.range(0, 51)
              .mapToObj(
                  index ->
                      new AudiobookEmbedding(
                          new AudiobookRecord(
                              "book-" + index,
                              "catalogue",
                              "Fantasy book",
                              List.of("Author"),
                              "A fantasy adventure.",
                              List.of("Narrator"),
                              "English",
                              100,
                              2020,
                              null),
                          VectorMath.normalize(new float[] {1, index / 100.0f})))
              .toList());
      List<String> shown =
          repository.searchSemantic(new float[] {1, 0}, AudiobookFilters.empty(), 50).stream()
              .map(candidate -> candidate.audiobook().id())
              .toList();
      assertThat(shown).hasSize(50);
      var filters =
          new AudiobookFilters(
              new AudiobookFilterConditions(
                  List.of(), List.of(), "English", null, null, null, null, null),
              AudiobookFilterConditions.empty(),
              shown);
      var semantic = repository.searchSemantic(new float[] {1, 0}, filters, 50);
      assertThat(semantic).hasSize(1);
      String unseen = semantic.getFirst().audiobook().id();
      assertThat(shown).doesNotContain(unseen);
      assertThat(repository.searchKeyword("fantasy", filters, 50))
          .extracting(c -> c.audiobook().id())
          .containsExactly(unseen);
      assertThat(repository.searchHybrid(new float[] {1, 0}, "fantasy", filters, 50))
          .extracting(c -> c.audiobook().id())
          .containsExactly(unseen);
      assertThat(repository.searchByFilters(filters, 50))
          .extracting(c -> c.audiobook().id())
          .containsExactly(unseen);
    }
  }
}
