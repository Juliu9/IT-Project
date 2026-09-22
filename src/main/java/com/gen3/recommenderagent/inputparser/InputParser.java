package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;

public interface InputParser {

  ResponseEntity<ChatResponse, SessionRequest> parse(String rawText);
}
