package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.stereotype.Component;

@Component
public class InputParser {
    public SessionRequest parse(String rawText) {
        // AI Agent logic goes here.
        // It ONLY sees rawText and returns a populated SessionRequest.
        SessionRequest request = new SessionRequest();
        request.setRawText(rawText);


        return request;
    }
}

