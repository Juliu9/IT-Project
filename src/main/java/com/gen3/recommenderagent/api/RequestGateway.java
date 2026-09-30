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

  /** Runs the recommendation pipeline and exposes demo-friendly timing and request details. */
  @PostMapping("/demo")
  public DemoRecommendationResponse handleDemoRequest(
      @RequestHeader("X-Session-Id") String sessionId,
      @RequestHeader("X-User-Id") String userId,
      @RequestBody String rawText) {
    long totalStartedAt = System.nanoTime();

    long parserStartedAt = System.nanoTime();
    var parsedRequest = inputParser.parse(rawText);
    long parserDurationMs = elapsedMilliseconds(parserStartedAt);

    long engineStartedAt = System.nanoTime();
    SessionRequest result =
        requestApplicationService.process(sessionId, userId, parsedRequest.entity());
    long engineDurationMs = elapsedMilliseconds(engineStartedAt);

    long generatorStartedAt = System.nanoTime();
    String response = responseGenerator.generate(result.getRecommendations(), result);
    long generatorDurationMs = elapsedMilliseconds(generatorStartedAt);

    DemoRecommendationResponse.StageMetric parserMetric =
        new DemoRecommendationResponse.StageMetric(
            parserDurationMs,
            DemoRecommendationResponse.UsageMetric.from(parsedRequest.response()));
    DemoRecommendationResponse.StageMetric engineMetric =
        new DemoRecommendationResponse.StageMetric(engineDurationMs, null);
    DemoRecommendationResponse.StageMetric generatorMetric =
        new DemoRecommendationResponse.StageMetric(generatorDurationMs, null);
    DemoRecommendationResponse.Metrics metrics =
        new DemoRecommendationResponse.Metrics(
            parserMetric,
            engineMetric,
            generatorMetric,
            elapsedMilliseconds(totalStartedAt),
            null,
            null);

    return new DemoRecommendationResponse(response, metrics, result);
  }

  private long elapsedMilliseconds(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
