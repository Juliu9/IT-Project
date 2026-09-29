package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gen3.recommenderagent.candidateretriever.SemanticQueryVectors;
import com.gen3.recommenderagent.ranker.strategy.HybridRankingStrategy;
import com.gen3.recommenderagent.ranker.strategy.SemanticQueryRankingStrategy;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookCandidate;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticQueryRankingTest {

  @Test
  void negativeVectorDemotesMatchingCandidate() {
    HybridRankingStrategy hybrid = new HybridRankingStrategy(new SemanticQueryRankingStrategy());
    AudiobookCandidate unwanted = candidate("horror", 0.90, new float[] {0, 1});
    AudiobookCandidate acceptable = candidate("adventure", 0.85, new float[] {1, 0});

    var result =
        hybrid.rank(
            List.of(unwanted, acceptable),
            2,
            new SemanticQueryVectors(List.of(), List.of(new float[] {0, 1})));

    assertEquals("adventure", result.getFirst().getBookId());
    assertEquals("horror", result.getLast().getBookId());
  }

  private AudiobookCandidate candidate(String id, double score, float[] embedding) {
    AudiobookRecord book = new AudiobookRecord(id, "catalogue", "Title " + id, List.of(), null);
    return new AudiobookCandidate(book, score, embedding);
  }
}
