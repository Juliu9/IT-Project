package com.gen3.recommenderagent.application.conversation.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.search.ExactCriteria;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.response.ResponseBudget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(1)
public final class ExplainPreferencesHandler implements ConversationHandler {
  public static final ConversationAction ACTION = ConversationAction.of("EXPLAIN_PREFERENCES");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      EXPLAIN_PREFERENCES=read or explain the current saved session preferences, without changing them. Questions such as "What are my current session preferences?" use this intent, not HELP or UPDATE_PREFERENCES. Do not extract preference updates or search criteria for this intent.
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "what are my current session preferences",
        "what are my session preferences",
        "what are my current preferences",
        "what are my preferences",
        "show my preferences",
        "show my session preferences",
        "explain my preferences",
        "explain preferences",
        "show preferences",
        "list my preferences");
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft input) {
    return input;
  }

  @Override
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    request.setRecommendations(List.of());
    request.setSessionPreferenceSnapshot(
        session.getPreferences() == null ? null : session.getPreferences().copy());
    request.setBookCount(session.getBookCount());
  }

  @Override
  public boolean retainsHistory() {
    return true;
  }

  @Override
  public boolean narratesBooks() {
    return false;
  }

  @Override
  public String response(ConversationTurn turn) {
    if (turn.getOutcome() != null) {
      return null;
    }
    return render(turn);
  }

  @Override
  public QuestionPolicy questionPolicy() {
    return QuestionPolicy.PRESERVE;
  }

  @Override
  public boolean ownsResponseBudget() {
    return true;
  }

  private static final String CLOSING = " You can ask me to change any of these whenever you like.";
  private static final String OMITTED = " I left out a few more preferences to keep this brief.";

  /**
   * Reports the saved preference snapshot, preserving exact constraints within the response budget.
   */
  public static String render(ConversationTurn turn) {
    Map<String, List<String>> constraints = new LinkedHashMap<>();
    Map<String, List<String>> fields = new LinkedHashMap<>();
    Map<String, List<String>> tastes = new LinkedHashMap<>();
    var preferences = turn.getSessionPreferenceSnapshot();
    if (preferences != null) {
      semantic(fields, tastes, "Preferred", preferences.getPositiveSemanticQuery());
      semantic(fields, tastes, "Avoided", preferences.getNegativeSemanticQuery());
      exact(constraints, "Required", preferences.getMustInclude());
      exact(constraints, "Excluded", preferences.getMustNotInclude());
    }
    fields.putAll(tastes);
    String response =
        fields.isEmpty() && constraints.isEmpty()
            ? "You haven't saved any preferences for this session yet."
            : "Here are your preferences for this session.";
    response += " I'll give you " + turn.getBookCount() + " recommendations at a time.";
    response += sentences(constraints);
    String complete = response + sentences(fields) + CLOSING;
    if (ResponseBudget.wordCount(complete) <= ResponseBudget.MAX_WORDS) {
      return complete;
    }
    for (var field : fields.entrySet()) {
      String prefix = " " + field.getKey() + " ";
      String values = "";
      String beforeField = response;
      for (String value : field.getValue()) {
        String nextValues = values.isEmpty() ? value : values + ", " + value;
        String candidate = beforeField + prefix + nextValues + ".";
        if (ResponseBudget.wordCount(candidate + OMITTED + CLOSING) > ResponseBudget.MAX_WORDS) {
          return response + OMITTED + CLOSING;
        }
        values = nextValues;
        response = candidate;
      }
    }
    return response + CLOSING;
  }

  /** Builds preference sentences in constraint, style, genre, and topic priority order. */
  private static String sentences(Map<String, List<String>> fields) {
    return fields.entrySet().stream()
        .map(field -> " " + field.getKey() + " " + String.join(", ", field.getValue()) + ".")
        .collect(java.util.stream.Collectors.joining());
  }

  /** Formats semantic preference values without splitting whole saved terms. */
  private static void semantic(
      Map<String, List<String>> fields,
      Map<String, List<String>> tastes,
      String label,
      SemanticQuery query) {
    if (query == null) {
      return;
    }
    String lead = label.equals("Preferred") ? "You enjoy" : "You'd rather avoid";
    add(tastes, lead + " genres like", query.getGenres());
    add(tastes, lead + " stories about", query.getTopics());
    add(fields, lead + " author styles like", query.getAuthors());
    add(fields, lead + " narrator styles like", query.getNarrators());
  }

  /** Formats required or excluded exact constraints as a single joined sentence. */
  private static void exact(
      Map<String, List<String>> fields, String label, ExactCriteria criteria) {
    if (criteria == null) {
      return;
    }
    Map<String, List<String>> parts = new LinkedHashMap<>();
    add(parts, "by", criteria.getAuthors());
    add(parts, "narrated by", criteria.getNarrators());
    add(parts, "in", criteria.getLanguage());
    add(parts, "", criteria.getDuration());
    add(parts, "published", criteria.getPublicationYear());
    add(parts, "from", criteria.getSource());
    var clauses =
        parts.entrySet().stream()
            .map(part -> (part.getKey() + " " + String.join(", ", part.getValue())).strip())
            .toList();
    if (!clauses.isEmpty()) {
      boolean required = label.equals("Required");
      add(
          fields,
          required ? "You want books" : "You don't want books",
          joinClauses(clauses, required ? "and" : "or"));
    }
  }

  /** Joins preference clauses using the supplied conjunction. */
  private static String joinClauses(List<String> clauses, String conjunction) {
    if (clauses.size() == 1) {
      return clauses.getFirst();
    }
    String separator = clauses.size() == 2 ? " " : ", ";
    return String.join(", ", clauses.subList(0, clauses.size() - 1))
        + separator
        + conjunction
        + " "
        + clauses.getLast();
  }

  /** Adds a preference clause only when it contains usable values. */
  private static void add(Map<String, List<String>> fields, String label, String value) {
    if (value != null && !value.isBlank()) {
      add(fields, label, List.of(value));
    }
  }

  /** Adds a preference clause only when it contains usable values. */
  private static void add(Map<String, List<String>> fields, String label, List<String> values) {
    if (values != null) {
      var populated = values.stream().filter(value -> value != null && !value.isBlank()).toList();
      if (!populated.isEmpty()) {
        fields.put(label, populated);
      }
    }
  }
}
