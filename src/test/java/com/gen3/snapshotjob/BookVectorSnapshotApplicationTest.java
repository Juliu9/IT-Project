package com.gen3.snapshotjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot;
import io.qdrant.client.QdrantClient;
import java.nio.file.Path;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BookVectorSnapshotApplicationTest {
  @TempDir Path directory;

  @Test
  void replacementBackendPublishesAndReopensThroughOfflineConfiguration() {
    replacementRunner(false)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(CatalogueSnapshotSource.class)
                  .doesNotHaveBean("qdrantSnapshotSource")
                  .doesNotHaveBean("solrSnapshotSource")
                  .doesNotHaveBean(QdrantClient.class)
                  .doesNotHaveBean(SolrClient.class);
              context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
              var reopened = SnapshotConfiguration.snapshot(context.getEnvironment());
              assertThat(reopened.findEmbeddingByBookId("book").orElseThrow())
                  .containsExactly(1, 0);
              assertThat(reopened.identity().backend()).isEqualTo("other");
              assertThat(reopened.identity().collection()).isEqualTo("books");
            });
  }

  @Test
  void rejectsSourceIdentityThatOfflineReadersWouldNotFind() {
    replacementRunner(true)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("Snapshot source identity does not match configuration"));
  }

  private ApplicationContextRunner replacementRunner(boolean mismatched) {
    return new ApplicationContextRunner()
        .withUserConfiguration(BookVectorSnapshotApplication.class)
        .withBean(
            CatalogueSnapshotSource.class,
            () -> {
              var identity =
                  SnapshotIdentity.from(
                      new org.springframework.mock.env.MockEnvironment()
                          .withProperty("audiobook.database", "other")
                          .withProperty("other.collection", "books"));
              return new CatalogueSnapshotSource() {
                public String backend() {
                  return identity.backend();
                }

                public String collection() {
                  return mismatched ? "different" : identity.collection();
                }

                public BookVectorSnapshot.Page page(String cursor, int limit) {
                  return new BookVectorSnapshot.Page(
                      List.of(new BookVectorSnapshot.Entry("book", new float[] {1, 0})), null);
                }
              };
            })
        .withPropertyValues(
            "audiobook.database=other",
            "other.collection=books",
            "embedding.dimensions=2",
            "book.vector-snapshot.directory=" + directory);
  }

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
            CatalogueSnapshotSource.class,
            () ->
                new CatalogueSnapshotSource() {
                  public String backend() {
                    return backend;
                  }

                  public String collection() {
                    return "books";
                  }

                  public BookVectorSnapshot.Page page(String cursor, int limit) {
                    return new BookVectorSnapshot.Page(
                        List.of(new BookVectorSnapshot.Entry("book", new float[] {1, 0})), null);
                  }
                },
            bean -> bean.setPrimary(true))
        .withPropertyValues(
            "audiobook.database=" + backend,
            "qdrant.collection=books",
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
