package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;

@Component
public class InputParser {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChatClient chatClient;

    public InputParser(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public ResponseEntity<ChatResponse, SessionRequest> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException(
                    "Please enter your request"
            );
        }

        var aiResponse = this.chatClient.prompt()
                .system("""
                        Extract the user's audiobook search request.
                        
                        Use only information explicitly provided by the user.
                        Choose the intent and populate the query fields.
                        Set personalised only when the user asks for personalised results.
                        """)
                .user(rawText)
                .call()
                .responseEntity(ParsedRequest.class);

        if (aiResponse.entity() == null) {
            throw new IllegalStateException(
                    "AI did not return a ParsedRequest"
            );
        }

        ParsedRequest parsed = aiResponse.entity();

        SessionRequest request = new SessionRequest();
        request.setPersonalised(parsed.isPersonalised());
        request.setIntent(parsed.getIntent());
        request.setQuery(parsed.getQuery());
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
