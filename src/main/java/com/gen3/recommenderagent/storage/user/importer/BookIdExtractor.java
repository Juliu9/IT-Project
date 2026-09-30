package com.gen3.recommenderagent.storage.user.importer;

import java.io.IOException;

public class BookIdExtractor {

    public static String extractAndNormalize(String itemField) throws IOException {
        if (itemField == null || itemField.isBlank()) {
            throw new IOException("Book item field must not be blank");
        }

        // Strips surounding literal escape quotes if left behind by parser mapping
        String cleanedItem = itemField.replace("\"", "").trim();

        // Splits item component: "rnib:book:14186_part1:the triumph..." -> max 4 chunks
        String[] parts = cleanedItem.split(":", 4);

        if (parts.length < 3 || !"book".equalsIgnoreCase(parts[1]) || parts[2].isBlank()) {
            throw new IOException("Invalid book item format pattern: " + itemField);
        }

        String provider = parts[0].toLowerCase().trim(); // "rnib"
        String rawId = parts[2].trim();     // "14186_PART1"

        // Generates the uniform production ID format: "rnib_14186_PART1"
        return provider + "_" + rawId;
    }
}
