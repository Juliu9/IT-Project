package com.gen3.readhistoryjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gen3.shared.csv.UserHistoryCsvReader;
import com.gen3.shared.readhistory.port.ReadHistoryWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadHistoryImporterTest {
  @TempDir Path temporary;

  @Test
  void streamsBoundedBatchesAndKeepsNoUncommittedTailAfterFailure() throws Exception {
    var path = temporary.resolve("history.csv");
    Files.writeString(
        path,
        """
        type,user,item,created_at,updated_at
        book,u,rnib:book:1,2026-01-01 00:00:00,NULL
        book,u,rnib:book:2,2026-01-01 00:00:00,NULL
        book,u,rnib:book:3,2026-01-01 00:00:00,NULL
        book,u,rnib:book:4,2026-01-01 00:00:00,NULL
        """);
    List<List<ReadHistoryWriter.ReadBook>> committed = new ArrayList<>();
    ReadHistoryWriter writer =
        rows -> {
          if (!committed.isEmpty()) {
            throw new IOException("database unavailable");
          }
          committed.add(List.copyOf(rows));
        };
    assertThatThrownBy(
            () -> new ReadHistoryImporter(new UserHistoryCsvReader(), writer).run(path, 2))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("database unavailable");
    assertThat(committed).hasSize(1);
    assertThat(committed.getFirst())
        .extracting(ReadHistoryWriter.ReadBook::bookId)
        .containsExactly("rnib_1", "rnib_2");
  }

  @Test
  void malformedHeadersAndDatesFailWithoutWrites() throws Exception {
    var path = temporary.resolve("history.csv");
    List<ReadHistoryWriter.ReadBook> writes = new ArrayList<>();
    var importer = new ReadHistoryImporter(new UserHistoryCsvReader(), writes::addAll);
    Files.writeString(path, "type,user,item\n");
    assertThatThrownBy(() -> importer.run(path, 10)).hasMessageContaining("headers");
    Files.writeString(path, "type,user,item,created_at,updated_at\nbook,u,rnib:book:1,bad,NULL\n");
    assertThatThrownBy(() -> importer.run(path, 10)).hasMessageContaining("row 1");
    assertThat(writes).isEmpty();
  }
}
