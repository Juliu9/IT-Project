package com.gen3.recommenderagent.application.conversation.intent;

import static com.gen3.recommenderagent.inputparser.CommandValues.COUNT;
import static com.gen3.recommenderagent.inputparser.CommandValues.countValue;
import static com.gen3.recommenderagent.inputparser.CommandValues.intent;

import com.gen3.recommenderagent.application.conversation.model.ConversationDraft;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import com.gen3.recommenderagent.application.conversation.search.SearchContextResolver;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.inputparser.CountInput;
import com.gen3.recommenderagent.inputparser.UserIntent;
import java.math.BigInteger;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(16)
public final class MoreResultsHandler extends SearchContextResolver {
  public static final ConversationAction ACTION = ConversationAction.of("MORE_RESULTS");

  @Override
  public Set<ConversationAction> intents() {
    return Set.of(ACTION);
  }

  @Override
  public String instructions() {
    return """
      MORE_RESULTS=different books.
      Use one intent. "more and make it three at a time" -> MORE_RESULTS with bookCount=3 for that request; only a separate count-setting request is CHANGE_COUNT.
      """;
  }

  @Override
  public Set<String> commands() {
    return Set.of(
        "more",
        "some more",
        "more recommendations",
        "more books",
        "more audiobooks",
        "show me more",
        "give me more",
        "can i have more",
        "can i have some more",
        "could i have more",
        "could i have some more",
        "next",
        "next batch",
        "next page");
  }

  @Override
  public boolean isSearchAction() {
    return true;
  }

  private static final Pattern TEMPORARY_MORE_COUNT =
      Pattern.compile(
          "^(.+?)(?:, (?:and )?| and )(?:make (?:it|that) )?("
              + COUNT
              + ")(?: books?| audiobooks?| recommendations?)? (?:at a time|per page|per request)$");

  private static final Pattern COUNTED_MORE =
      Pattern.compile(
          "(?:show me |give me |can i have |could i have |i'd like |i would like |recommend )?"
              + "(?:("
              + COUNT
              + ") more|another(?: ("
              + COUNT
              + "))?)"
              + "(?: books?| audiobooks?| recommendations?)?");

  @Override
  public UserIntent recognize(String command) {
    var temporaryCount = TEMPORARY_MORE_COUNT.matcher(command);
    if (temporaryCount.matches()) {
      var parsed = recognize(temporaryCount.group(1));
      if (parsed != null) {
        parsed.setBookCount(countValue(temporaryCount.group(2)));
        return parsed;
      }
    }
    var exact = commands().contains(command);
    if (exact) {
      return intent(intents().iterator().next());
    }
    var countedMoreResult = countedMore(command);
    if (countedMoreResult != null) {
      return countedMoreResult;
    }
    return null;
  }

  /** Recognizes a request for another page with an explicit recommendation count. */
  private UserIntent countedMore(String command) {
    var countedMore = COUNTED_MORE.matcher(command);
    if (countedMore.matches()) {
      var parsed = intent(ACTION);
      String count = countedMore.group(1) == null ? countedMore.group(2) : countedMore.group(1);
      parsed.setBookCount(count == null ? BigInteger.ONE : countValue(count));
      return parsed;
    }
    return null;
  }

  @Override
  public String emptyResponse(com.gen3.recommenderagent.domain.conversation.ConversationTurn turn) {
    return "There are no more audiobooks matching your current search. Would you like to try a different author, genre, or filter?";
  }

  @Override
  public ConversationDraft resolve(Session session, ConversationDraft effective) {
    if (session == null) {
      return effective;
    }
    // Check before preference handling: MORE cannot invent a first search.
    if (session.getActiveSearch().getSearch() == null) {
      effective.setBookCount(resolveBookCount(effective, session));
      effective.setSearchClarificationRequired(true);
      session.setPendingRefinementRequest(ConversationDraft.toStoredTurn(effective));
      return effective;
    }
    return super.resolve(session, effective);
  }

  @Override
  public boolean inheritsSearch() {
    return true;
  }

  @Override
  public boolean reusesSearchSnapshot() {
    return true;
  }

  @Override
  protected boolean acceptsSearchEdits() {
    return false;
  }

  @Override
  public boolean invalidatesBatch(SessionRequest request) {
    var criteria = new ConversationDraft();
    if (request != null) {
      criteria.setMustInclude(request.getMustInclude());
      criteria.setMustNotInclude(request.getMustNotInclude());
    }
    return com.gen3.recommenderagent.application.conversation.ConversationRules.hasSearchCriteria(
        criteria);
  }

  @Override
  public boolean retainsInvalidCountIntent() {
    return true;
  }

  @Override
  public boolean recognizesInvalidCount(String rawText, String modelText) {
    return CountInput.more(rawText);
  }

  @Override
  public boolean requiresSemanticPreferences(
      ConversationDraft request, com.gen3.recommenderagent.embedding.SemanticQueryVectors vectors) {
    return RecommendationHandler.needsSemanticPreferences(request, vectors);
  }

  @Override
  public String exhaustedResponse(
      com.gen3.recommenderagent.domain.conversation.ConversationTurn turn) {
    return turn.isNoMoreRecommendations() ? emptyResponse(turn) : null;
  }
}
