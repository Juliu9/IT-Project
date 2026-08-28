package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.ai.chat.client.ChatClient;
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

    public SessionRequest parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException(
                    "Please enter your request"
            );
        }

        SessionRequest request = this.chatClient.prompt()
                .system("""
                        You help an audiobook recommendation system understand what the user is looking for.
                        
                                Read the user's message and convert it into a SessionRequest. Choose the Intent that best describes the request.
                        
                                Add any mentioned topics, genres, authors, or search terms to the query. Record books or features the user wants included or excluded as preferences. If the user specifies a number of results, duration, or language, add those details to the constraints.
                        
                                Only create feedback when the user is commenting on recommendations they have already received. Do not guess or add details that the user did not mention.
                        
                                Ignore requestId, createdAt, and recommendations because they will be handled by other parts of the system.
                        """)
                .user(rawText)
                .call()
                .entity(SessionRequest.class);

        if (request == null) {
            throw new IllegalStateException(
                    "AI did not return a SessionRequest"
            );
        }

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
