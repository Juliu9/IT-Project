package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.storage.sessionrepository.redis.SessionBusyException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Returns invalid request messages as client errors rather than server errors. */
@RestControllerAdvice
public class RequestExceptionHandler {

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<String> handleInvalidRequest(InvalidRequestException exception) {
    return ResponseEntity.badRequest()
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(exception.getMessage());
  }

  @ExceptionHandler(SessionBusyException.class)
  public ResponseEntity<String> handleBusySession(SessionBusyException exception) {
    return ResponseEntity.status(409)
        .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .body(exception.getMessage());
  }
}
