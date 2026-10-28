package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.application.conversation.workflow.ConversationRules;
import com.gen3.recommenderagent.domain.conversation.ConversationAction;
import com.gen3.recommenderagent.domain.session.SessionPreferences;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
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

  private boolean hasSearchValue(UserIntent parsed, String path) {
    String[] parts = path.split("\\.");
    Object value;
    if (parts[0].equals("mustInclude") || parts[0].equals("mustNotInclude")) {
      var criteria =
          parts[0].equals("mustInclude") ? parsed.getMustInclude() : parsed.getMustNotInclude();
      if (criteria == null) {
        return false;
      }
      value =
          switch (parts[1]) {
            case "authors" -> criteria.getAuthors();
            case "narrators" -> criteria.getNarrators();
            case "duration" -> criteria.getDuration();
            case "publicationYear" -> criteria.getPublicationYear();
            case "language" -> criteria.getLanguage();
            case "source" -> criteria.getSource();
            default -> null;
          };
    } else {
      var query =
          parts[0].equals("positiveSemanticQuery")
              ? parsed.getPositiveSemanticQuery()
              : parsed.getNegativeSemanticQuery();
      if (query == null) {
        return false;
      }
      value =
          switch (parts[1]) {
            case "authors" -> query.getAuthors();
            case "narrators" -> query.getNarrators();
            case "genres" -> query.getGenres();
            case "topics" -> query.getTopics();
            case "keywords" -> query.getKeywords();
            default -> null;
          };
    }
    return value instanceof List<?> list
        ? !list.isEmpty()
        : value instanceof String text && !text.isBlank();
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

  private boolean normalizeCount(String rawText, CountInput.Check countInput, UserIntent parsed) {
    // A number retained as a stated subject is not also a quantity error.
    if (!countInput.invalid() && CountInput.describesZero(rawText, parsed)) {
      parsed.setInvalidBookCount(false);
      if (parsed.getBookCount() != null && parsed.getBookCount().signum() <= 0) {
        parsed.setBookCount(null);
      }
    }

    boolean invalidCount =
        parsed.getIntent() != ConversationAction.EXPLAIN_RECOMMENDATION
            && (countInput.invalid()
                || parsed.isInvalidBookCount()
                || (parsed.getBookCount() != null && parsed.getBookCount().signum() <= 0));
    if (invalidCount && countInput.invalid()) {
      parsed.setIntent(
          parsed.getIntent() == ConversationAction.MORE_RESULTS
                  || parsed.getIntent() == ConversationAction.REFINE
              ? parsed.getIntent()
              : CountInput.setting(rawText) || countInput.modelText().equals("1")
                  ? ConversationAction.CHANGE_COUNT
                  : CountInput.more(rawText)
                      ? ConversationAction.MORE_RESULTS
                      : ConversationAction.RECOMMENDATION);
    }
    return invalidCount;
  }

  private SessionRequest toRequest(String rawText, UserIntent parsed, boolean invalidCount) {
    SessionRequest request = new SessionRequest();
    request.setIntent(parsed.getIntent());
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
    request.setExplicitSearch(parsed.isExplicitSearch());
    applySearchReplacements(rawText, parsed, request);
    request.setReferenceRank(boundedInteger(parsed.getReferenceRank()));
    request.setReferenceTitle(parsed.getReferenceTitle());
    request.setPersonalised(parsed.isPersonalised());
    request.setPositiveSemanticQuery(parsed.getPositiveSemanticQuery());
    request.setNegativeSemanticQuery(parsed.getNegativeSemanticQuery());
    request.setMustInclude(parsed.getMustInclude());
    request.setMustNotInclude(parsed.getMustNotInclude());
    // Validate stated people before normalization can discard unsupported control-intent fields.
    com.gen3.recommenderagent.application.conversation.PreferenceRequestMapper.fromRequest(request);
    if (request.getPreferenceUpdates() != null) {
      request.getPreferenceUpdates().copy();
    }
    if (request.getPreferenceRemovals() != null) {
      request.getPreferenceRemovals().copy();
    }
    applyPersonalisation(rawText, request);
    ConversationRules.normalizeInput(request);
    request.setInvalidBookCount(invalidCount);
    request.setBookCount(
        invalidCount || parsed.getIntent() == ConversationAction.EXPLAIN_RECOMMENDATION
            ? null
            : boundedInteger(parsed.getBookCount()));
    request.setRequestId(createUuidV7().toString());
    request.setRawText(rawText);
    return request;
  }

  private void applySearchReplacements(String rawText, UserIntent parsed, SessionRequest request) {
    if (parsed.getIntent() == ConversationAction.RECOMMENDATION
        || parsed.getIntent() == ConversationAction.REFINE) {
      new SessionPreferences().updated(null, null, parsed.getReplaceSearchFields());
      request.setReplaceSearchFields(parsed.getReplaceSearchFields());
      String command =
          com.gen3.recommenderagent.inputparser.ConversationText.normalizeCommand(rawText);
      if (command.matches("^(?:also|add|additionally|(?:make (?:it|them) )?more)\\b.*")
          && !command.matches(".*\\b(?:instead|replace|rather than|switch)\\b.*")) {
        // Keep field removals while protecting supplied additions from model replacement flags.
        var search = parsed;
        request.setReplaceSearchFields(
            parsed.getReplaceSearchFields().stream()
                .filter(path -> !hasSearchValue(search, path))
                .toList());
      }
    }
  }

  private void applyPersonalisation(String rawText, SessionRequest request) {
    String command =
        com.gen3.recommenderagent.inputparser.ConversationText.normalizeCommand(rawText);
    if (command.matches(
            "^(?:i want |give me |recommend |show me )?(?:a |an |some |[0-9]+ )?(?:books?|audiobooks?) (?:by|narrated by) .+")
        && !command.matches(
            ".*\\b(?:for me|personalised|personalized|my preferences|my reading|my favourites|my favorites|my history|i would like|i'd like)\\b.*")) {
      request.setPersonalised(false);
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
