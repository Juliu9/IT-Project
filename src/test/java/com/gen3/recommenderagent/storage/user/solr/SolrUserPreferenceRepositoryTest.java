package com.gen3.recommenderagent.storage.user.solr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SolrUserPreferenceRepositoryTest {
  private final SolrClient client = mock(SolrClient.class);
  private final SolrUserPreferenceRepository repository =
      new SolrUserPreferenceRepository(client, "preferences", 2, "books");

  private final QueryResponse response = mock(QueryResponse.class);

  @BeforeEach
  void emptyStore() throws Exception {
    when(response.getResults()).thenReturn(new SolrDocumentList());
    when(client.query(eq("preferences"), any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
        .thenReturn(response);
  }

  private void stored(SolrDocument document) {
    var documents = new SolrDocumentList();
    documents.add(document);
    when(response.getResults()).thenReturn(documents);
  }

  @Test
  void readsBothVectorsUsingTheExactUserIdAndNormalizesForRanking() throws Exception {
    var document = new SolrDocument();
    document.setField("favouritesVector", List.of(3f, 4f));
    document.setField("historyVector", new float[] {0, 1});
    stored(document);
    var result = repository.findEmbeddingByUserId("user:+ /quote\"").orElseThrow();
    assertThat(result.favouritesVector()).containsExactly(0.6f, 0.8f);
    assertThat(result.historyVector()).containsExactly(0, 1);
  }

  @Test
  void replacesBothVectorsAtomicallyAndClearsAnAbsentSide() throws Exception {
    repository.save(new UserPreferenceEmbedding("user", new float[] {1, 0}, null));
    var captured = ArgumentCaptor.forClass(java.util.Collection.class);
    verify(client).add(eq("preferences"), captured.capture());
    verify(client).commit("preferences");
    assertThat(((SolrInputDocument) captured.getValue().iterator().next()).getFieldValue("id"))
        .isEqualTo("user");
    assertThat(
            ((SolrInputDocument) captured.getValue().iterator().next())
                .getFieldValue("favouritesVector"))
        .isEqualTo(Map.of("set", List.of(1f, 0f)));
    assertThat(
            ((Map<?, ?>)
                    ((SolrInputDocument) captured.getValue().iterator().next())
                        .getFieldValue("historyVector"))
                .containsKey("set"))
        .isTrue();
    assertThat(
            ((Map<?, ?>)
                    ((SolrInputDocument) captured.getValue().iterator().next())
                        .getFieldValue("historyVector"))
                .get("set"))
        .isNull();
  }

  @Test
  void commitsMultipleUsersOnceAndSkipsEmptyBatches() throws Exception {
    repository.saveAll(List.of());
    verifyNoInteractions(client);
    repository.saveAll(
        List.of(
            new UserPreferenceEmbedding("one", new float[] {1, 0}, null),
            new UserPreferenceEmbedding("two", null, new float[] {0, 1})));
    var captured = ArgumentCaptor.forClass(java.util.Collection.class);
    verify(client).add(eq("preferences"), captured.capture());
    assertThat(captured.getValue()).hasSize(2);
    verify(client).commit("preferences");
    verifyNoMoreInteractions(client);
  }

  @Test
  void clearsMultipleExistingUsersWithOneWriteAndCommit() throws Exception {
    repository.clearAll(List.of());
    verifyNoInteractions(client);
    var document = new SolrDocument();
    document.setField("favouritesVector", List.of(1f, 0f));
    stored(document);
    repository.clearAll(List.of("one", "two"));
    var captured = ArgumentCaptor.forClass(java.util.Collection.class);
    verify(client).add(eq("preferences"), captured.capture());
    assertThat(captured.getValue()).hasSize(2);
    for (Object value : captured.getValue()) {
      var update = (SolrInputDocument) value;
      assertThat(update.getFieldNames())
          .containsExactlyInAnyOrder("id", "favouritesVector", "historyVector");
      for (String field : List.of("favouritesVector", "historyVector")) {
        assertThat(((Map<?, ?>) update.getFieldValue(field)).containsKey("set")).isTrue();
        assertThat(((Map<?, ?>) update.getFieldValue(field)).get("set")).isNull();
      }
    }
    verify(client).commit("preferences");
  }

  @Test
  void clearingMissingUsersDoesNotCreateDocuments() throws Exception {
    repository.clearAll(List.of("missing"));
    verify(client).query(eq("preferences"), any(SolrQuery.class), eq(SolrRequest.METHOD.POST));
    verifyNoMoreInteractions(client);
  }

  @Test
  void missingAndBlankUsersNeedNoWrites() throws Exception {
    assertThat(repository.findEmbeddingByUserId(null)).isEmpty();
    assertThat(repository.findEmbeddingByUserId(" ")).isEmpty();
    repository.save(null);
    verifyNoInteractions(client);
    assertThat(repository.findEmbeddingByUserId("missing")).isEmpty();
    verify(client).query(eq("preferences"), any(SolrQuery.class), eq(SolrRequest.METHOD.POST));
    verifyNoMoreInteractions(client);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dimension", "nan", "zero", "nonNumeric"})
  void invalidStoredVectorsFailAsIoErrors(String problem) throws Exception {
    var document = new SolrDocument();
    document.setField(
        "favouritesVector",
        switch (problem) {
          case "dimension" -> List.of(1f);
          case "nan" -> List.of(Float.NaN, 1f);
          case "zero" -> List.of(0f, 0f);
          default -> List.of("oops", 1f);
        });
    stored(document);
    assertThatThrownBy(() -> repository.findEmbeddingByUserId("user"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void invalidWritesAndCollectionCollisionAreRejectedBeforeIo() {
    for (float[] vector :
        List.of(new float[] {1}, new float[] {Float.POSITIVE_INFINITY, 1}, new float[] {0, 0})) {
      assertThatThrownBy(() -> repository.save(new UserPreferenceEmbedding("u", vector, null)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new SolrUserPreferenceRepository(client, "books", 2, "books"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client);
  }

  @Test
  void vendorErrorsRespectTheRepositoryIoContract() throws Exception {
    when(client.query(eq("preferences"), any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
        .thenThrow(
            new org.apache.solr.common.SolrException(
                org.apache.solr.common.SolrException.ErrorCode.SERVICE_UNAVAILABLE, "unavailable"));
    assertThatThrownBy(() -> repository.findEmbeddingByUserId("user"))
        .isInstanceOf(IOException.class);
  }
}
