package com.gen3.recommenderagent.inputparser;

/** Signals request input the caller can correct. */
public class InvalidRequestException extends RuntimeException {

  /** Creates the failure with the supplied message or underlying cause. */
  public InvalidRequestException(String message) {
    super(message);
  }
}
