package com.gen3.recommenderagent.api;

import com.gen3.catalogue.model.AudiobookRecord;
import com.gen3.catalogue.port.AudiobookDetailsLookup;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
import com.gen3.recommenderagent.domain.recommendation.Recommendation;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Development-only website; its assets live outside Spring's public static directories. */
@Profile("dev")
@RestController
public class DemoWebsite {
  private static final Logger LOGGER = LoggerFactory.getLogger(DemoWebsite.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final RequestGateway gateway;
  private final AudiobookDetailsLookup catalogue;

  public DemoWebsite(RequestGateway gateway, AudiobookDetailsLookup catalogue) {
    this.gateway = gateway;
    this.catalogue = catalogue;
  }

  @GetMapping(
      value = {"/", "/index.html"},
      produces = MediaType.TEXT_HTML_VALUE)
  public Resource index() {
    return new ClassPathResource("demo/index.html");
  }

  @PostMapping(value = "/api/v1/recommendations/demo", produces = MediaType.APPLICATION_JSON_VALUE)
  public DemoResponse recommend(
      @RequestHeader("X-Session-Id") String sessionId,
      @RequestHeader("X-User-Id") String userId,
      @RequestBody(required = false) String rawText) {
    var result = gateway.handleDemoRequest(sessionId, userId, rawText);
    var books = result.sessionRequest().getRecommendations();
    List<AudiobookRecord> metadata = List.of();
    String error = null;
    if (books != null && !books.isEmpty()) {
      try {
        metadata = catalogue.findBooksByIds(Recommendation.bookIds(books));
      } catch (IOException exception) {
        LOGGER.warn("dev.catalogue.lookup.failed", exception);
        error = "I couldn't load the extra book details right now. Please try again in a moment.";
      }
    }
    return new DemoResponse(
        result.response(),
        withoutVectors(result.sessionRequest()),
        result.metrics(),
        metadata,
        error);
  }

  private ObjectNode withoutVectors(ConversationTurn request) {
    ObjectNode json = JSON.valueToTree(request);
    json.remove("similarityQueryVector");
    if (json.get("similarBook") instanceof ObjectNode book) {
      book.remove("vector");
    }
    if (json.get("userPreferenceSnapshot") instanceof ObjectNode preferences) {
      preferences.remove("favouritesVector");
      preferences.remove("historyVector");
    }
    return json;
  }

  public record DemoResponse(
      String response,
      ObjectNode sessionRequest,
      RequestTelemetry.Snapshot metrics,
      List<AudiobookRecord> catalogueBooks,
      String catalogueError) {}
}
