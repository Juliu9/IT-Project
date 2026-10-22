package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;

public interface InputParser {
  int MAX_REQUEST_CHARACTERS = 10000;

  SessionRequest parse(String rawText);
}
