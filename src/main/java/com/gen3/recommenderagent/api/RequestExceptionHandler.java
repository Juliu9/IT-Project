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

  @ExceptionHandler({
    org.springframework.web.client.RestClientException.class,
    org.springframework.core.retry.RetryException.class,
    com.openai.errors.OpenAIIoException.class,
    com.openai.errors.OpenAIServiceException.class
  })
  public ResponseEntity<String> handleUnavailableAi(Exception exception) {
    return ResponseEntity.status(503)
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(
            "I'm having trouble processing your request right now. Please try again in a moment.");
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<String> handleInvalidRequest(InvalidRequestException exception) {
    return ResponseEntity.badRequest()
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(exception.getMessage());
  }

  @ExceptionHandler(SessionBusyException.class)
  public ResponseEntity<String> handleBusySession(SessionBusyException exception) {
    return ResponseEntity.status(409)
        .header("Retry-After", "1")
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(exception.getMessage());
  }

  @ExceptionHandler(SessionCommitException.class)
  public ResponseEntity<String> handleUnsafeSessionCommit(SessionCommitException exception) {
    return ResponseEntity.status(503)
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(exception.getMessage());
  }

  @ExceptionHandler(SessionLockLostException.class)
  public ResponseEntity<String> handleLostSessionLock(SessionLockLostException exception) {
    return ResponseEntity.status(503)
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(exception.getMessage());
  }

  @ExceptionHandler(CandidateRetrievalException.class)
  public ResponseEntity<String> handleUnavailableCandidateStore(
      CandidateRetrievalException exception) {
    return ResponseEntity.status(503)
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(
            "I'm having trouble reaching the audiobook catalogue right now. Please try again in a moment.");
  }
}
