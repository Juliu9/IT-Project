package com.gen3.readhistoryjob;

import static org.assertj.core.api.Assertions.assertThat;

import com.gen3.shared.readhistory.port.ReadHistoryWriter;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReadHistoryApplicationTest {
  @TempDir Path directory;

  @Test
  void importsThroughAReplacementWriterWithoutOpeningPostgres() throws Exception {
    var csv = directory.resolve("history.csv");
    Files.writeString(
        csv,
        """
        type,user,item,created_at,updated_at
        book,u,rnib:book:1,2026-01-01 00:00:00,NULL
        """);
    var written = new ArrayList<ReadHistoryWriter.ReadBook>();
    new ApplicationContextRunner()
        .withUserConfiguration(ReadHistoryApplication.class)
        .withBean(ReadHistoryWriter.class, () -> written::addAll)
        .withPropertyValues(
            "read-history.import.enabled=true", "read-history.import.csv-path=" + csv)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ReadHistoryWriter.class)
                  .doesNotHaveBean(HikariDataSource.class);
              context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
              assertThat(written).containsExactly(new ReadHistoryWriter.ReadBook("u", "rnib_1"));
            });
  }
}
