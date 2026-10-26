package com.gen3.catalogue.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PersonNameNormalizerTest {
  @ParameterizedTest
  @CsvSource({
    "Suzanne Collins,'Collins, Suzanne'",
    "'Collins, Suzanne',Suzanne Collins",
    "'García Márquez, Gabriel',Gabriel García Márquez"
  })
  void generatesSafeVariantsWithoutChangingTheOriginal(String input, String alternative) {
    assertThat(PersonNameNormalizer.variants(List.of(input))).contains(input, alternative);
    assertThat(PersonNameNormalizer.comparisonKey(input))
        .isEqualTo(PersonNameNormalizer.comparisonKey(alternative));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Gabriel García Márquez",
        "Martin Luther King, Jr.",
        "John Smith Jr.",
        "Smith, John, Jr.",
        "Cher",
        "Collins,",
        ", Suzanne"
      })
  void leavesUncertainNamesAndSuffixesUnchanged(String name) {
    assertThat(PersonNameNormalizer.variants(List.of(name))).containsExactly(name);
  }

  @Test
  void deduplicatesVariantsAndIgnoresMissingNames() {
    assertThat(
            PersonNameNormalizer.variants(
                Arrays.asList(null, " ", "Suzanne Collins", "Collins, Suzanne", "Suzanne Collins")))
        .containsExactly("Suzanne Collins", "Collins, Suzanne");
    assertThat(PersonNameNormalizer.variants(null)).isEmpty();
    assertThat(PersonNameNormalizer.comparisonKey("  Collins,   Suzanne  "))
        .isEqualTo("suzanne collins");
    assertThat(PersonNameNormalizer.comparisonKey("Martin Luther King, Jr."))
        .isNotEqualTo(PersonNameNormalizer.comparisonKey("Jr. Martin Luther King"));
  }
}
