package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.ConversationState;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AiInputParser implements InputParser {
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

    var aiResponse =
        aiOperationObserver.observe(
            "parse",
            () ->
                this.chatClient
                    .prompt()
                    .system(
                        """
                        Extract the user's audiobook request using only stated information.

                        Set intent to RECOMMENDATION, REFINE, MORE_RESULTS, REPEAT, CHANGE_COUNT,
                        UPDATE_PREFERENCES, CLEAR_HISTORY, HELP, or UNKNOWN. Use RECOMMENDATION
                        for a new search; do not choose a retrieval mode. Put desired and avoided
                        concepts in positiveSemanticQuery and negativeSemanticQuery. Use mustInclude
                        and mustNotInclude only for exact author, narrator, language, duration,
                        publication year, or source requirements. Preserve duration comparisons:
                        use "under", "over", "at most", "at least", "exactly", or "between X and Y" with
                        explicit hours or minutes. Keep decimal values and both range endpoints;
                        for example, "under 1.5 hours" and "between 2 and 3 hours".
                        Never convert a vague duration into an invented numeric limit.
                        Preserve publication year comparisons as "after", "before", "at least",
                        "at most", "exactly", or "between YYYY and YYYY". Keep both range endpoints.
                        Set bookCount only when the user explicitly requests
                        a number of recommendations; otherwise leave it null.
                        Use REPEAT for requests to show the last recommendations again, such as
                        "repeat those" or "show those again". MORE_RESULTS asks for different books.

                        Set personalised=true only when the user requests recommendations based on
                        their taste, favourites, or history. "Give me books I would like" is a
                        personalised RECOMMENDATION with no invented criteria. Statements of taste,
                        including short answers such as "fantasy", are REFINE; keep
                        liked authors and narrators semantic unless an exact match is required.
                        Only RECOMMENDATION and REFINE may contain positiveSemanticQuery,
                        negativeSemanticQuery, or personalised=true. Leave these empty for all
                        other intents. Use REFINE for a follow-up that supplies semantic criteria.
                        Do not invent values or report contradictions.
                        """)
                    .user(rawText)
                    .call()
                    .responseEntity(ParsedRequest.class),
            response -> AiOperationObserver.AiUsage.from(response.response()));

    if (aiResponse.entity() == null) {
      throw new IllegalStateException("AI did not return a ParsedRequest");
    }

    ParsedRequest parsed = aiResponse.entity();

    SessionRequest request = new SessionRequest();
    request.setIntent(parsed.getIntent());
    request.setPersonalised(parsed.isPersonalised());
    request.setPositiveSemanticQuery(parsed.getPositiveSemanticQuery());
    request.setNegativeSemanticQuery(parsed.getNegativeSemanticQuery());
    ConversationState.normalizeInput(request);
    request.setMustInclude(parsed.getMustInclude());
    request.setMustNotInclude(parsed.getMustNotInclude());
    request.setBookCount(parsed.getBookCount());
    request.setRequestId(createUuidV7().toString());
    request.setRawText(rawText);

    return request;
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
