package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Preferences;
import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreferenceVectorServiceTest {

  @Test
  void combinesNewQueryPolarityWithLegacyPreferenceFields() {
    EmbeddingIndexer indexer = mock(EmbeddingIndexer.class);
    SemanticQuery semanticQuery = new SemanticQuery();
    semanticQuery.setPositive(List.of("space opera"));
    semanticQuery.setNegative(List.of("horror"));
    Preferences preferences = new Preferences();
    preferences.setInclude(List.of("found family", "space opera"));
    preferences.setExclude(List.of("gore"));
    SessionRequest request = new SessionRequest();
    request.setQuery(semanticQuery);
    request.setPreferences(preferences);
    when(indexer.embedPreferenceTerms(List.of("space opera", "found family")))
        .thenReturn(List.of(new float[] {1, 0}, new float[] {0, 1}));
    when(indexer.embedPreferenceTerms(List.of("horror", "gore")))
        .thenReturn(List.of(new float[] {-1, 0}, new float[] {0, -1}));

    PreferenceSignals signals = new PreferenceVectorService(indexer).create(request);

    assertEquals(2, signals.positive().size());
    assertEquals(2, signals.negative().size());
  }
}
