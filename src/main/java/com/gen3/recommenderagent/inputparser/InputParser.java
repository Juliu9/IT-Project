package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.application.conversation.ConversationRules;
import com.gen3.recommenderagent.application.conversation.intent.UnknownHandler;
import com.gen3.recommenderagent.application.conversation.model.SessionRequest;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Validates input, chooses a parser, and creates the request consumed by the conversation. */
@Component
public class InputParser {
  public static final int MAX_REQUEST_CHARACTERS = 10000;
  private static final Logger LOGGER = LoggerFactory.getLogger(InputParser.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private final CommandParser commandParser;
  private final AiParser aiParser;
  private final com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers;

  /** Creates a parser with command recognition and AI interpretation kept separate. */
  public InputParser(
      CommandParser commandParser,
      AiParser aiParser,
      com.gen3.recommenderagent.application.conversation.HandlerRegistry handlers) {
    this.commandParser = commandParser;
    this.aiParser = aiParser;
    this.handlers = handlers;
  }

  /**
   * Extracts the user's stated meaning. This method deliberately performs no session mutation or
   * business decision; those occur at the session transition boundary.
   */
  public UserIntent parseUserIntent(String rawText, SessionContext context) {
    return parseUserIntent(rawText, () -> context);
  }

  /** Parses without session reference data for direct parser callers. */
  public UserIntent parseUserIntent(String rawText) {
    return parseUserIntent(rawText, SessionContext.EMPTY);
  }

  /** Converts parser output for the existing conversation executor. */
  public SessionRequest parse(String rawText) {
    return toSessionRequest(rawText, parseUserIntent(rawText));
  }

  /**
   * Defers session loading until the AI path actually needs it. Complete commands and invalid input
   * never touch session storage merely to obtain parser context.
   */
  public UserIntent parseUserIntent(
      String rawText, java.util.function.Supplier<SessionContext> contextSupplier) {
    long started = System.nanoTime();
    String mode = "VALIDATION";
    String outcome = "FAILURE";
    try {
      validateText(rawText);

      var countInput = CountInput.check(rawText);
      UserIntent parsed = commandParser.parse(rawText);
      if (parsed == null && countInput.invalid()) {
        parsed = commandParser.parse(countInput.modelText());
      }
      mode = parsed == null ? "AI" : "FAST_PATH";
      if (parsed == null) {
        SessionContext context =
            contextSupplier == null ? SessionContext.EMPTY : contextSupplier.get();
        parsed =
            context == null || context.isEmpty()
                ? aiParser.parse(countInput.modelText())
                : aiParser.parse(countInput.modelText(), context);
      }
      if (parsed == null) {
        throw new InvalidRequestException(
            "I couldn't determine what you'd like to do. Could you rephrase that?");
      }

      parsed.setInvalidBookCount(normalizeCount(rawText, countInput, parsed));

      outcome = "SUCCESS";
      return parsed;
    } finally {
      LOGGER.info(
          "parser.completed mode={} outcome={} durationMs={}",
          mode,
          outcome,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  /** Converts validated user meaning into the request consumed by conversation policy. */
  public SessionRequest toSessionRequest(String rawText, UserIntent parsed) {
    return toRequest(rawText, parsed, parsed.isInvalidBookCount());
  }

  /** Rejects missing or oversized messages before command parsing, AI calls, or session loading. */
  private void validateText(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      throw new InvalidRequestException("I didn't catch that. What would you like to listen to?");
    }

    if (rawText.length() > InputParser.MAX_REQUEST_CHARACTERS) {
      throw new InvalidRequestException(
          "Please keep your request to "
              + InputParser.MAX_REQUEST_CHARACTERS
              + " characters or fewer.");
    }
  }

  /**
   * Marks invalid quantities and selects count-related actions without treating subjects as counts.
   */
  private boolean normalizeCount(String rawText, CountInput.Check countInput, UserIntent parsed) {
    // A number retained as a stated subject is not also a quantity error.
    if (!countInput.invalid() && CountInput.describesZero(rawText, parsed)) {
      parsed.setInvalidBookCount(false);
      if (parsed.getBookCount() != null && parsed.getBookCount().signum() <= 0) {
        parsed.setBookCount(null);
      }
    }

    boolean invalidCount =
        !handlers
                .handler(parsed.getIntent() == null ? UnknownHandler.ACTION : parsed.getIntent())
                .retainsExplanationSelection()
            && (countInput.invalid()
                || parsed.isInvalidBookCount()
                || (parsed.getBookCount() != null && parsed.getBookCount().signum() <= 0));
    if (invalidCount
        && countInput.invalid()
        && !handlers
            .handler(parsed.getIntent() == null ? UnknownHandler.ACTION : parsed.getIntent())
            .retainsInvalidCountIntent()) {
      for (var handler : handlers.definitions()) {
        if (handler.recognizesInvalidCount(rawText, countInput.modelText())) {
          parsed.setIntent(handler.intents().iterator().next());
          break;
        }
      }
    }
    return invalidCount;
  }

  /**
   * Validates and maps parsed fields, recovering misplaced preference edits before normalization.
   */
  private SessionRequest toRequest(String rawText, UserIntent parsed, boolean invalidCount) {
    SessionRequest request = new SessionRequest();
    request.setIntent(parsed.getIntent() == null ? UnknownHandler.ACTION : parsed.getIntent());
    request.setShorter(parsed.isShorter());
    request.setLonger(parsed.isLonger());
    request.setExplanationSelection(parsed.getExplanationSelection());
    request.setPreferenceRemovalTerms(parsed.getPreferenceRemovalTerms());
    request.setPositivePreferenceRemovalOnly(parsed.isPositivePreferenceRemovalOnly());
    request.setSimilarToTitle(parsed.getSimilarToTitle());
    request.setSimilarToAuthor(parsed.getSimilarToAuthor());
    request.setSimilarToClarificationRequired(parsed.isSimilarToClarificationRequired());
    request.setPreferenceUpdates(parsed.getPreferenceUpdates());
    request.setPreferenceRemovals(parsed.getPreferenceRemovals());
    request.setReplacePreferenceFields(parsed.getReplacePreferenceFields());
    request.setReferenceRank(boundedInteger(parsed.getReferenceRank()));
    request.setReferenceTitle(parsed.getReferenceTitle());
    request.setPersonalised(parsed.isPersonalised());
    request.setPositiveSemanticQuery(parsed.getPositiveSemanticQuery());
    request.setNegativeSemanticQuery(parsed.getNegativeSemanticQuery());
    request.setMustInclude(parsed.getMustInclude());
    request.setMustNotInclude(parsed.getMustNotInclude());
    // Validate stated people before normalization can discard unsupported control-intent fields.
    handlers.handler(request.getIntent()).prepare(rawText, parsed, request);
    com.gen3.recommenderagent.application.conversation.model.PreferenceRequestMapper.fromRequest(
        request);
    if (request.getPreferenceUpdates() != null) {
      request.getPreferenceUpdates().copy();
    }
    if (request.getPreferenceRemovals() != null) {
      request.getPreferenceRemovals().copy();
    }
    ConversationRules.normalizeInput(request, handlers);
    request.setInvalidBookCount(invalidCount);
    request.setBookCount(
        invalidCount
                || handlers
                    .handler(
                        parsed.getIntent() == null ? UnknownHandler.ACTION : parsed.getIntent())
                    .retainsExplanationSelection()
            ? null
            : boundedInteger(parsed.getBookCount()));
    request.setRequestId(createUuidV7().toString());
    request.setRawText(rawText);
    return request;
  }

  /** Keeps oversized model numbers invalid without overflowing into a valid rank or count. */
  private Integer boundedInteger(BigInteger value) {
    return value == null
        ? null
        : value
            .max(BigInteger.valueOf(Integer.MIN_VALUE))
            .min(BigInteger.valueOf(Integer.MAX_VALUE))
            .intValue();
  }

  /** Creates a time-ordered version-7 request identifier using secure random bits. */
  private UUID createUuidV7() {
    long timestamp = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
    long randomA = RANDOM.nextLong() & 0x0FFFL;
    long randomB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;

    long mostSignificantBits = (timestamp << 16) | 0x7000L | randomA;
    long leastSignificantBits = 0x8000000000000000L | randomB;

    return new UUID(mostSignificantBits, leastSignificantBits);
  }
}
