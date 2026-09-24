package com.gen3.recommenderagent.ranker.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.recommenderagent.domain.Intent;
import org.junit.jupiter.api.Test;

/** Verifies that intent routing remains centralized and deliberate. */
class IntentRetrievalPolicyTest {

  private final IntentRetrievalPolicy policy = new IntentRetrievalPolicy();

  /** Covers one representative intent for every retrieval mode used by the policy. */
  @Test
  void mapsIntentsToTheirRetrievalModes() {
    assertThat(policy.modeFor(Intent.SIMILAR_TO_BOOK)).isEqualTo(RetrievalMode.SEMANTIC);
    assertThat(policy.modeFor(Intent.NEW_RECOMMENDATION)).isEqualTo(RetrievalMode.HYBRID);
    assertThat(policy.modeFor(Intent.FILTER_BY_NARRATOR)).isEqualTo(RetrievalMode.FILTER_ONLY);
    assertThat(policy.modeFor(Intent.HELP)).isEqualTo(RetrievalMode.NONE);
    assertThat(policy.modeFor(Intent.UNKNOWN)).isEqualTo(RetrievalMode.SEMANTIC);
  }
}
