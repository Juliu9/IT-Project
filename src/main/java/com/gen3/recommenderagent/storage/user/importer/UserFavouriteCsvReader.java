package com.gen3.recommenderagent.storage.user.importer;

import com.gen3.recommenderagent.storage.user.model.UserFavourite;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class UserFavouriteCsvReader {

    public List<UserFavourite> read(Path path) throws IOException {
        List<UserFavourite> favourites = new ArrayList<>();

        CSVFormat format = CSVFormat.DEFAULT.builder()
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
                String item = record.get("item"); // Correctly targets index mapping now

                String bookId = BookIdExtractor.extractAndNormalize(item);

                favourites.add(new UserFavourite(userId, bookId));
            }
        }
        return List.copyOf(favourites);
    }
}
