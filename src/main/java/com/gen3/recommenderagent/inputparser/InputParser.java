package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class InputParser {
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
                        
                                Leave requestId, createdAt, and recommendations null because they will be handled by other parts of the system.
                        """)
                .user(rawText)
                .call()
                .entity(SessionRequest.class);

        if (request == null) {
            throw new IllegalStateException(
                    "AI did not return a SessionRequest"
            );
        }

        request.setRawText(rawText);

        return request;
    }
}
