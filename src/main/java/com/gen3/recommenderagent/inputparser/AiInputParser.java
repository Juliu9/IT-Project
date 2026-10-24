package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AiInputParser implements InputParser {
  static final BeanOutputConverter<ParsedRequest> REQUEST_CONVERTER =
      new BeanOutputConverter<>(
          ParsedRequest.class,
          JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build());

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
    if (rawText == null || rawText.isBlank()) {
      throw new InvalidRequestException("I didn't catch that. What would you like to listen to?");
    }

    if (rawText.length() > InputParser.MAX_REQUEST_CHARACTERS) {
      throw new InvalidRequestException(
          "Please keep your request to "
              + InputParser.MAX_REQUEST_CHARACTERS
              + " characters or fewer.");
    }

    ResponseEntity<ChatResponse, ParsedRequest> aiResponse;
    try {
      aiResponse =
          aiOperationObserver.observe(
              "parse",
              () ->
                  this.chatClient
                      .prompt()
                      .system(
                          """
                        Extract only stated audiobook criteria; never invent values, report contradictions, or choose retrieval mode.
                        Intents: RECOMMENDATION=new search; REFINE=context-dependent change to an earlier search or taste reply;
                        Self-contained criteria ("fantasy", "fantasy under five hours") are RECOMMENDATION, even without a verb.
                        Newly stated exact author/narrator lists replace prior lists by default.
                        addAuthors/addNarrators=true only for explicit additions ("also", "add"), otherwise false.
                        Preserve stated include/exclude contradictions for clarification; do not silently repair the same message.
                        MORE_RESULTS=different books, including "2 more" with bookCount=2; REPEAT=repeat the latest request ("repeat that", "say that again"), including explanations/help.
                        REPEAT_RECOMMENDATIONS=repeat the latest book page ("repeat the books", "show those recommendations again");
                        EXPLAIN_RECOMMENDATION=why recommended;
                        CANCEL=cancel or never mind; CHANGE_COUNT, UPDATE_PREFERENCES, CLEAR_HISTORY, HELP, UNKNOWN=corresponding actions.
                        Desired/avoided concepts go in positiveSemanticQuery/negativeSemanticQuery. Liked authors/narrators
                        stay semantic unless exact matching is requested. mustInclude/mustNotInclude hold only exact author,
                        narrator, language, duration, publication-year or source requirements.
                        Duration: preserve under/over/at most/at least/exactly/between comparisons, decimals, both endpoints
                        and explicit hours/minutes ("under 1.5 hours", "between 2 and 3 hours"); never quantify vague duration.
                        Years: preserve after/before/at least/at most/exactly/between YYYY and YYYY, including both endpoints.
                        Relative publication years: recent/recently means the last 5 years. Put "recent", "past year", or
                        "last 4 years" (preserving the stated count, including words) in publicationYear, never a guessed
                        calendar year. Apply exclusions to mustNotInclude; keep duration comparisons separate.
                        bookCount=null unless explicitly requesting a recommendation count.
                        personalised=true for recommendations based on taste/favourites/history and generic requests without
                        stated criteria ("Recommend an audiobook", "Recommend an audiobook for me", "books I would like");
                        do not invent criteria. Only RECOMMENDATION/REFINE allow semantic queries or personalised=true;
                        leave semantic queries empty and personalised=false for other intents.
                        UPDATE_PREFERENCES uses preferenceUpdates and preferenceRemovals (SessionPreferences shape), never search semantic fields.
                        Likes add to positiveSemanticQuery; dislikes/avoidances add to negativeSemanticQuery and remove the matching positive value.
                        "No longer like romance" only removes positive romance; "avoid romance" also adds negative romance.
                        Explicit replacement uses replacePreferenceFields paths, e.g. positiveSemanticQuery.genres or mustInclude.authors;
                        replace only named fields, not the whole profile. Clearing a field uses its replacement path and no new value.
                        Empty update commands leave changes empty. For standalone taste replies also supply preferenceUpdates.
                        explicitSearch=true only for explicit search commands ("recommend horror books", "find books"), false for short taste replies ("horror").
                        For EXPLAIN_RECOMMENDATION ("why these books", "why the second book", "why Harry Potter"),
                        referenceRank=stated ordinal, even invalid (sixth=6); referenceTitle=stated title. Preserve both if supplied;
                        both null means whole page. These selectors belong only to EXPLAIN_RECOMMENDATION, never bookCount.
                        Bare ordinals ("fourth") are not explanation requests.
                        """)
                      .user(rawText)
                      .call()
                      .responseEntity(REQUEST_CONVERTER),
              response -> AiOperationObserver.AiUsage.from(response.response()));
    } catch (JacksonException exception) {
      throw new InvalidRequestException(
          "I couldn't understand that request. Please use whole numbers for book positions and counts, and keep numbers short.");
    }

    if (aiResponse.entity() == null) {
      throw new IllegalStateException("AI did not return a ParsedRequest");
    }

    ParsedRequest parsed = aiResponse.entity();

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
    ConversationState.normalizeInput(request);
    request.setMustInclude(parsed.getMustInclude());
    request.setMustNotInclude(parsed.getMustNotInclude());
    request.setBookCount(boundedInteger(parsed.getBookCount()));
    request.setRequestId(createUuidV7().toString());
    request.setRawText(rawText);

    return request;
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
