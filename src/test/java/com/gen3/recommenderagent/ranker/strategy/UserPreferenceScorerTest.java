package com.gen3.recommenderagent.ranker.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.ranker.RankingService;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserPreferenceScorerTest {

  @Test
  void userVectorsCanPromoteARelevantPreferenceMatch() {
    UserPreferenceScorer preferenceScorer = new UserPreferenceScorer();
    AudiobookCandidate generic = candidate("generic", 0.90, new float[] {0, 1});
    AudiobookCandidate preferred = candidate("preferred", 0.80, new float[] {1, 0});
    UserPreferenceEmbedding preferences =
        new UserPreferenceEmbedding("user-1", new float[] {1, 0}, new float[] {1, 0});

    RankingService ranker =
        new RankingService(new CompositeScorer(List.of(new WeightedScorer(preferenceScorer, 1.0))));
    var result =
        ranker.rank(
            List.of(generic, preferred),
            2,
            new RankingContext(SemanticQueryVectors.empty(), preferences, Set.of()));

    assertEquals("preferred", result.getFirst().getBookId());
    assertEquals(1.0, result.getFirst().getScore());
  }

  private AudiobookCandidate candidate(String id, double score, float[] embedding) {
    AudiobookRecord book = new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null);
    return new AudiobookCandidate(book, score, embedding);
  }
}
