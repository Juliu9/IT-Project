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
      throw new IllegalArgumentException("Please enter your request");
    }

    var aiResponse =
        aiOperationObserver.observe(
            "parse",
            () ->
                this.chatClient
                    .prompt()
                    .system(
                        """
                        Extract the user's audiobook search request.

                        Use only information explicitly provided by the user.
                        Choose one of these intents: RECOMMENDATION, REFINE, MORE_RESULTS,
                        CHANGE_COUNT, UPDATE_PREFERENCES, CLEAR_HISTORY, HELP, or UNKNOWN.
                        Put desired concepts in positiveSemanticQuery and concepts to avoid in
                        negativeSemanticQuery. Put exact author, narrator, language, duration, and
                        source requirements in mustInclude. Put exact values that must be excluded in
                        mustNotInclude. For example, "only English" belongs in mustInclude.language,
                        while "not English" belongs in mustNotInclude.language. Put the requested
                        number of results in bookCount. Do not invent values or report contradictions.
                        Use RECOMMENDATION for every new audiobook search, including searches by
                        theme, author, narrator, language, or duration. Do not select a retrieval mode.
                        Set personalised to true when the user asks for books they would like,
                        recommendations based on their taste, favourites, or listening history.
                        "Give me books I would like" is RECOMMENDATION with personalised=true
                        and no invented search criteria. Set personalised=false for ordinary searches.
                        Statements of taste such as "I like fantasy and Ursula Le Guin", including
                        short preference answers such as "fantasy", are UPDATE_PREFERENCES.
                        Put liked authors and narrators in positiveSemanticQuery; use mustInclude
                        only when the user explicitly requires exact matches.
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
