package com.gen3.catalogue.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicationDateTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "2013", "2013-02-30", "0000-01-01", "not-a-date"})
  void missingOrInvalidDateHasNoYear(String date) {
    assertThat(PublicationDate.normalize(date)).isNull();
    assertThat(PublicationDate.year(date)).isNull();
  }

  @Test
  void datesAndSolrDateObjectsHaveTheSameYear() {
    assertThat(PublicationDate.normalize("2013-07-03")).isEqualTo("2013-07-03");
    assertThat(PublicationDate.year("2013-07-03")).isEqualTo(2013);
    var instant = java.time.Instant.parse("2013-07-03T00:00:00Z");
    assertThat(PublicationDate.normalize(java.util.Date.from(instant)))
        .isEqualTo(instant.toString());
    assertThat(PublicationDate.normalize("2014-01-01T01:00:00+02:00"))
        .isEqualTo("2013-12-31T23:00:00Z");
  }

  @Test
  void bookJsonUsesOnlyTheNewStoredFieldsAndYearIsDerivedAfterReload() {
    var record =
        new AudiobookRecord(
            "one", "s", "Title", List.of(), "Description", List.of(), "English", 120, "2013-07-03");
    var mapper = new tools.jackson.databind.ObjectMapper();
    String json = mapper.writeValueAsString(record);
    assertThat(json)
        .contains("\"id\":\"one\"", "\"duration_minutes\":120", "\"pubdate\":\"2013-07-03\"")
        .doesNotContain("bookId", "durationMinutes", "publicationYear");
    var book =
        new com.gen3.recommenderagent.domain.recommendation.Recommendation("one", 2, 1.0, "Title");
    book.setDurationMinutes(120);
    book.setPubdate(record.pubdate());
    String saved = mapper.writeValueAsString(book);
    assertThat(saved).doesNotContain("bookId", "durationMinutes", "publicationYear");
    var restored =
        mapper.readValue(
            saved, com.gen3.recommenderagent.domain.recommendation.Recommendation.class);
    assertThat(restored.getPublicationYear()).isEqualTo(2013);
    assertThat(restored.getDurationMinutes()).isEqualTo(120);
    assertThat(restored.getId()).isEqualTo("one");
  }
}
