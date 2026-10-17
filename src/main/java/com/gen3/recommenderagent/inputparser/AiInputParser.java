package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.observability.AiOperationObserver;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AiInputParser implements InputParser {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ChatClient chatClient;
  private final AiOperationObserver aiOperationObserver;

  @Autowired
  public AiInputParser(
      ChatClient.Builder chatClientBuilder, AiOperationObserver aiOperationObserver) {
    this.chatClient = chatClientBuilder.build();
    this.aiOperationObserver = aiOperationObserver;
  }

  public AiInputParser(ChatClient.Builder chatClientBuilder) {
    this(chatClientBuilder, AiOperationObserver.noop());
  }

  @Override
  public ResponseEntity<ChatResponse, SessionRequest> parse(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      throw new InvalidRequestException("I didn't catch that. What would you like to listen to?");
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

                        Set intent to RECOMMENDATION, REFINE, MORE_RESULTS, CHANGE_COUNT,
                        UPDATE_PREFERENCES, CLEAR_HISTORY, HELP, or UNKNOWN. Use RECOMMENDATION
                        for a new search; do not choose a retrieval mode. Put desired and avoided
                        concepts in positiveSemanticQuery and negativeSemanticQuery. Use mustInclude
                        and mustNotInclude only for exact author, narrator, language, duration, or
                        source requirements. Set bookCount only when the user explicitly requests
                        a number of recommendations; otherwise leave it null.

                        Set personalised=true only when the user requests recommendations based on
                        their taste, favourites, or history. "Give me books I would like" is a
                        personalised RECOMMENDATION with no invented criteria. Statements of taste,
                        including short answers such as "fantasy", are UPDATE_PREFERENCES; keep
                        liked authors and narrators semantic unless an exact match is required.
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
    request.setMustInclude(parsed.getMustInclude());
    request.setMustNotInclude(parsed.getMustNotInclude());
    request.setBookCount(parsed.getBookCount());
    request.setRequestId(createUuidV7().toString());
    request.setRawText(rawText);

    return new ResponseEntity<>(aiResponse.response(), request);
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
