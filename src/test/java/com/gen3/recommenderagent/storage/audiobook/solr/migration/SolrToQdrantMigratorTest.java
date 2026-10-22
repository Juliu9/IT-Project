package com.gen3.recommenderagent.storage.audiobook.solr.migration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.audiobook.qdrant.QdrantAudiobookRepository;
import com.gen3.recommenderagent.storage.audiobook.solr.SolrAudiobookRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class SolrToQdrantMigratorTest {
  @Test
  void copiesStoredVectorsAndRecordsWithoutVectorsAcrossPages() throws Exception {
    var source = mock(SolrAudiobookRepository.class);
    var destination = mock(QdrantAudiobookRepository.class);
    var first =
        new AudiobookEmbedding(
            new AudiobookRecord("1", "source", "One", List.of(), "Description"),
            new float[] {2, 3});
    var second =
        new AudiobookEmbedding(new AudiobookRecord("2", "source", "Two", List.of(), null), null);
    var third =
        new AudiobookEmbedding(
            new AudiobookRecord("3", "source", "Three", List.of(), "Description"), null);
    when(source.findStoredBooks(0, 2)).thenReturn(List.of(first, second));
    when(source.findStoredBooks(2, 2)).thenReturn(List.of(third));
    new SolrToQdrantMigrator(source, destination, 2, 0).run(null);
    verify(destination).initialize();
    verify(destination).importStoredBooks(List.of(first, second));
    verify(destination).importStoredBooks(List.of(third));
    verifyNoMoreInteractions(destination);
  }

  @Test
  void recordLimitCountsReadRecordsEvenWhenDestinationSkipsExistingRecords() throws Exception {
    var source = mock(SolrAudiobookRepository.class);
    var destination = mock(QdrantAudiobookRepository.class);
    var record =
        new AudiobookEmbedding(new AudiobookRecord("1", "s", "Title", List.of(), null), null);
    when(source.findStoredBooks(0, 1)).thenReturn(List.of(record));
    new SolrToQdrantMigrator(source, destination, 2, 1).run(null);
    verify(source).findStoredBooks(0, 1);
    verifyNoMoreInteractions(source);
  }
}
