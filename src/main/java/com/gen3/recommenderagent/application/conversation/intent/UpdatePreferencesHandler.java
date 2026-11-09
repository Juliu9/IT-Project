package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.application.conversation.ConversationRules.hasTerms;
import static com.gen3.recommenderagent.application.conversation.ConversationRules.normalizeInput;
import static com.gen3.recommenderagent.inputparser.CommandValues.intent;

import com.gen3.recommenderagent.application.conversation.ConversationHandler;
import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.PreferenceRequestMapper;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.conversation.ConversationOutcome;
import com.gen3.recommenderagent.domain.conversation.ConversationQuestion;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.search.SemanticQuery;
import com.gen3.recommenderagent.domain.session.LastOutcome;
import com.gen3.recommenderagent.domain.session.PendingQuestion;
import com.gen3.recommenderagent.domain.session.PreferenceLimitException;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import com.gen3.recommenderagent.inputparser.ConversationText;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(10)
public final class UpdatePreferencesHandler implements ConversationHandler {
  private static final Pattern SET_PREFERENCES =
      Pattern.compile("^(?:update|change|set) (?:my |the )?(?:session )?preferences to .+");
  private static final String PREFERRED_GENRES = "positiveSemanticQuery.genres";

  public static final ConversationAction ACTION = ConversationAction.of("UPDATE_PREFERENCES");
  public static final ConversationOutcome PREFERENCE_LIMIT_EXCEEDED =
      ConversationOutcome.of("PREFERENCE_LIMIT_EXCEEDED");
  public static final ConversationQuestion PREFERENCE_UPDATE_CLARIFICATION =
      ConversationQuestion.of(
          "PREFERENCE_UPDATE_CLARIFICATION",
          ConversationQuestion.Context.PREFERENCE_UPDATE,
          com.gen3.recommenderagent.domain.session.PendingQuestion.Preference::new);

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      UPDATE_PREFERENCES=an explicit edit to saved session tastes or constraints, with save/remember/preference wording. Replacing or clearing a search criterion alone is REFINE, not UPDATE_PREFERENCES, even without a preceding search.
      Saved session preference edits:
      UPDATE_PREFERENCES uses preferenceUpdates, preferenceRemovals, preferenceRemovalTerms, and replacePreferenceFields—not top-level search fields.
      Inside preferenceUpdates, classify requested and excluded genres, themes, moods, plots, and traits with the same semantic field rules, nested under preferenceUpdates rather than top-level search fields.
      Preserve every stated edit, including exact constraints and semantic preferences. Likes add positives. Dislikes/avoidances add negatives and remove matching positives. Removing a like does not invent a dislike.
      Avoiding/disliking X means ADD X to preferenceUpdates.negativeSemanticQuery, not preferenceRemovals or preferenceRemovalTerms. Those removal fields delete saved values; they do not express content to avoid. Never interpret "avoid romance" as deleting an existing avoidance.
      "update my preferences to avoid romance" -> {"intent":"UPDATE_PREFERENCES","preferenceUpdates":{"negativeSemanticQuery":{"genres":["romance"]}},"preferenceRemovals":{"positiveSemanticQuery":{"genres":["romance"]}}}.
      "Remember that I dislike tragic endings" -> {"intent":"UPDATE_PREFERENCES","preferenceUpdates":{"negativeSemanticQuery":{"topics":["tragic endings"]}},"preferenceRemovals":{"positiveSemanticQuery":{"topics":["tragic endings"]}}}.
      "Stop avoiding romance in my preferences" -> {"intent":"UPDATE_PREFERENCES","preferenceRemovals":{"negativeSemanticQuery":{"genres":["romance"]}}}. This removes an avoidance without adding a positive preference.
      "Remove X from my preferences" -> preferenceRemovalTerms=["X"]. "I no longer like X" also sets positivePreferenceRemovalOnly=true so negative preferences are not removed.
      "Set my preferences to English-language mystery audiobooks under ten hours, and avoid horror. Do not recommend anything yet." -> {"intent":"UPDATE_PREFERENCES","preferenceUpdates":{"mustInclude":{"language":"English","duration":"under 10 hours"},"positiveSemanticQuery":{"genres":["mystery"]},"negativeSemanticQuery":{"genres":["horror"]}},"replacePreferenceFields":["positiveSemanticQuery.genres"]}.
      "Update/change/set my preferences to ..." replaces the stated positive genres and leaves unrelated fields untouched. "Add X to my preferences" adds X without replacing genres.
      Explicit preference replacement/clearing uses only the named replacePreferenceFields path, for example positiveSemanticQuery.genres or mustInclude.authors; clearing supplies no value. Empty edit commands leave edit fields empty so Java can ask for clarification.
      A standalone RECOMMENDATION/REFINE taste uses top-level search criteria; omit preferenceUpdates. Java may later interpret those criteria as an answer to a pending preference question.
      """;
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
    return "Thanks, I've updated your preferences for this session.";
  }

  @Override
  public void validate(
      Session session,
      ConversationDraft result,
      SessionRequest original,
      com.gen3.recommenderagent.retrieval.plan.ExactFilterPolicy filters,
      com.gen3.recommenderagent.application.recommendation.SimilarBookResolver similarBooks,
      com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers) {
    if (result.getPendingQuestion() == PREFERENCE_UPDATE_CLARIFICATION) {
      return;
    }
    var proposed = PreferenceRequestMapper.toRequest(result.getPreferenceUpdates());
    var updates =
        original != null && original.getPreferenceUpdates() != null
            ? PreferenceRequestMapper.toRequest(original.getPreferenceUpdates())
            : original == null
                ? new SessionRequest()
                : PreferenceRequestMapper.toRequest(PreferenceRequestMapper.fromRequest(original));
    filters.resolveFilterCorrections(proposed, updates);
    result.getPreferenceUpdates().setMustInclude(proposed.getMustInclude());
    result.getPreferenceUpdates().setMustNotInclude(proposed.getMustNotInclude());
  }

  @Override
  public UserIntent recognize(String command) {
    var exact = commands().contains(command);
    if (exact) {
      return intent(intents().iterator().next());
    }
    var removal =
        Pattern.compile(
                "^(?:remove (.+) from (?:my |the )?(?:session )?preferences|i no longer like (.+))$")
            .matcher(command);
    return removal.matches() ? preferenceRemoval(removal) : null;
  }

  /** Recognizes explicit removal of saved preference terms. */
  private UserIntent preferenceRemoval(java.util.regex.Matcher removal) {
    var parsed = intent(ACTION);
    String term =
        (removal.group(1) == null ? removal.group(2) : removal.group(1))
            .replaceFirst("^(?:the )?(?:author|narrator|genre|topic|keyword) ", "");
    if (term.contains(" and ")) {
      return null;
    }
    parsed.setPreferenceRemovalTerms(java.util.List.of(term));
    parsed.setPositivePreferenceRemovalOnly(removal.group(2) != null);
    return parsed;
  }

  @Override
  public void prepare(String rawText, UserIntent parsed, SessionRequest request) {
    if (request.getPreferenceUpdates() == null) {
      var stated = PreferenceRequestMapper.fromRequest(request);
      if (stated.hasValues()) {
        request.setPreferenceUpdates(stated);
      }
    }
    if (SET_PREFERENCES.matcher(ConversationText.normalizeCommand(rawText)).matches()) {
      replaceStatedGenres(request);
    }
  }

  /** Recovers misplaced genres and marks a stated preferred genre as a field replacement. */
  private void replaceStatedGenres(SessionRequest request) {
    var updates = request.getPreferenceUpdates();
    if (updates == null) {
      return;
    }
    var preferred = updates.getPositiveSemanticQuery();
    if (preferred == null || !hasTerms(preferred.getGenres())) {
      var search = request.getPositiveSemanticQuery();
      if (search == null || !hasTerms(search.getGenres())) {
        return;
      }
      var genres = new SemanticQuery();
      genres.setGenres(search.getGenres());
      var recovered = new SessionPreferences();
      recovered.setPositiveSemanticQuery(genres);
      request.setPreferenceUpdates(updates.updated(recovered, null, List.of()));
    }
    var fields = request.getReplacePreferenceFields();
    if (fields == null || !fields.contains(PREFERRED_GENRES)) {
      var replacements = fields == null ? new ArrayList<String>() : new ArrayList<>(fields);
      replacements.add(PREFERRED_GENRES);
      request.setReplacePreferenceFields(replacements);
    }
  }

  @Override
  public ConversationTurn recoverFailure(
      Session session,
      ConversationAction action,
      PendingQuestion pending,
      RuntimeException exception) {
    if (!(exception instanceof PreferenceLimitException)
        || (action != ACTION && !(pending instanceof PendingQuestion.Preference))) {
      return null;
    }
    session.setPendingQuestion(pending);
    var rejected = new ConversationTurn();
    rejected.setIntent(ACTION);
    rejected.setOutcome(PREFERENCE_LIMIT_EXCEEDED);
    rejected.setRecommendations(List.of());
    rejected.setRequestId(UUID.randomUUID().toString());
    rejected.setCreatedAt(java.time.Instant.now());
    session.setLastOutcome(new LastOutcome(rejected, rejected.getOutcome()));
    return rejected;
  }

  /** Calculates a saved-preference edit or asks for the missing update without retrieving books. */
  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (!effective.getPreferenceRemovalTerms().isEmpty()) {
      var preferences =
          session.getPreferences() == null ? new SessionPreferences() : session.getPreferences();
      effective.setPreferenceUpdates(
          preferences.withoutTerms(
              effective.getPreferenceRemovalTerms(), effective.isPositivePreferenceRemovalOnly()));
      return effective;
    }
    SessionPreferences updates = effective.getPreferenceUpdates();
    if (updates == null) {
      updates = PreferenceRequestMapper.fromRequest(effective);
    }
    boolean changes =
        updates.hasValues()
            || (effective.getPreferenceRemovals() != null
                && effective.getPreferenceRemovals().hasValues())
            || (effective.getReplacePreferenceFields() != null
                && !effective.getReplacePreferenceFields().isEmpty());
    effective.setMustInclude(null);
    effective.setMustNotInclude(null);
    normalizeInput(effective, this);
    if (!changes) {
      effective.setPendingQuestion(PREFERENCE_UPDATE_CLARIFICATION);
    } else {
      effective.setPreferenceUpdates(
          session.updatedPreferences(
              updates, effective.getPreferenceRemovals(), effective.getReplacePreferenceFields()));
    }
    return effective;
  }

  /** Saves a resolved preference edit and leaves the current recommendation batch intact. */
  public void finish(Session session, ConversationDraft request, SessionRequest original) {
    boolean clarification = request.getPendingQuestion() == PREFERENCE_UPDATE_CLARIFICATION;
    if (!clarification) {
      session.setPreferences(request.getPreferenceUpdates().copy());
    }
    request.setRecommendations(List.of());
  }

  @Override
  public String outcomeResponse(ConversationTurn turn) {
    if (turn.getOutcome() == null) {
      return null;
    }
    if (turn.getOutcome() == PREFERENCE_LIMIT_EXCEEDED) {
      return "I can save up to "
          + SessionPreferences.MAX_TERMS_PER_SEMANTIC_FIELD
          + " values in each preference category. I haven't changed your preferences. "
          + "Please remove a saved preference or replace that category.";
    }
    return null;
  }

  /** Maps a reply to a pending saved-preference update. */
  @Override
  public ConversationDraft resolvePendingReply(
      Session session, ConversationDraft input, String reply) {
    if (session.isAwaitingPreferenceUpdate()
        && !input.startsNewSearch()
        && (input.getIntent() == RecommendationHandler.ACTION
            || input.getIntent() == RefineHandler.ACTION
            || input.getIntent() == UnknownHandler.ACTION)) {
      var stated = PreferenceRequestMapper.fromRequest(input);
      if (stated.hasValues()) {
        input.setIntent(ACTION);
        if (input.getPreferenceUpdates() == null) {
          input.setPreferenceUpdates(stated);
        }
      }
    }
    return null;
  }

  @Override
  public boolean keepsUnansweredQuestion(ConversationQuestion question) {
    return questions().contains(question);
  }

  @Override
  public Set<ConversationQuestion> questions() {
    return Set.of(PREFERENCE_UPDATE_CLARIFICATION);
  }

  @Override
  public String questionResponse(
      ConversationQuestion question, ConversationTurn currentRequest, int labelWords) {
    if (question == PREFERENCE_UPDATE_CLARIFICATION) {
      return "What would you like to change about your preferences?";
    }
    return null;
  }

  @Override
  public boolean questionActive(ConversationQuestion question, ConversationTurn turn) {
    var action = turn.getResponseAction() == null ? turn.getIntent() : turn.getResponseAction();
    return action == ACTION || turn.getIntent() == RepeatHandler.ACTION;
  }

  @Override
  public void retainQuestion(Session session, ConversationDraft request) {
    if (session.getPendingQuestion() instanceof PendingQuestion.Preference pending) {
      pending.setRequest(ConversationDraft.toStoredTurn(request));
    }
  }

  @Override
  public Set<ConversationOutcome> outcomes() {
    return Set.of(PREFERENCE_LIMIT_EXCEEDED);
  }
}
