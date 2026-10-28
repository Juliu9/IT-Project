package com.gen3.recommenderagent.response;

import java.util.Arrays;

/** Shared spoken-response budget; catalogue and API values are never shortened in place. */
final class ResponseBudget {
  static final int MAX_WORDS = 150;

  private ResponseBudget() {}

  static int wordCount(String text) {
    return text == null || text.isBlank() ? 0 : text.strip().split("(?U)\\s+").length;
  }

  static String limitWords(String text, int maximum) {
    if (text == null || text.isBlank() || maximum <= 0) {
      return "";
    }
    String[] words = text.strip().split("(?U)\\s+");
    return String.join(" ", Arrays.copyOf(words, Math.min(maximum, words.length)));
  }

  static String label(String text, int maximum) {
    return wordCount(text) <= maximum ? text : limitWords(text, maximum) + "…";
  }
}
