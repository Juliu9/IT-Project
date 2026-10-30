package com.gen3.userpreferencejob;

import com.gen3.shared.identity.OpaqueIds;
import com.gen3.userpreferencejob.model.UserFavourite;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class UserFavouriteCsvReader {

  public void read(Path path, CsvRecordConsumer<UserFavourite> consumer) throws IOException {

    CSVFormat format =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .build();

    try (BufferedReader reader = Files.newBufferedReader(path);
        CSVParser csvParser = new CSVParser(reader, format)) {

      for (CSVRecord record : csvParser) {
        String type = record.get("type");

        if (!"book".equalsIgnoreCase(type)) {
          continue;
        }

        String userId = record.get("user");
        if (!OpaqueIds.isValid(userId)) {
          throw new IOException(
              "CSV user must be nonblank and at most 256 characters; row "
                  + record.getRecordNumber());
        }
        String item = record.get("item"); // Correctly targets index mapping now

        String bookId = BookIdExtractor.extractAndNormalize(item);

        consumer.accept(new UserFavourite(userId, bookId));
      }
    }
  }
}
