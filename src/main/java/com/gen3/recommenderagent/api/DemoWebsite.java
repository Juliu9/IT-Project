package com.gen3.recommenderagent.api;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Development-only website; its assets live outside Spring's public static directories. */
@Profile("dev")
@RestController
public class DemoWebsite {
  private final RequestGateway gateway;

  public DemoWebsite(RequestGateway gateway) {
    this.gateway = gateway;
  }

  @GetMapping(
      value = {"/", "/index.html"},
      produces = MediaType.TEXT_HTML_VALUE)
  public Resource index() {
    return new ClassPathResource("demo/index.html");
  }

  @PostMapping(value = "/api/v1/recommendations/demo", produces = MediaType.APPLICATION_JSON_VALUE)
  public RequestGateway.DemoResponse recommend(
      @RequestHeader("X-Session-Id") String sessionId,
      @RequestHeader("X-User-Id") String userId,
      @RequestBody(required = false) String rawText) {
    return gateway.handleDemoRequest(sessionId, userId, rawText);
  }
}
