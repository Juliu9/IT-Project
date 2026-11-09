package com.gen3.recommenderagent.domain.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchHistorySnapshotTest {

  @Test
  void newSearchHasNoSnapshot() {
    assertThat(new SearchContext().getSearchHistorySnapshot()).isNull();
  }

  @Test
  void copyKeepsTheSnapshotForMoreResults() {
    var context = new SearchContext();
    context.setSearchHistorySnapshot(new float[] {0.6f, 0.8f});

    assertThat(context.copy().getSearchHistorySnapshot()).containsExactly(0.6f, 0.8f);
  }

  @Test
  void snapshotCannotBeChangedFromOutside() {
    var taste = new float[] {0.6f, 0.8f};
    var context = new SearchContext();
    context.setSearchHistorySnapshot(taste);

    taste[0] = 99f;
    context.getSearchHistorySnapshot()[1] = 99f;

    assertThat(context.getSearchHistorySnapshot()).containsExactly(0.6f, 0.8f);
  }
}
