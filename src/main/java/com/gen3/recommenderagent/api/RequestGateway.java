package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.application.RequestApplicationService;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.response.ResponseGenerator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RequestGateway {

  private final InputParser inputParser;
  private final RequestApplicationService requestApplicationService;
  private final ResponseGenerator responseGenerator;

  public RequestGateway(
      InputParser inputParser,
      RequestApplicationService requestApplicationService,
      ResponseGenerator responseGenerator) {
    this.inputParser = inputParser;
    this.requestApplicationService = requestApplicationService;
    this.responseGenerator = responseGenerator;
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
      @RequestBody String rawText) {
    // 1. Parser receives ONLY raw text
    SessionRequest currentRequest = inputParser.parse(rawText).entity();

    // 2. Application service resolves the action and updates session state.
    SessionRequest result = requestApplicationService.process(sessionId, userId, currentRequest);

    // 3. Generate natural language response
    return responseGenerator.generate(result.getRecommendations(), result);
  }

  /** Runs the same request flow with the measurements displayed by the bundled demo page. */
  @PostMapping("/demo")
  public DemoRecommendationResponse handleDemoRequest(
      @RequestHeader("X-Session-Id") String sessionId,
      @RequestHeader("X-User-Id") String userId,
      @RequestBody String rawText) {
    long totalStartedAt = System.nanoTime();

    long parseStartedAt = System.nanoTime();
    var parsedRequest = inputParser.parse(rawText);
    long parseMs = elapsedMs(parseStartedAt);

    long recommendStartedAt = System.nanoTime();
    SessionRequest result =
        requestApplicationService.process(sessionId, userId, parsedRequest.entity());
    long recommendMs = elapsedMs(recommendStartedAt);

    long responseStartedAt = System.nanoTime();
    String response = responseGenerator.generate(result.getRecommendations(), result);
    long responseMs = elapsedMs(responseStartedAt);

    return new DemoRecommendationResponse(
        response,
        DemoRecommendationResponse.metrics(
            parseMs, recommendMs, responseMs, elapsedMs(totalStartedAt), parsedRequest.response()),
        result);
  }

  private long elapsedMs(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
