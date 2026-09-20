/** Central embedding service for vector embedding the Solr database and Input */
package com.gen3.recommenderagent.ranker;

import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.solr.common.SolrDocument;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    // Receives Spring AI's EmbeddingModel
    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    // Converts a user request into text
    // Sends that text to the embedding model
    // Used for semantic search queries
    public float[] embedRequest(SessionRequest request) {
        return embedText(buildRequestText(request));
    }

    // Converts audiobook metadata into text
    // Embeds the title, authors, description and genres
    // Used when indexing audiobook records
    public float[] embedAudiobook(SolrDocument audiobook) {
        return embedText(buildAudiobookText(audiobook));
    }

    //
    public float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text must not be blank");
        }
        return normalize(embeddingModel.embed(text));
    }

    private float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Embedding vector must not be empty");
        }

        double magnitudeSquared = 0.0;
        for (float value : vector) {
            magnitudeSquared += (double) value * value;
        }

        if (magnitudeSquared == 0.0) {
            throw new IllegalArgumentException("Cannot normalize a zero vector");
        }

        double magnitude = Math.sqrt(magnitudeSquared);
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = (float) (vector[index] / magnitude);
        }
        return normalized;
    }

    String buildRequestText(SessionRequest request) {
        List<String> parts = new ArrayList<>();
        if (request != null) {
            add(parts, request.getRawText());
            if (request.getIntent() != null) {
                add(parts, "Intent: " + request.getIntent().name());
            }
            Query query = request.getQuery();
            if (query != null) {
                addValues(parts, "Topics", query.getTopics());
                addValues(parts, "Genres", query.getGenres());
                addValues(parts, "Authors", query.getAuthors());
                addValues(parts, "Keywords", query.getKeywords());
            }
        }
        return String.join(". ", parts);
    }

    String buildAudiobookText(SolrDocument audiobook) {
        if (audiobook == null) {
            throw new IllegalArgumentException("Audiobook must not be null");
        }

        List<String> parts = new ArrayList<>();
        addField(parts, "Title", audiobook.getFieldValue("title"));
        addField(parts, "Authors", audiobook.getFieldValue("authors"));
        addField(parts, "Description", audiobook.getFieldValue("description"));
        addField(parts, "Genres", audiobook.getFieldValue("genres"));
        return String.join(". ", parts);
    }

    private void addValues(List<String> parts, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            add(parts, label + ": " + String.join(", ", values));
        }
    }

    private void addField(List<String> parts, String label, Object value) {
        if (value != null) {
            add(parts, label + ": " + value);
        }
    }

    private void add(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(Objects.requireNonNull(value).trim());
        }
    }
}
