package com.gen3.recommenderagent.ranker.scorer;

import com.gen3.catalogue.model.AudiobookCandidate;
import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.recommenderagent.ranker.RankingContext;
import java.time.Year;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Scores user-independent publication recency and duration plausibility rules. */
@Component
public class UniversalRulesScorer implements CandidateScorer {

  private final double recencyWeight;
  private final double durationPlausibilityWeight;
  private final IntSupplier currentYear;

  @Autowired
  public UniversalRulesScorer(RankingScoringProperties properties) {
    this(properties, () -> Year.now().getValue());
  }

  UniversalRulesScorer(RankingScoringProperties properties, IntSupplier currentYear) {
    RankingScoringProperties.UniversalRuleWeights weights = properties.universalRules();
    this.recencyWeight = weights.recencyWeight();
    this.durationPlausibilityWeight = weights.durationPlausibilityWeight();
    this.currentYear = currentYear;
  }

  @Override
  public ScoreResult score(AudiobookCandidate candidate, RankingContext context) {
    if (candidate == null || candidate.audiobook() == null) {
      return ScoreResult.unavailable();
    }

    AudiobookRecord book = candidate.audiobook();
    double weightedScore = 0.0;
    double availableWeight = 0.0;

    Double recency = recencyScore(book.publicationYear());
    if (recency != null && recencyWeight > 0.0) {
      weightedScore += recency * recencyWeight;
      availableWeight += recencyWeight;
    }

    Double duration = durationPlausibilityScore(book.durationMinutes());
    if (duration != null && durationPlausibilityWeight > 0.0) {
      weightedScore += duration * durationPlausibilityWeight;
      availableWeight += durationPlausibilityWeight;
    }

    return availableWeight == 0.0
        ? ScoreResult.unavailable()
        : ScoreResult.available(weightedScore / availableWeight);
  }

  private Double recencyScore(Integer publicationYear) {
    if (publicationYear == null || publicationYear <= 0) {
      return null;
    }
    int age = Math.max(0, currentYear.getAsInt() - publicationYear);
    if (age <= 5) {
      return 1.0;
    }
    if (age <= 15) {
      return 0.8;
    }
    if (age <= 30) {
      return 0.6;
    }
    if (age <= 50) {
      return 0.45;
    }
    return 0.35;
  }

  private Double durationPlausibilityScore(Integer durationMinutes) {
    if (durationMinutes == null || durationMinutes <= 0) {
      return null;
    }
    if (durationMinutes >= 30 && durationMinutes <= 1_800) {
      return 1.0;
    }
    if ((durationMinutes >= 15 && durationMinutes < 30)
        || (durationMinutes > 1_800 && durationMinutes <= 3_000)) {
      return 0.6;
    }
    return 0.2;
  }
}
