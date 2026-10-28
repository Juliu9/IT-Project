package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.application.conversation.SessionRequest;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.UserIntent;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.response.ResponseGenerator;
import java.util.function.BiFunction;
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
    return process(sessionId, userId, rawText, false, (response, request) -> response);
  }

  public DemoResponse handleDemoRequest(
      @RequestHeader("X-Session-Id") String sessionId,
      @RequestHeader("X-User-Id") String userId,
      @RequestBody(required = false) String rawText) {
    return process(
        sessionId,
        userId,
        rawText,
        true,
        (response, request) ->
            new DemoResponse(response, request, telemetry == null ? null : telemetry.snapshot()));
  }

  public record DemoResponse(
      String response, ConversationTurn sessionRequest, RequestTelemetry.Snapshot metrics) {}

  private <T> T process(
      String sessionId,
      String userId,
      String rawText,
      boolean demo,
      BiFunction<String, ConversationTurn, T> formatResponse) {
    if (telemetry != null) {
      if (demo) {
        telemetry.startDemo();
      } else {
        telemetry.start();
      }
    }
    SessionRequest currentRequest = null;
    boolean completed = false;
    try {
      UserIntent parsedIntent =
          RequestTelemetry.measure(
              "parseMs",
              () ->
                  inputParser.parseUserIntent(
                      rawText, () -> sessionTransitionService.parserContext(sessionId, userId)));
      record Generated(String text, ConversationTurn turn) {}
      java.util.function.Function<ConversationTurn, Generated> generate =
          result -> {
            String response =
                RequestTelemetry.measure(
                    "responseMs",
                    () -> responseGenerator.generate(result.getRecommendations(), result));
            return new Generated(response, result);
          };
      Generated generated =
          sessionTransitionService.process(sessionId, userId, rawText, parsedIntent, generate);
      T payload = formatResponse.apply(generated.text(), generated.turn());
      if (telemetry != null) {
        telemetry.complete(generated.turn());
      }
      completed = true;
      return payload;
    } finally {
      if (telemetry != null && !completed) {
        telemetry.fail(currentRequest);
      }
    }
  }
}
