package com.gen3.catalogue.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.catalogue.model.AudiobookFilterConditions;
import com.gen3.catalogue.model.AudiobookFilters;
import java.util.List;
import org.junit.jupiter.api.Test;

class QdrantPublicationFilterTest {
  @Test
  void includesTheEntireFinalYearWithoutStoringADuplicateYear() {
    var dates =
        new AudiobookFilterConditions(List.of(), List.of(), null, null, null, null, 2013, 2013);
    var filter = QdrantAudiobookSchema.filter(new AudiobookFilters(dates, dates, List.of()));
    var required = filter.getMust(0).getField();
    assertThat(required.getKey()).isEqualTo("pubdate");
    assertThat(required.hasDatetimeRange()).isTrue();
    assertThat(required.getDatetimeRange().getGte().getSeconds())
        .isEqualTo(java.time.Instant.parse("2013-01-01T00:00:00Z").getEpochSecond());
    assertThat(required.getDatetimeRange().getLt().getSeconds())
        .isEqualTo(java.time.Instant.parse("2014-01-01T00:00:00Z").getEpochSecond());
    assertThat(filter.getMustNot(0)).isEqualTo(filter.getMust(0));
  }

  @Test
  void maximumSupportedYearDoesNotCreateAnInvalidYear10000Timestamp() {
    var dates =
        new AudiobookFilterConditions(List.of(), List.of(), null, null, null, null, null, 9999);
    var range =
        QdrantAudiobookSchema.filter(
                new AudiobookFilters(dates, AudiobookFilterConditions.empty(), List.of()))
            .getMust(0)
            .getField()
            .getDatetimeRange();
    assertThat(range.hasLt()).isFalse();
    assertThat(range.getLte().getSeconds())
        .isEqualTo(java.time.Instant.parse("9999-12-31T23:59:59Z").getEpochSecond());
    assertThat(range.getLte().getNanos()).isEqualTo(999999000);
  }
}
