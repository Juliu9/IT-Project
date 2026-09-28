package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.session.Preferences;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.PreferenceRankingStrategy;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreferenceRankingTest {

  /** A strong negative match can demote a slightly more relevant database result. */
  @Test
  void negativeVectorDemotesMatchingCandidate() {
    PreferenceRankingStrategy preference = new PreferenceRankingStrategy();
    HybridRankingStrategy hybrid = new HybridRankingStrategy(preference);
    AudiobookCandidate unwanted = candidate("horror", 0.90, new float[] {0, 1});
    AudiobookCandidate acceptable = candidate("adventure", 0.85, new float[] {1, 0});

    var result =
        hybrid.rank(
            List.of(unwanted, acceptable),
            2,
            new PreferenceSignals(List.of(), List.of(new float[] {0, 1})));

    assertEquals("adventure", result.getFirst().getBookId());
    assertEquals("horror", result.getLast().getBookId());
  }

  /** Explicit include and exclude terms are embedded in separate polarity batches. */
  @Test
  void buildsSeparatePositiveAndNegativeSignals() {
    EmbeddingIndexer indexer = mock(EmbeddingIndexer.class);
    when(indexer.embedPreferenceTerms(List.of("space opera")))
        .thenReturn(List.of(new float[] {1, 0}));
    when(indexer.embedPreferenceTerms(List.of("gore"))).thenReturn(List.of(new float[] {0, 1}));
    PreferenceVectorService service = new PreferenceVectorService(indexer);

    SessionRequest request = new SessionRequest();
    request.setPreferences(preferences(List.of("space opera"), List.of("gore")));

    PreferenceSignals signals = service.create(request);

    assertEquals(1, signals.positive().size());
    assertEquals(1, signals.negative().size());
    verify(indexer).embedPreferenceTerms(List.of("space opera"));
    verify(indexer).embedPreferenceTerms(List.of("gore"));
  }

  private Preferences preferences(List<String> include, List<String> exclude) {
    Preferences preferences = new Preferences();
    preferences.setInclude(include);
    preferences.setExclude(exclude);
    return preferences;
  }

  private AudiobookCandidate candidate(String id, double score, float[] embedding) {
    AudiobookRecord book = new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null);
    return new AudiobookCandidate(book, score, embedding);
  }
}
