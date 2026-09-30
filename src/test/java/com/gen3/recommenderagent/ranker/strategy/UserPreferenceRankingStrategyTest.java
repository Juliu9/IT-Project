package com.gen3.recommenderagent.ranker.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.RankingContext;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserPreferenceRankingStrategyTest {

  @Test
  void userVectorsCanPromoteARelevantPreferenceMatch() {
    HybridRankingStrategy hybrid =
        new HybridRankingStrategy(
            new SemanticQueryRankingStrategy(), new UserPreferenceRankingStrategy());
    AudiobookCandidate generic = candidate("generic", 0.90, new float[] {0, 1});
    AudiobookCandidate preferred = candidate("preferred", 0.80, new float[] {1, 0});
    UserPreferenceEmbedding preferences =
        new UserPreferenceEmbedding("user-1", new float[] {1, 0}, new float[] {1, 0});

    var result =
        hybrid.rank(
            List.of(generic, preferred),
            2,
            new RankingContext(SemanticQueryVectors.empty(), preferences, Set.of()));

    assertEquals("preferred", result.getFirst().getBookId());
  }

  private AudiobookCandidate candidate(String id, double score, float[] embedding) {
    AudiobookRecord book = new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null);
    return new AudiobookCandidate(book, score, embedding);
  }
}
