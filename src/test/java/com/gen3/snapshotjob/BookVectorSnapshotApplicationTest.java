package com.gen3.snapshotjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.catalogue.snapshot.BookVectorSnapshot;
import io.qdrant.client.QdrantClient;
import java.nio.file.Path;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BookVectorSnapshotApplicationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"solr", "qdrant"})
  void standaloneJobPublishesWithoutAiOrProfileWriters(String backend) {
    new ApplicationContextRunner()
        .withUserConfiguration(BookVectorSnapshotApplication.class)
        .withBean(QdrantClient.class, () -> mock(QdrantClient.class))
        .withBean(
            "testSolrClient",
            SolrClient.class,
            () -> mock(SolrClient.class),
            bean -> bean.setPrimary(true))
        .withBean(
            "testSource",
            BookVectorSnapshot.Source.class,
            () ->
                (cursor, limit) ->
                    new BookVectorSnapshot.Page(
                        List.of(new BookVectorSnapshot.Entry("book", new float[] {1, 0})), null),
            bean -> bean.setPrimary(true))
        .withPropertyValues(
            "audiobook.database=" + backend,
            "book.vector-snapshot.directory=" + directory,
            "embedding.dimensions=2",
            "solr.url=http://localhost:8983/solr",
            "solr.collection=books",
            "solr.username=",
            "solr.password=")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(org.springframework.ai.embedding.EmbeddingModel.class)
                  .doesNotHaveBean(
                      com.gen3.recommenderagent.storage.user.port.UserPreferenceVectorRepository
                          .class)
                  .doesNotHaveBean(com.gen3.recommenderagent.api.RequestGateway.class);
              context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
              assertThat(
                      context
                          .getBean(BookVectorSnapshot.class)
                          .findEmbeddingByBookId("book")
                          .orElseThrow())
                  .containsExactly(1, 0);
            });
  }
}
