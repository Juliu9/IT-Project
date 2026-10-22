package com.gen3.recommenderagent.inputparser;

import com.gen3.recommenderagent.domain.session.SessionRequest;

public interface InputParser {

  SessionRequest parse(String rawText);
}
