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
                        including "show me the same audiobook list you gave me earlier". A book-list reference takes precedence over REPEAT.
                        EXPLAIN_RECOMMENDATION=why recommended; HELP=capabilities/usage; CHANGE_COUNT=recommendation count;
                        UPDATE_PREFERENCES=edit tastes/constraints; CLEAR_HISTORY=clear history; CANCEL=abandon pending action;
                        UNKNOWN=unsupported/unrecognised, never a capability question.

                        Fields:
                        Only RECOMMENDATION/REFINE allow positiveSemanticQuery/negativeSemanticQuery or personalised=true.
                        Desired/avoided concepts, including genre/mood and liked authors/narrators, stay semantic;
                        Explicit author/narrator/language/duration/publication-year/source constraints use mustInclude/mustNotInclude.
                        Extract every stated constraint alongside semantic criteria; compact output must not omit supplied values.
                        Preserve contradictions. Author/narrator lists replace by default; addAuthors/addNarrators=true only for explicit additions.
                        personalised=true for taste/favourites/history or generic recommendations without criteria; never invent taste.
                        explicitSearch=true for explicit search commands, false for short taste replies.
                        bookCount=null unless a recommendation count is requested ("2 more" -> MORE_RESULTS, bookCount=2).
                        Explanation ordinals select ONE book: "the second one’s reason" -> EXPLAIN_RECOMMENDATION, referenceRank=2, bookCount=null.
                        Preserve only explicitly stated rank (even invalid) or title in referenceRank/referenceTitle.
                        "Why did I get these recommendations?" and other whole-page explanations omit both references; never default to rank 1.
                        References are explanation-only; bare ordinals are not explanation requests.

                        Filters:
                        Duration comparisons belong in mustInclude.duration (including "under"/"over", not just exact lengths); use digits plus hours/minutes; convert words/fractions ("under ten hours" -> "under 10 hours";
                        "one and a half hours" -> "1.5 hours"). Preserve under/over/at most/at least/exactly/between and both endpoints; vague duration=null.
                        "Recommend mystery audiobooks under ten hours" -> {"intent":"RECOMMENDATION","positiveSemanticQuery":{"genres":["mystery"]},"mustInclude":{"duration":"under 10 hours"}}.
                        Years=preserve after/before/at least/at most/exactly/between and both YYYY endpoints. recent/recently=last 5 years;
                        retain relative wording/counts ("recent", "past year", "last four years") in publicationYear, never guess years.
                        Keep exclusions and duration comparisons separate. "mystery, not too dark" -> positive genres=["mystery"], negative keywords=["dark"].

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
