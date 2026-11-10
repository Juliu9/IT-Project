package com.gen3.recommenderagent.retrieval.strategy;

import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Provides keyword fallback when vector evidence is unusable. */
@Component
public final class KeywordRetriever {
  private final AudiobookCandidateSearch search;

  public KeywordRetriever(AudiobookCandidateSearch search) {
    this.search = search;
  }

  static boolean hasKeywords(AudiobookRetrievalPlan plan) {
    return plan.keywordText() != null && !plan.keywordText().isBlank();
  }

  List<AudiobookCandidate> retrieveKeywords(AudiobookRetrievalPlan plan) throws IOException {
    return hasKeywords(plan)
        ? search.searchKeyword(plan.keywordText(), plan.filters(), plan.limit())
        : List.of();
  }
}
