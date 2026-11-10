package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.retrieval.CandidateRetrievalException;
import com.gen3.recommenderagent.storage.session.SessionBusyException;
import com.gen3.recommenderagent.storage.session.SessionCommitException;
import com.gen3.recommenderagent.storage.session.SessionLockLostException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Returns invalid request messages as client errors rather than server errors. */
@RestControllerAdvice
public class RequestExceptionHandler {
  private static final MediaType PLAIN_TEXT_UTF8 =
      new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

  /** Maps an unavailable AI provider to the API error response. */
  @ExceptionHandler(com.gen3.recommenderagent.ai.AiProviderException.class)
  public ResponseEntity<String> handleUnavailableAi(
      com.gen3.recommenderagent.ai.AiProviderException exception) {
    return ResponseEntity.status(503)
        .contentType(PLAIN_TEXT_UTF8)
        .body(
            "I'm having trouble processing your request right now. Please try again in a moment.");
  }

  /** Maps invalid user input to a client error with the request explanation. */
  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<String> handleInvalidRequest(InvalidRequestException exception) {
    return ResponseEntity.badRequest().contentType(PLAIN_TEXT_UTF8).body(exception.getMessage());
  }

  /** Maps a concurrent same-session request to the API busy response. */
  @ExceptionHandler(SessionBusyException.class)
  public ResponseEntity<String> handleBusySession(SessionBusyException exception) {
    return ResponseEntity.status(409)
        .header("Retry-After", "1")
        .contentType(PLAIN_TEXT_UTF8)
        .body(exception.getMessage());
  }

  /** Maps unsafe session writes to an error without returning uncommitted results. */
  @ExceptionHandler({SessionCommitException.class, SessionLockLostException.class})
  public ResponseEntity<String> handleUnsafeSessionWrite(RuntimeException exception) {
    return ResponseEntity.status(503).contentType(PLAIN_TEXT_UTF8).body(exception.getMessage());
  }

  /** Maps catalogue retrieval failures to the API unavailable response. */
  @ExceptionHandler(CandidateRetrievalException.class)
  public ResponseEntity<String> handleUnavailableCandidateStore(
      CandidateRetrievalException exception) {
    return ResponseEntity.status(503)
        .contentType(PLAIN_TEXT_UTF8)
        .body(
            "I'm having trouble reaching the audiobook catalogue right now. Please try again in a moment.");
  }
}
