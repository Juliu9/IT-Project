package com.gen3.shortdescriptionjob.store;

import static io.qdrant.client.ConditionFactory.hasId;
import static io.qdrant.client.ConditionFactory.isEmpty;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.gen3.recommenderagent.storage.audiobook.model.StoredAudiobook;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantPointMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.CreateFieldIndexCollection;
import io.qdrant.client.grpc.Points.FieldType;
import io.qdrant.client.grpc.Points.PointsSelector;
import io.qdrant.client.grpc.Points.PointsUpdateOperation;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "audiobook.database", havingValue = "qdrant", matchIfMissing = true)
public class QdrantShortDescriptionStore implements AudiobookEnrichmentStore {
  private static final String SHORT_DESCRIPTION = "short_description";
  private final QdrantClient client;
  private final QdrantPointMapper mapper;
  private final String collection;

  public QdrantShortDescriptionStore(
      QdrantClient client,
      QdrantPointMapper mapper,
      @Value("${qdrant.collection:audiobooks_hybrid}") String collection) {
    this.client = client;
    this.mapper = mapper;
    this.collection = collection;
  }

  @Value("${qdrant.url:http://localhost:6333}")
  private String endpoint = "http://localhost:6333";

  @Value("${qdrant.grpc-port:6334}")
  private int grpcPort = 6334;

  @Override
  public DatasetIdentity datasetIdentity() {
    String url = endpoint.contains("://") ? endpoint.trim() : "http://" + endpoint.trim();
    URI uri = URI.create(url);
    return new DatasetIdentity(
        "qdrant",
        uri.getScheme().toLowerCase(Locale.ROOT)
            + "://"
            + uri.getHost().toLowerCase(Locale.ROOT)
            + ":"
            + grpcPort
            + "/"
            + collection);
  }

  /** Stores generated short descriptions without recalculating or rewriting the book vectors. */
  @Override
  public void updateShortDescriptions(List<ShortDescriptionUpdate> updates) throws IOException {
    if (updates == null || updates.isEmpty()) {
      return;
    }
    requireCollection();
    List<PointsUpdateOperation> operations = new ArrayList<>();
    for (ShortDescriptionUpdate update : updates) {
      if (update != null && hasText(update.storageReference()) && hasText(update.description())) {
        operations.add(
            setMissingPayload(
                pointId(update.storageReference()),
                SHORT_DESCRIPTION,
                io.qdrant.client.ValueFactory.value(update.description())));
      }
    }
    updatePayloads(operations);
  }

  private PointsUpdateOperation setMissingPayload(
      io.qdrant.client.grpc.Common.PointId pointId,
      String field,
      io.qdrant.client.grpc.JsonWithInt.Value value) {
    PointsSelector selector =
        PointsSelector.newBuilder()
            .setFilter(Filter.newBuilder().addMust(hasId(pointId)).addMust(isEmpty(field)))
            .build();
    return PointsUpdateOperation.newBuilder()
        .setSetPayload(
            PointsUpdateOperation.SetPayload.newBuilder()
                .putPayload(field, value)
                .setPointsSelector(selector))
        .build();
  }

  /** Loads Qdrant records that still need a generated short description. */
  @Override
  public List<StoredAudiobook> findRecordsWithoutShortDescription(int maximumRecords)
      throws IOException {
    if (maximumRecords <= 0) {
      return List.of();
    }
    requireCollection();
    createPayloadIndex(SHORT_DESCRIPTION);
    List<StoredAudiobook> records = new ArrayList<>();
    io.qdrant.client.grpc.Common.PointId offset = null;
    while (records.size() < maximumRecords) {
      ScrollPoints.Builder request =
          ScrollPoints.newBuilder()
              .setCollectionName(collection)
              .setFilter(Filter.newBuilder().addMust(isEmpty(SHORT_DESCRIPTION)))
              .setLimit(Math.min(500, maximumRecords - records.size()))
              .setWithPayload(enable(true));
      if (offset != null) {
        request.setOffset(offset);
      }
      ScrollResponse page = await(client.scrollAsync(request.build()), "read audiobook records");
      records.addAll(
          page.getResultList().stream()
              .map(
                  point ->
                      new StoredAudiobook(
                          mapper.toRecord(point),
                          Base64.getEncoder().encodeToString(point.getId().toByteArray())))
              .filter(AudiobookEnrichmentStore::eligible)
              .toList());
      if (!page.hasNextPageOffset() || page.getResultCount() == 0) {
        break;
      }
      offset = page.getNextPageOffset();
    }
    return records;
  }

  private io.qdrant.client.grpc.Common.PointId pointId(String storageReference) throws IOException {
    try {
      return io.qdrant.client.grpc.Common.PointId.parseFrom(
          Base64.getDecoder().decode(storageReference));
    } catch (IllegalArgumentException exception) {
      throw new IOException("Invalid Qdrant storage reference", exception);
    }
  }

  private void updatePayloads(List<PointsUpdateOperation> operations) throws IOException {
    if (operations.isEmpty()) {
      return;
    }
    for (int start = 0; start < operations.size(); start += 100) {
      List<PointsUpdateOperation> batch =
          operations.subList(start, Math.min(start + 100, operations.size()));
      await(client.batchUpdateAsync(collection, batch), "store audiobook short descriptions");
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info(
              "short.description.import.progress processed={} total={}",
              start + batch.size(),
              operations.size());
    }
  }

  private void requireCollection() throws IOException {
    if (!await(client.collectionExistsAsync(collection), "check Qdrant collection")) {
      throw new IOException("Qdrant collection does not exist: " + collection);
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private void createPayloadIndex(String field) throws IOException {
    var request =
        CreateFieldIndexCollection.newBuilder()
            .setCollectionName(collection)
            .setFieldName(field)
            .setFieldType(FieldType.FieldTypeKeyword)
            .setWait(true)
            .build();
    await(
        client.createPayloadIndexAsync(request, Duration.ofSeconds(30)),
        "create Qdrant payload index for " + field);
  }

  private <T> T await(Future<T> future, String operation) throws IOException {
    try {
      return future.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted: " + operation, exception);
    } catch (ExecutionException exception) {
      throw new IOException("Failed: " + operation, exception.getCause());
    }
  }
}
