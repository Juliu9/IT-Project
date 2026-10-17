package com.gen3.recommenderagent.inputparser;

/** Signals request input the caller can correct. */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String message) {
    super(message);
  }
}
