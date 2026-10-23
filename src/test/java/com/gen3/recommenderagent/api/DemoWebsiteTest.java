package com.gen3.recommenderagent.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.inputparser.InputParser;
import com.gen3.recommenderagent.inputparser.InvalidRequestException;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.response.ResponseGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class DemoWebsiteTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "prod", "dev"})
  void exposesWebsiteAndDemoApiOnlyWithExplicitDevProfile(String profile) throws Exception {
    try (var context = new AnnotationConfigWebApplicationContext()) {
      if (!profile.isEmpty()) {
        context.getEnvironment().setActiveProfiles(profile);
      }
      context.setServletContext(new MockServletContext());
      context.register(TestConfiguration.class);
      context.refresh();
      var mvc = MockMvcBuilders.webAppContextSetup(context).build();
      boolean dev = profile.equals("dev");
      for (String path : new String[] {"/", "/index.html"}) {
        var result =
            mvc.perform(get(path)).andExpect(dev ? status().isOk() : status().isNotFound());
        if (dev) {
          result
              .andExpect(content().contentTypeCompatibleWith("text/html"))
              .andExpect(
                  content()
                      .string(
                          org.hamcrest.Matchers.containsString("Audiobook Recommendation Agent")));
        }
      }
      mvc.perform(get("/demo/index.html")).andExpect(status().isNotFound());
      mvc.perform(
              post("/api/v1/recommendations/demo")
                  .header("X-Session-Id", "session")
                  .header("X-User-Id", "user")
                  .contentType("text/plain")
                  .content("blank"))
          .andExpect(dev ? status().isBadRequest() : status().isNotFound());
      mvc.perform(
              post("/api/v1/recommendations")
                  .header("X-Session-Id", "session")
                  .header("X-User-Id", "user")
                  .contentType("text/plain")
                  .content("blank"))
          .andExpect(status().isBadRequest())
          .andExpect(content().string("fallback"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({RequestGateway.class, DemoWebsite.class, RequestExceptionHandler.class})
  static class TestConfiguration {
    @Bean
    InputParser parser() {
      var parser = mock(InputParser.class);
      when(parser.parse("blank")).thenThrow(new InvalidRequestException("fallback"));
      return parser;
    }

    @Bean
    SessionTransitionService transitions() {
      return mock(SessionTransitionService.class);
    }

    @Bean
    ResponseGenerator response() {
      return mock(ResponseGenerator.class);
    }

    @Bean
    RequestTelemetry telemetry() {
      return new RequestTelemetry(new tools.jackson.databind.ObjectMapper());
    }
  }
}
