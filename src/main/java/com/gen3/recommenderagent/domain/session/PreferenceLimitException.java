package com.gen3.recommenderagent.domain.session;

import com.gen3.recommenderagent.inputparser.InvalidRequestException;

/** A category overflow can be explained without applying any of the requested edits. */
public final class PreferenceLimitException extends InvalidRequestException {
  public PreferenceLimitException(String message) {
    super(message);
  }
}
