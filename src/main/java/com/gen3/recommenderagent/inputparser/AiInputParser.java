package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.ai.AiProviderCalls;
import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.conversation.ConversationRules;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class AiInputParser implements InputParser {
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

  private static final Logger LOGGER = LoggerFactory.getLogger(AiInputParser.class);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final ChatClient chatClient;
  private final AiOperationObserver aiOperationObserver;

  @Autowired
  public AiInputParser(
      @Qualifier("inputParserChatClient") ChatClient chatClient,
      AiOperationObserver aiOperationObserver) {
    this.chatClient = chatClient;
    this.aiOperationObserver = aiOperationObserver;
  }

  public AiInputParser(ChatClient.Builder chatClientBuilder) {
    this(chatClientBuilder != null ? chatClientBuilder.build() : null, AiOperationObserver.noop());
  }

  @Override
  public SessionRequest parse(String rawText) {
    long started = System.nanoTime();
    String mode = "VALIDATION";
    String outcome = "FAILURE";
    try {
      if (rawText == null || rawText.isBlank()) {
        throw new InvalidRequestException("I didn't catch that. What would you like to listen to?");
      }

      if (rawText.length() > InputParser.MAX_REQUEST_CHARACTERS) {
        throw new InvalidRequestException(
            "Please keep your request to "
                + InputParser.MAX_REQUEST_CHARACTERS
                + " characters or fewer.");
      }

      Intent command = exactCommand(rawText);
      mode = command == null ? "AI" : "FAST_PATH";
      ParsedRequest parsed;
      if (command == null) {
        parsed = parseWithAi(rawText);
      } else {
        parsed = new ParsedRequest();
        parsed.setIntent(command);
      }

      SessionRequest request = new SessionRequest();
      request.setIntent(parsed.getIntent());
      request.setPreferenceUpdates(parsed.getPreferenceUpdates());
      request.setPreferenceRemovals(parsed.getPreferenceRemovals());
      request.setReplacePreferenceFields(parsed.getReplacePreferenceFields());
      request.setExplicitSearch(parsed.isExplicitSearch());
      request.setAddAuthors(parsed.isAddAuthors());
      request.setAddNarrators(parsed.isAddNarrators());
      request.setReferenceRank(boundedInteger(parsed.getReferenceRank()));
      request.setReferenceTitle(parsed.getReferenceTitle());
      request.setPersonalised(parsed.isPersonalised());
      request.setPositiveSemanticQuery(parsed.getPositiveSemanticQuery());
      request.setNegativeSemanticQuery(parsed.getNegativeSemanticQuery());
      ConversationRules.normalizeInput(request);
      request.setMustInclude(parsed.getMustInclude());
      request.setMustNotInclude(parsed.getMustNotInclude());
      request.setBookCount(boundedInteger(parsed.getBookCount()));
      request.setRequestId(createUuidV7().toString());
      request.setRawText(rawText);

      outcome = "SUCCESS";
      return request;
    } finally {
      LOGGER.info(
          "parser.completed mode={} outcome={} durationMs={}",
          mode,
          outcome,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  private Intent exactCommand(String rawText) {
    String command =
        rawText
            .strip()
            .toLowerCase(Locale.ROOT)
            .replace('’', '\'')
            .replace('‘', '\'')
            .replaceAll("\\b(?:please|kindly)\\b", " ")
            .replaceAll("(?U)\\s+", " ")
            .strip()
            .replaceFirst(
                "^(?:(?:hi|hello|hey|sorry|excuse me|just|can you|could you|would you)[\\s,!.?…–—:;]+)+",
                "")
            .replaceFirst(
                "(?:[\\s,!.?…–—:;]+(?:cheers|ta|many thanks|thanks|thank you(?: very much)?))+[\\s,!.?…–—:;]*$",
                "")
            .replaceFirst("[.!?,…]+$", "")
            .stripTrailing();
    return switch (command) {
      case "help",
          "show help",
          "what can you do",
          "what else can you do",
          "what do you do",
          "how do i use this",
          "how does this work",
          "what can i ask" ->
          Intent.HELP;
      case "more",
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
          "next page" ->
          Intent.MORE_RESULTS;
      case "repeat",
          "repeat that",
          "say that again",
          "say it again",
          "repeat yourself",
          "repeat your last response",
          "repeat the last response",
          "repeat your last answer" ->
          Intent.REPEAT;
      case "repeat the books",
          "repeat the recommendations",
          "repeat recommendations",
          "repeat the audiobooks",
          "repeat those books",
          "repeat those recommendations",
          "show those recommendations again",
          "show me the recommendations again",
          "show those books again" ->
          Intent.REPEAT_RECOMMENDATIONS;
      case "explain",
          "explain the recommendations",
          "explain these recommendations",
          "why these books",
          "why these recommendations",
          "why were these books recommended" ->
          Intent.EXPLAIN_RECOMMENDATION;
      case "clear history",
          "clear my history",
          "clear recommendation history",
          "clear my recommendation history" ->
          Intent.CLEAR_HISTORY;
      case "cancel",
          "cancel that",
          "cancel this",
          "cancel the request",
          "never mind",
          "nevermind",
          "stop",
          "forget it" ->
          Intent.CANCEL;
      default -> null;
    };
  }

  private ParsedRequest parseWithAi(String rawText) {
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
                        MORE_RESULTS=different books; REPEAT=latest action/answer; REPEAT_RECOMMENDATIONS=latest book page.
                        EXPLAIN_RECOMMENDATION=why recommended; HELP=capabilities/usage; CHANGE_COUNT=recommendation count;
                        UPDATE_PREFERENCES=edit tastes/constraints; CLEAR_HISTORY=clear history; CANCEL=abandon pending action;
                        UNKNOWN=unsupported/unrecognised, never a capability question.

                        Fields:
                        Only RECOMMENDATION/REFINE allow positiveSemanticQuery/negativeSemanticQuery or personalised=true.
                        Desired/avoided concepts, including genre/mood and liked authors/narrators, stay semantic;
                        only explicit exact author/narrator/language/duration/publication-year/source requirements use mustInclude/mustNotInclude.
                        Preserve contradictions. Author/narrator lists replace by default; addAuthors/addNarrators=true only for explicit additions.
                        personalised=true for taste/favourites/history or generic recommendations without criteria; never invent taste.
                        explicitSearch=true for explicit search commands, false for short taste replies.
                        bookCount=null unless a recommendation count is requested ("2 more" -> MORE_RESULTS, bookCount=2).
                        Explanation ordinals select ONE book: "the second one’s reason" -> EXPLAIN_RECOMMENDATION, referenceRank=2, bookCount=null.
                        Preserve stated rank (even invalid) and title in referenceRank/referenceTitle; both null=whole page.
                        References are explanation-only; bare ordinals are not explanation requests.

                        Filters:
                        Duration=digits plus hours/minutes; convert words/fractions ("under ten hours" -> "under 10 hours";
                        "one and a half hours" -> "1.5 hours"). Preserve under/over/at most/at least/exactly/between and both endpoints; vague duration=null.
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

  // Keep oversized model numbers invalid without overflowing into a valid rank or count.
  private Integer boundedInteger(BigInteger value) {
    return value == null
        ? null
        : value
            .max(BigInteger.valueOf(Integer.MIN_VALUE))
            .min(BigInteger.valueOf(Integer.MAX_VALUE))
            .intValue();
  }

  private UUID createUuidV7() {
    long timestamp = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
    long randomA = RANDOM.nextLong() & 0x0FFFL;
    long randomB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;

    long mostSignificantBits = (timestamp << 16) | 0x7000L | randomA;
    long leastSignificantBits = 0x8000000000000000L | randomB;

    return new UUID(mostSignificantBits, leastSignificantBits);
  }
}
