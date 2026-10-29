package com.gen3.shortdescriptionjob.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.shortdescriptionjob.ShortDescriptionMode;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SolrShortDescriptionStoreTest {
  private final SolrClient client = mock(SolrClient.class);
  private final SolrAudiobookRepository catalogue = mock(SolrAudiobookRepository.class);
  private final SolrShortDescriptionStore store =
      new SolrShortDescriptionStore(client, catalogue, "books");

  @Test
  void readsOnlyMissingSummariesAndKeepsStorageReferences() throws Exception {
    var missing = new AudiobookRecord("one", "s", "Title", List.of(), "Description");
    var present =
        new AudiobookRecord(
            "two",
            "s",
            "Title",
            List.of(),
            "Description",
            List.of(),
            "en",
            60,
            2020,
            "Existing summary");
    when(catalogue.findAllBooks(null, 500)).thenReturn(List.of(missing, present));
    assertThat(store.findRecordsForShortDescription(10, ShortDescriptionMode.MISSING_ONLY))
        .hasSize(1)
        .first()
        .extracting(book -> book.storageReference())
        .isEqualTo("one");
  }

  @Test
  void skipsUnusableRecordsBeforeCountingTheLimitAcrossPages() throws Exception {
    var invalid = new AudiobookRecord("invalid", "s", "Title", List.of(), " ");
    var valid = new AudiobookRecord("valid", "s", "Title", List.of(), "Description");
    when(catalogue.findAllBooks(null, 500)).thenReturn(java.util.Collections.nCopies(500, invalid));
    when(catalogue.findAllBooks("invalid", 500)).thenReturn(List.of(valid));
    assertThat(store.findRecordsForShortDescription(1, ShortDescriptionMode.MISSING_ONLY))
        .extracting(book -> book.record().id())
        .containsExactly("valid");
  }

  @Test
  void writesOnlyTheSummaryWithAnOptimisticVersionCheckAndPreservesExistingSummaries()
      throws Exception {
    var missing = new SolrDocument();
    missing.setField("id", "missing");
    missing.setField("_version_", 123L);
    var present = new SolrDocument();
    present.setField("id", "present");
    present.setField("short_description", "Keep this");
    var records = new org.apache.solr.common.SolrDocumentList();
    records.add(missing);
    records.add(present);
    when(client.getById(
            eq("books"),
            eq(List.of("missing", "present")),
            org.mockito.ArgumentMatchers.any(org.apache.solr.common.params.SolrParams.class)))
        .thenReturn(records);
    store.updateShortDescriptions(
        List.of(
            new AudiobookEnrichmentStore.ShortDescriptionUpdate("missing", "Generated"),
            new AudiobookEnrichmentStore.ShortDescriptionUpdate("present", "Replacement")),
        ShortDescriptionMode.MISSING_ONLY);
    var documents = ArgumentCaptor.forClass(java.util.Collection.class);
    verify(client).add(eq("books"), documents.capture());
    assertThat(documents.getValue()).hasSize(1);
    var document = (SolrInputDocument) documents.getValue().iterator().next();
    assertThat(document.getFieldNames())
        .containsExactlyInAnyOrder("id", "_version_", "short_description");
    assertThat(document.getFieldValue("_version_")).isEqualTo(123L);
    assertThat(document.getFieldValue("short_description")).isEqualTo(Map.of("set", "Generated"));
    verify(client).commit("books");
  }

  @Test
  void boundsReadsAndWritesAcrossLargeImports() throws Exception {
    when(client.getById(
            eq("books"),
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.any(org.apache.solr.common.params.SolrParams.class)))
        .thenAnswer(
            invocation -> {
              var records = new org.apache.solr.common.SolrDocumentList();
              for (String id : (java.util.Collection<String>) invocation.getArgument(1)) {
                var record = new SolrDocument();
                record.setField("id", id);
                record.setField("_version_", 123L);
                records.add(record);
              }
              return records;
            });
    store.updateShortDescriptions(
        java.util.stream.IntStream.range(0, 501)
            .mapToObj(
                i -> new AudiobookEnrichmentStore.ShortDescriptionUpdate("id-" + i, "Summary"))
            .toList(),
        ShortDescriptionMode.MISSING_ONLY);
    var ids = ArgumentCaptor.forClass(java.util.Collection.class);
    verify(client, org.mockito.Mockito.times(2))
        .getById(
            eq("books"),
            ids.capture(),
            org.mockito.ArgumentMatchers.any(org.apache.solr.common.params.SolrParams.class));
    assertThat(ids.getAllValues()).extracting(java.util.Collection::size).containsExactly(500, 1);
    var writes = ArgumentCaptor.forClass(java.util.Collection.class);
    verify(client, org.mockito.Mockito.times(2)).add(eq("books"), writes.capture());
    assertThat(writes.getAllValues())
        .extracting(java.util.Collection::size)
        .containsExactly(500, 1);
  }

  @Test
  void refusesUnsafeUpdatesWhenTheVersionIsMissing() throws Exception {
    var records = new org.apache.solr.common.SolrDocumentList();
    var document = new SolrDocument();
    document.setField("id", "id");
    records.add(document);
    when(client.getById(
            eq("books"),
            eq(List.of("id")),
            org.mockito.ArgumentMatchers.any(org.apache.solr.common.params.SolrParams.class)))
        .thenReturn(records);
    assertThatThrownBy(
            () ->
                store.updateShortDescriptions(
                    List.of(new AudiobookEnrichmentStore.ShortDescriptionUpdate("id", "Generated")),
                    ShortDescriptionMode.MISSING_ONLY))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("_version_");
    verify(client)
        .getById(
            eq("books"),
            eq(List.of("id")),
            org.mockito.ArgumentMatchers.any(org.apache.solr.common.params.SolrParams.class));
    verifyNoMoreInteractions(client);
  }
}
