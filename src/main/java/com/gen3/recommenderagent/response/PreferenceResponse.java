package com.gen3.recommenderagent.response;

import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed session-preference report, bounded without splitting saved values. */
final class PreferenceResponse {
  private static final String OMITTED = " Additional preferences omitted.";

  private PreferenceResponse() {}

  static String render(ConversationTurn turn) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    var preferences = turn.getSessionPreferenceSnapshot();
    if (preferences != null) {
      semantic(fields, "Preferred", preferences.getPositiveSemanticQuery());
      semantic(fields, "Avoided", preferences.getNegativeSemanticQuery());
      exact(fields, "Required", preferences.getMustInclude());
      exact(fields, "Excluded", preferences.getMustNotInclude());
    }
    String response =
        fields.isEmpty()
            ? "You have no saved session preferences."
            : "Your current session preferences:";
    response += " Recommendation count: " + turn.getBookCount() + ".";
    String complete =
        response
            + fields.entrySet().stream()
                .map(
                    field ->
                        " " + field.getKey() + ": " + String.join(", ", field.getValue()) + ".")
                .collect(java.util.stream.Collectors.joining());
    if (ResponseBudget.wordCount(complete) <= ResponseBudget.MAX_WORDS) {
      return complete;
    }
    for (var field : fields.entrySet()) {
      String prefix = " " + field.getKey() + ": ";
      String values = "";
      String beforeField = response;
      for (String value : field.getValue()) {
        String nextValues = values.isEmpty() ? value : values + ", " + value;
        String candidate = beforeField + prefix + nextValues + ".";
        if (ResponseBudget.wordCount(candidate + OMITTED) > ResponseBudget.MAX_WORDS) {
          return response + OMITTED;
        }
        values = nextValues;
        response = candidate;
      }
    }
    return response;
  }

  private static void semantic(
      Map<String, List<String>> fields, String label, SemanticQuery query) {
    if (query == null) {
      return;
    }
    add(fields, label + " genres", query.getGenres());
    add(fields, label + " topics", query.getTopics());
    add(fields, label + " author styles", query.getAuthors());
    add(fields, label + " narrator styles", query.getNarrators());
  }

  private static void exact(
      Map<String, List<String>> fields, String label, ExactCriteria criteria) {
    if (criteria == null) {
      return;
    }
    add(fields, label + " authors", criteria.getAuthors());
    add(fields, label + " narrators", criteria.getNarrators());
    add(fields, label + " language", criteria.getLanguage());
    add(fields, label + " duration", criteria.getDuration());
    add(fields, label + " publication year", criteria.getPublicationYear());
    add(fields, label + " source", criteria.getSource());
  }

  private static void add(Map<String, List<String>> fields, String label, String value) {
    if (value != null && !value.isBlank()) {
      add(fields, label, List.of(value));
    }
  }

  private static void add(Map<String, List<String>> fields, String label, List<String> values) {
    if (values != null) {
      var populated = values.stream().filter(value -> value != null && !value.isBlank()).toList();
      if (!populated.isEmpty()) {
        fields.put(label, populated);
      }
    }
  }
}
