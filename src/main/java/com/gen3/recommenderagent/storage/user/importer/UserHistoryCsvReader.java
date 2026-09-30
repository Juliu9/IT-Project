package com.gen3.recommenderagent.storage.user.importer;

import com.gen3.recommenderagent.storage.user.model.UserHistory;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class UserHistoryCsvReader {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<UserHistory> read(Path path) throws IOException {
        List<UserHistory> recents = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>(); // Safeguard container

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .build();

        try (BufferedReader reader = Files.newBufferedReader(path);
             CSVParser csvParser = new CSVParser(reader, format)) {

            for (CSVRecord record : csvParser) {
                if (!"book".equalsIgnoreCase(record.get("type"))) {
                    continue;
                }

                String userId = record.get("user");
                String item = record.get("item");
                String createdAtRaw = record.get("created_at");
                String updatedAtRaw = record.get("updated_at");

                String bookId = BookIdExtractor.extractAndNormalize(item);

                String dedupeKey = userId + "_" + bookId;
                if (!processedKeys.add(dedupeKey)) {
                    continue;
                }

                // Parse created_at as the baseline
                Instant lastInteractionTime = LocalDateTime.parse(createdAtRaw, DATE_FORMATTER)
                        .toInstant(ZoneOffset.UTC);

                // Override with updated_at if it exists and is not "NULL"
                if (updatedAtRaw != null && !updatedAtRaw.equalsIgnoreCase("NULL") && !updatedAtRaw.isBlank()) {
                    lastInteractionTime = LocalDateTime.parse(updatedAtRaw, DATE_FORMATTER)
                            .toInstant(ZoneOffset.UTC);
                }

                recents.add(new UserHistory(userId, bookId, lastInteractionTime));
            }
        }
        return List.copyOf(recents);
    }
}
