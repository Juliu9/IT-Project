package com.gen3.recommenderagent.retrieval.strategy;

import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalStrategy;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import com.gen3.shared.audiobookcatalogue.port.AudiobookCandidateSearch;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Searches the catalogue using keyword evidence. */
@Component
public final class KeywordRetriever implements CandidateRetrievalStrategy {
  private final AudiobookCandidateSearch search;

  public KeywordRetriever(AudiobookCandidateSearch search) {
    this.search = search;
  }

  @Override
  public String name() {
    return "KEYWORD";
  }

  @Override
  public int getOrder() {
    return 1002;
  }

  @Override
  public Optional<List<AudiobookCandidate>> tryRetrieve(
      SessionRequest request, SemanticQueryVectors vectors, AudiobookRetrievalPlan plan)
      throws IOException {
    return hasKeywords(plan) ? Optional.of(retrieveKeywords(plan)) : Optional.empty();
  }

  static boolean hasKeywords(AudiobookRetrievalPlan plan) {
    return plan.keywordText() != null && !plan.keywordText().isBlank();
  }

  List<AudiobookCandidate> retrieveKeywords(AudiobookRetrievalPlan plan) throws IOException {
    return search.searchKeyword(plan.keywordText(), plan.filters(), plan.limit());
  }
}
