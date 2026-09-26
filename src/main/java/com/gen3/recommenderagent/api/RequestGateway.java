package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.application.RequestApplicationService;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.embedding.EmbeddingIndexer;
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
  private final EmbeddingIndexer embeddingIndexer;

  public RequestGateway(
      InputParser inputParser,
      RequestApplicationService requestApplicationService,
      ResponseGenerator responseGenerator,
      EmbeddingIndexer embeddingIndexer) {
    this.inputParser = inputParser;
    this.requestApplicationService = requestApplicationService;
    this.responseGenerator = responseGenerator;
    this.embeddingIndexer = embeddingIndexer;
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
    SessionRequest result =
        requestApplicationService.process(sessionId, userId, currentRequest);

    // The application service assigns the final request ID before request-vector persistence.
    embeddingIndexer.indexRequest(result);

    // 3. Generate natural language response
    return responseGenerator.generate(result.getRecommendations(), result);
  }
}
