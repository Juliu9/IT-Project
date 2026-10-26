package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.ai.AiProviderCalls;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AiParser {
  static final BeanOutputConverter<ParsedRequest> REQUEST_CONVERTER =
      new BeanOutputConverter<>(
          ParsedRequest.class,
          JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build()) {
        @Override
        protected String generateSchema() {
          var schema = (ObjectNode) getJsonMapper().readTree(super.generateSchema());
          optionalFields(schema);
          schema.putArray("required").add("intent");
          return getJsonMapper().writeValueAsString(schema);
        }
      };

  // Converter format guidance must allow omitted defaults, including nested criteria fields.
  private static void optionalFields(JsonNode schema) {
    if (schema instanceof ObjectNode object) {
      object.remove("required");
    }
    for (JsonNode child : schema) {
      optionalFields(child);
    }
  }

  private final ChatClient chatClient;
  private final AiOperationObserver aiOperationObserver;

  @Autowired
  public AiParser(
      @Qualifier("inputParserChatClient") ChatClient chatClient,
      AiOperationObserver aiOperationObserver) {
    this.chatClient = chatClient;
    this.aiOperationObserver = aiOperationObserver;
  }

  public AiParser(ChatClient.Builder chatClientBuilder) {
    this(chatClientBuilder != null ? chatClientBuilder.build() : null, AiOperationObserver.noop());
  }

  public ParsedRequest parse(String rawText) {
    ResponseEntity<ChatResponse, ParsedRequest> aiResponse;
    try {
      aiResponse =
          AiProviderCalls.call(
              () ->
                  aiOperationObserver.observe(
                      "parse",
                      () ->
                          this.chatClient
                              .prompt()
                              .system(
                                  """
                        Extract stated audiobook criteria as compact JSON. Require intent; omit nulls, empty arrays/objects/strings and false flags.
                        Omitted fields use Java defaults. Never invent values, resolve contradictions, or choose retrieval mode.

                        Intents:
                        RECOMMENDATION=new/self-contained criteria (even without a verb); REFINE=context-dependent search change/taste reply.
                        MORE_RESULTS=different books; REPEAT=latest action/answer; REPEAT_RECOMMENDATIONS=the same previous books/list,
                        A same-books/list reference takes precedence over REPEAT; "repeat your last explanation" is REPEAT, not a new explanation.
                        EXPLAIN_RECOMMENDATION=why recommended; HELP=capabilities/usage; CHANGE_COUNT=recommendation count;
                        REFINE changes the current results; UPDATE_PREFERENCES explicitly edits saved tastes/constraints.
                        "give me some books" -> RECOMMENDATION, personalised=true, explicitSearch=true; no invented criteria.
                        Comparative recency ("more recent", "I want books from more recent times") -> REFINE, mustInclude.publicationYear="more recent"; Java compares displayed years, never MORE_RESULTS or a guessed calendar cutoff.
                        "make them shorter" -> REFINE, shorter=true, explicitSearch=true; no invented genre or hour limit.
                        shorter=true only compares previous results' runtimes without an explicit numeric limit; Java uses their durations.
                        shorter refers to runtime, not response length. Never invent genres or numeric limits for an underspecified refinement.
                        RESET_PREFERENCES=reset all session preferences and recommendation count to defaults; clearing one field is UPDATE_PREFERENCES.
                        CLEAR_HISTORY=clear history; CANCEL=abandon pending action;
                        UNKNOWN=unsupported/unrecognised, never a capability question.

                        Fields:
                        "books similar to The Hunger Games" -> RECOMMENDATION, similarToTitle="The Hunger Games".
                        For "similar to TITLE by AUTHOR", extract both similarToTitle and similarToAuthor;
                        that author identifies the reference, not a filter on recommended authors.
                        Do not invent themes or embed the title as semantic criteria; only additional requested tastes go in semantic queries.
                        A single named title always goes to catalogue lookup, even if it also names a series; never infer series scope from your knowledge.
                        similarToClarificationRequired=true ONLY when the user explicitly requests a series/trilogy/saga as a whole or names multiple reference books.
                        "a book similar to the hunger games" -> similarToTitle="The Hunger Games", omit similarToClarificationRequired.
                        "books similar to the Hunger Games series" -> similarToClarificationRequired=true.
                        Preserve extra constraints and explicit personalisation; a reference alone is not personalised=true.
                        Only RECOMMENDATION/REFINE allow positiveSemanticQuery/negativeSemanticQuery or personalised=true.
                        Genres, moods, plots and character traits belong in semantic genres/topics/keywords, never author/narrator fields.
                        Author/narrator fields contain only names explicitly identified as writers/narrators; a protagonist is a story character.
                        "I want a book by Suzanne Collins" -> mustInclude.authors=["Suzanne Collins"] (mandatory author).
                        "I want books similar to Suzanne Collins style" -> positiveSemanticQuery.authors=["Suzanne Collins"], personalised=false; never an author filter or similarToTitle.
                        Apply the same distinction to narrators: by/narrated-by requirements use mustInclude/mustNotInclude; liked/similar styles use positive/negativeSemanticQuery names, without invented themes.
                        Explicit language/duration/publication-year/source constraints also use mustInclude/mustNotInclude.
                        Extract every stated constraint alongside semantic criteria; compact output must not omit supplied values.
                        Preserve contradictions. Author/narrator lists replace by default; addAuthors/addNarrators=true only for explicit additions.
                        personalised=true only for explicit use of the user's preferences/favourites/history or generic requests without criteria. Author/narrator style similarity alone is personalised=false; "for me based on my preferences" can make it true. Never invent taste.
                        Personalisation never removes stated criteria: "books I would like by Suzanne Collins" -> personalised=true, mustInclude.authors=["Suzanne Collins"].
                        explicitSearch=true for explicit new-search commands; bare taste replies like "horror" are RECOMMENDATION/REFINE with explicitSearch=false, never UPDATE_PREFERENCES.
                        "Only three at a time" / "3 per page" / "change count to three books" -> CHANGE_COUNT, bookCount=3, never UPDATE_PREFERENCES.
                        bookCount=null unless a recommendation count is requested ("2 more" -> MORE_RESULTS, bookCount=2).
                        Explanation ordinals select ONE book: "the second one’s reason" -> referenceRank=2; "why the sixth book" -> referenceRank=6; intent=EXPLAIN_RECOMMENDATION, omit bookCount.
                        "Explain two more" and "explain book two more" mean explain rank 2 in more detail, not two books.
                        Preserve only explicitly stated rank (even invalid) or title in referenceRank/referenceTitle.
                        "Why did I get these recommendations?" and other whole-page explanations omit both references; never default to rank 1.
                        References are explanation-only; bare ordinals are not explanation requests.

                        Filters:
                        Duration comparisons belong in mustInclude.duration (including "under"/"over", not just exact lengths); use digits plus hours/minutes; convert words/fractions ("under ten hours" -> "under 10 hours";
                        "one and a half hours" -> "1.5 hours"). Preserve under/over/at most/at least/exactly/between and both endpoints; vague duration=null.
                        Years=preserve after/before/at least/at most/exactly/between and both endpoints; decades include all ten years ("1950 to the 2000s" -> "between 1950 and 2009"). recent/recently=last 5 years;
                        "books from around 2000" / "books from the period 2000" -> publicationYear="around 2000" / "period 2000"; Java applies inclusive +/-5 years. Preserve these approximate phrases, never extract only the exact year.
                        retain relative wording/counts ("recent", "past year", "last four years") in publicationYear, never guess years.
                        Keep exclusions and duration comparisons separate. "mystery, not too dark" -> positive genres=["mystery"], negative keywords=["dark"].

                        Extraction examples (copy only values stated in the actual request):
                        "Recommend fantasy audiobooks with a strong female protagonist" ->
                        {"intent":"RECOMMENDATION","explicitSearch":true,"positiveSemanticQuery":{"genres":["fantasy"],"keywords":["strong female protagonist"]}}
                        "Recommend mystery audiobooks under five hours" ->
                        {"intent":"RECOMMENDATION","explicitSearch":true,"positiveSemanticQuery":{"genres":["mystery"]},"mustInclude":{"duration":"under 5 hours"}}
                        "Books similar to The Hunger Games by Suzanne Collins under ten hours" ->
                        {"intent":"RECOMMENDATION","explicitSearch":true,"similarToTitle":"The Hunger Games","similarToAuthor":"Suzanne Collins","mustInclude":{"duration":"under 10 hours"}}

                        Preferences:
                        UPDATE_PREFERENCES uses preferenceUpdates/preferenceRemovals (SessionPreferences), not search fields.
                        Likes add positives; dislikes/avoidances add negatives and remove matching positives; "no longer like" only removes positives.
                        Explicit replacement/clearing uses replacePreferenceFields paths, e.g. positiveSemanticQuery.genres or mustInclude.authors;
                        replace only named fields; clearing adds no values. Empty commands leave edits empty.
                        Standalone RECOMMENDATION/REFINE taste replies use search criteria only; omit preferenceUpdates.
                        Java derives preference edits from these criteria when needed. Explicit UPDATE_PREFERENCES still uses the edit fields.
                        """)
                              .user(rawText)
                              .call()
                              .responseEntity(REQUEST_CONVERTER),
                      response -> AiOperationObserver.AiUsage.from(response.response())));
    } catch (JacksonException exception) {
      throw new InvalidRequestException(
          "I couldn't understand that request. Please use whole numbers for book positions and counts, and keep numbers short.");
    }

    if (aiResponse.entity() == null) {
      throw new IllegalStateException("AI did not return a ParsedRequest");
    }

    return aiResponse.entity();
  }
}
