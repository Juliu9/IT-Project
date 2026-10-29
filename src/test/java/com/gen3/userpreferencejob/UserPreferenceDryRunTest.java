package com.gen3.userpreferencejob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gen3.catalogue.port.AudiobookVectorRepository;
import com.gen3.recommenderagent.storage.user.model.UserPreferenceEmbedding;
import com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository;
import io.qdrant.client.QdrantClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class UserPreferenceDryRunTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "qdrant,false,dry-run", "qdrant,true,dry-run", "solr,false,dry-run", "solr,true,dry-run",
    "qdrant,false,default", "qdrant,true,default", "solr,false,default", "solr,true,default"
  })
  void bothBackendsReadAndAggregateWithoutAnyDatabaseWrites(
      String backend, boolean missing, String mode, CapturedOutput output) throws Exception {
    Path favourites = directory.resolve("favourites.csv");
    Path history = directory.resolve("history.csv");
    Files.writeString(favourites, "type,user,item\nbook,test-user,local:book:one\n");
    Files.writeString(history, "type,user,item,created_at,updated_at\n");
    var books = mock(AudiobookVectorRepository.class);
    when(books.findEmbeddingsByBookIds(anyCollection()))
        .thenReturn(missing ? Map.of() : Map.of("local_one", new float[] {1, 0}));
    var qdrant = mock(QdrantClient.class);
    var solr = mock(SolrClient.class);
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(UserPreferenceApplication.class)
            .withInitializer(
                context ->
                    context.addBeanFactoryPostProcessor(
                        factory -> {
                          String catalogue =
                              com.gen3.catalogue.qdrant.QdrantAudiobookRepository.class.getName();
                          if (factory.containsBeanDefinition(catalogue)) {
                            factory.getBeanDefinition(catalogue).setPrimary(false);
                          }
                        }))
            .withBean(QdrantClient.class, () -> qdrant)
            .withBean("testSolrClient", SolrClient.class, () -> solr, bean -> bean.setPrimary(true))
            .withBean(
                "testBooks",
                AudiobookVectorRepository.class,
                () -> books,
                bean -> bean.setPrimary(true))
            .withPropertyValues(
                "audiobook.database=" + backend,
                "embedding.dimensions=2",
                "user.preferences.import-on-startup=true",
                "user.preferences.favourites-csv-path=" + favourites,
                "user.preferences.history-csv-path=" + history,
                "solr.url=http://localhost:8983/solr",
                "solr.collection=books",
                "solr.username=",
                "solr.password=");
    if (!mode.equals("default")) {
      runner = runner.withPropertyValues("user.preferences.import.mode=" + mode);
    }
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(UserPreferenceVectorRepository.class))
              .isInstanceOf(DryRunUserPreferenceRepository.class);
          if (backend.equals("solr")) {
            assertThat(context)
                .hasSingleBean(
                    com.gen3.recommenderagent.storage.user.solr.SolrUserPreferenceRepository.class);
          } else {
            assertThat(context)
                .hasSingleBean(
                    com.gen3.recommenderagent.storage.user.qdrant.QdrantUserPreferenceRepository
                        .class);
          }
          context
              .getBean(UserPreferenceStartupImporter.class)
              .run(new DefaultApplicationArguments());
          verify(books).findEmbeddingsByBookIds(Set.of("local_one"));
          verifyNoInteractions(solr, qdrant);
        });
    assertThat(output.getOut())
        .contains(
            "mode=dry-run",
            "csvReadMs=",
            "aggregationMs=",
            "totalMs=",
            missing ? "missingVectorUsers=1 clears=0" : "generatedProfiles=1 writes=0",
            "No user preference vectors were saved or cleared.")
        .doesNotContain("Successfully aggregated and saved");
  }

  @Test
  void dryRunStillRejectsInvalidOutputVectors() {
    var sink = new DryRunUserPreferenceRepository(2);
    for (float[] invalid :
        new float[][] {{1}, {0, 0}, {Float.NaN, 1}, {Float.POSITIVE_INFINITY, 1}}) {
      assertThatThrownBy(() -> sink.save(new UserPreferenceEmbedding("test", invalid, null)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> sink.findEmbeddingByUserId("test"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
