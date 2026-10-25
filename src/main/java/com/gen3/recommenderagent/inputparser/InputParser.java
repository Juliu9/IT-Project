package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.conversation.ConversationRules;
import com.gen3.recommenderagent.domain.session.SessionRequest;
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

  public InputParser(CommandParser commandParser, AiParser aiParser) {
    this.commandParser = commandParser;
    this.aiParser = aiParser;
  }

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

      ParsedRequest parsed = commandParser.parse(rawText);
      mode = parsed == null ? "AI" : "FAST_PATH";
      if (parsed == null) {
        parsed = aiParser.parse(rawText);
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
