package com.gen3.recommenderagent.storage.audiobook.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.storage.audiobook.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.AudiobookRepository.AudiobookEmbedding;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the stable point ID and payload contract used by Qdrant. */
class QdrantPointMapperTest {

  private final QdrantPointMapper mapper = new QdrantPointMapper(new Bm25SparseTextEncoder());

  /** Ensures migration reruns overwrite the same point and preserve catalogue fields. */
  @Test
  void mapsAudiobookToDeterministicPoint() {
    AudiobookRecord book =
        new AudiobookRecord(
            "book-7", "catalogue", "A Title", List.of("First", "Second"), "Description");

    PointStruct first = mapper.toPoint(new AudiobookEmbedding(book, new float[] {0.6f, 0.8f}));
    PointStruct second = mapper.toPoint(new AudiobookEmbedding(book, new float[] {0.6f, 0.8f}));

    assertThat(first.getId()).isEqualTo(second.getId());
    assertThat(first.getPayloadMap().get("bookId").getStringValue()).isEqualTo("book-7");
    assertThat(
            first
                .getVectors()
                .getVectors()
                .getVectorsMap()
                .get(QdrantPointMapper.DENSE_VECTOR)
                .getDense()
                .getDataList())
        .containsExactly(0.6f, 0.8f);
  }

  /** Ensures search payloads return the database-independent record expected by the application. */
  @Test
  void mapsSearchResultToAudiobook() {
    PointStruct point =
        mapper.toPoint(
            new AudiobookEmbedding(
                new AudiobookRecord("1", "src", "Title", List.of("Author"), "Summary"),
                new float[] {1.0f, 0.0f}));
    ScoredPoint result =
        ScoredPoint.newBuilder().setId(point.getId()).putAllPayload(point.getPayloadMap()).build();

    AudiobookRecord record = mapper.toRecord(result);

    assertThat(record.id()).isEqualTo("1");
    assertThat(record.authors()).containsExactly("Author");
    assertThat(record.title()).isEqualTo("Title");
  }
}
