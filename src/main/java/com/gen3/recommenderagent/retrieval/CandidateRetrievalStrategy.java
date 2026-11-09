package com.gen3.recommenderagent.retrieval;

import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.embedding.SemanticQueryVectors;
import com.gen3.recommenderagent.retrieval.plan.AudiobookRetrievalPlan;
import com.gen3.shared.audiobookcatalogue.model.AudiobookCandidate;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.core.Ordered;

/** An independently registered candidate search. Lower order values run first. */
public interface CandidateRetrievalStrategy extends Ordered {
  /** Custom strategies run ahead of the defaults unless they declare another order. */
  @Override
  default int getOrder() {
    return 0;
  }

  /** Returns the stable retrieval mode used in telemetry. */
  String name();

  /**
   * Declines with Optional.empty(), or supplies the complete result, including an empty list.
   * Implementations must honor the plan's filters, exclusions, and limit.
   */
  Optional<List<AudiobookCandidate>> tryRetrieve(
      SessionRequest request, SemanticQueryVectors vectors, AudiobookRetrievalPlan plan)
      throws IOException;
}
