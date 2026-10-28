package com.gen3.recommenderagent.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gen3.recommenderagent.application.SessionTransitionService;
import com.gen3.recommenderagent.domain.conversation.ConversationTurn;
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
                          org.hamcrest.Matchers.containsString("Audiobook Recommendation Agent")))
              .andExpect(
                  content().string(org.hamcrest.Matchers.containsString("Recommendation details")));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void addsCatalogueMetadataWithoutChangingTheTurnAndSurvivesLookupFailure(boolean failure)
      throws Exception {
    var gateway = mock(RequestGateway.class);
    var catalogue = mock(com.gen3.catalogue.port.AudiobookDetailsLookup.class);
    var turn = new ConversationTurn();
    var book =
        new com.gen3.recommenderagent.domain.recommendation.Recommendation("id", 1, 0.7, "Title");
    turn.setRecommendations(java.util.List.of(book));
    when(gateway.handleDemoRequest("session", "user", "books"))
        .thenReturn(new RequestGateway.DemoResponse("Your books", turn, null));
    var stored =
        new com.gen3.catalogue.model.AudiobookRecord(
            "id", "source", "Title", java.util.List.of("Author"), "Description");
    if (failure) {
      when(catalogue.findBooksByIds(java.util.List.of("id")))
          .thenThrow(new java.io.IOException("unavailable"));
    } else {
      when(catalogue.findBooksByIds(java.util.List.of("id"))).thenReturn(java.util.List.of(stored));
    }
    var result = new DemoWebsite(gateway, catalogue).recommend("session", "user", "books");
    org.assertj.core.api.Assertions.assertThat(result.response()).isEqualTo("Your books");
    org.assertj.core.api.Assertions.assertThat(
            result.sessionRequest().path("recommendations").get(0).path("bookId").asText())
        .isEqualTo("id");
    org.assertj.core.api.Assertions.assertThat(turn.getRecommendations()).containsExactly(book);
    if (failure) {
      org.assertj.core.api.Assertions.assertThat(result.catalogueBooks()).isEmpty();
      org.assertj.core.api.Assertions.assertThat(result.catalogueError())
          .isEqualTo(
              "I couldn't load the extra book details right now. Please try again in a moment.");
    } else {
      org.assertj.core.api.Assertions.assertThat(result.catalogueBooks()).containsExactly(stored);
      org.assertj.core.api.Assertions.assertThat(result.catalogueError()).isNull();
    }
  }

  @org.junit.jupiter.api.Test
  void skipsCatalogueLookupWhenThereAreNoRecommendations() throws Exception {
    var gateway = mock(RequestGateway.class);
    var catalogue = mock(com.gen3.catalogue.port.AudiobookDetailsLookup.class);
    var turn = new ConversationTurn();
    when(gateway.handleDemoRequest("session", "user", "help"))
        .thenReturn(new RequestGateway.DemoResponse("Help", turn, null));
    var result = new DemoWebsite(gateway, catalogue).recommend("session", "user", "help");
    org.assertj.core.api.Assertions.assertThat(result.catalogueBooks()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(catalogue);
  }

  @org.junit.jupiter.api.Test
  void omitsVectorsFromDevPayloadWithoutChangingSessionData() {
    var gateway = mock(RequestGateway.class);
    var catalogue = mock(com.gen3.catalogue.port.AudiobookDetailsLookup.class);
    var turn = new ConversationTurn();
    turn.setSimilarBook(
        new com.gen3.recommenderagent.domain.search.SimilarBookReference(
            "book", "Title", java.util.List.of("Author"), new float[] {1, 0}));
    turn.setSimilarityQueryVector(new float[] {0, 1});
    turn.setUserPreferenceSnapshot(
        new com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding(
            "user", new float[] {1, 0}, new float[] {0, 1}));
    when(gateway.handleDemoRequest("session", "user", "books"))
        .thenReturn(new RequestGateway.DemoResponse("Books", turn, null));
    var result = new DemoWebsite(gateway, catalogue).recommend("session", "user", "books");
    var json = result.sessionRequest();
    org.assertj.core.api.Assertions.assertThat(json.has("similarityQueryVector")).isFalse();
    org.assertj.core.api.Assertions.assertThat(json.path("similarBook").has("vector")).isFalse();
    org.assertj.core.api.Assertions.assertThat(json.path("similarBook").path("title").asText())
        .isEqualTo("Title");
    org.assertj.core.api.Assertions.assertThat(
            json.path("userPreferenceSnapshot").has("favouritesVector"))
        .isFalse();
    org.assertj.core.api.Assertions.assertThat(
            json.path("userPreferenceSnapshot").has("historyVector"))
        .isFalse();
    org.assertj.core.api.Assertions.assertThat(turn.getSimilarBook().vector())
        .containsExactly(1, 0);
    org.assertj.core.api.Assertions.assertThat(turn.getSimilarityQueryVector())
        .containsExactly(0, 1);
    org.assertj.core.api.Assertions.assertThat(turn.getUserPreferenceSnapshot().favouritesVector())
        .containsExactly(1, 0);
    org.assertj.core.api.Assertions.assertThat(turn.getUserPreferenceSnapshot().historyVector())
        .containsExactly(0, 1);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({RequestGateway.class, DemoWebsite.class, RequestExceptionHandler.class})
  static class TestConfiguration {
    @Bean
    com.gen3.catalogue.port.AudiobookDetailsLookup catalogue() {
      return mock(com.gen3.catalogue.port.AudiobookDetailsLookup.class);
    }

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
