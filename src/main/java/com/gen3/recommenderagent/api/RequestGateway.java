package com.gen3.recommenderagent.api;

import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.engine.RecommendationEngine;
import com.gen3.recommenderagent.inputparser.AiInputParser;
import com.gen3.recommenderagent.response.AiResponseGenerator;
import org.springframework.web.bind.annotation.*;
import com.gen3.recommenderagent.domain.session.Recommendation;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RequestGateway {

    private final AiInputParser inputParser;
    private final RecommendationEngine recommendationEngine;
    private final AiResponseGenerator responseGenerator;

    public RequestGateway(AiInputParser inputParser, RecommendationEngine recommendationEngine, AiResponseGenerator responseGenerator) {
        this.inputParser = inputParser;
        this.recommendationEngine = recommendationEngine;
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

        // 2. Engine combines parsed request with Redis session state
        List<Recommendation> recommendations = recommendationEngine.process(sessionId, userId, currentRequest);

        // 3. Generate natural language response
        return responseGenerator.generate(recommendations, currentRequest);
    }
}