package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.response.ResponseGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RequestGateway {

  private final InputParser inputParser;
  private final SessionTransitionService sessionTransitionService;
  private final ResponseGenerator responseGenerator;
  private final RequestTelemetry telemetry;

  @Autowired
  public RequestGateway(
      InputParser inputParser,
      SessionTransitionService sessionTransitionService,
      ResponseGenerator responseGenerator,
      RequestTelemetry telemetry) {
    this.inputParser = inputParser;
    this.sessionTransitionService = sessionTransitionService;
    this.responseGenerator = responseGenerator;
    this.telemetry = telemetry;
  }

  public RequestGateway(
      InputParser inputParser,
      SessionTransitionService sessionTransitionService,
      ResponseGenerator responseGenerator) {
    this(inputParser, sessionTransitionService, responseGenerator, null);
  }

  /**
   * Handles the logic at the highest level, from request to response
   *
   * @param sessionId
   * @param userId
   * @param rawText
   * @return
   */
  @PostMapping
  public String handleRequest(
      @RequestHeader("X-Session-Id") String sessionId,
      @RequestHeader("X-User-Id") String userId,
      @RequestBody(required = false) String rawText) {
    if (telemetry != null) {
      telemetry.start();
    }
    SessionRequest currentRequest = null;
    boolean completed = false;
    try {
      currentRequest = RequestTelemetry.measure("parseMs", () -> inputParser.parse(rawText));
      SessionRequest result = sessionTransitionService.process(sessionId, userId, currentRequest);
      String response =
          RequestTelemetry.measure(
              "responseMs", () -> responseGenerator.generate(result.getRecommendations(), result));
      if (telemetry != null) {
        telemetry.complete(result);
      }
      completed = true;
      return response;
    } finally {
      if (telemetry != null && !completed) {
        telemetry.fail(currentRequest);
      }
    }
  }
}
