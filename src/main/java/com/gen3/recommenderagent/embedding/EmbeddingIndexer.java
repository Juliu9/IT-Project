package com.gen3.recommenderagent.embedding;

import com.gen3.recommenderagent.domain.session.SemanticQuery;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import com.gen3.recommenderagent.ranker.PreferenceSignals;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookEmbedding;
import com.gen3.recommenderagent.storage.audiobook.model.AudiobookRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Builds comparable audiobook and request text and generates normalized embeddings.
 *
 * <p>EmbeddingIndexer is deliberately persistence-agnostic. Vector storage and retrieval are
 * handled by the Qdrant storage layer.
 */
@Service
public class EmbeddingIndexer {

    private final EmbeddingModel model;

    /** Uses the configured Spring AI embedding model. */
    public EmbeddingIndexer(EmbeddingModel model) {
        this.model = model;
    }

    /** Builds the stable, human-readable catalogue representation sent to the model. */
    public String buildAudiobookText(AudiobookRecord book) {
        return List.of(
                        part("title", book.title()),
                        part("authors", book.authors()),
                        part("narrators", book.narrators()),
                        part("language", book.language()),
                        part("description", book.description()))
                .stream()
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /** Returns the normalized vector used for indexing and semantic retrieval. */
    public float[] embedAudiobook(AudiobookRecord book) {
        String text = buildAudiobookText(book);

        return text.isBlank()
                ? new float[0]
                : VectorMath.normalize(model.embed(text));
    }

    /**
     * Embeds a batch of audiobook records.
     *
     * <p>The returned embeddings retain the same order as the supplied valid books.
     */
    public List<AudiobookEmbedding> embedAudiobooks(List<AudiobookRecord> books) {
        if (books == null || books.isEmpty()) {
            return List.of();
        }

        List<AudiobookRecord> validBooks =
                books.stream()
                        .filter(book -> book != null && hasText(book.id()))
                        .toList();

        if (validBooks.isEmpty()) {
            return List.of();
        }

        List<AudiobookRecord> embeddableBooks =
                validBooks.stream()
                        .filter(book -> !buildAudiobookText(book).isBlank())
                        .toList();

        if (embeddableBooks.isEmpty()) {
            return List.of();
        }

        List<String> texts =
                embeddableBooks.stream()
                        .map(this::buildAudiobookText)
                        .toList();

        List<float[]> generated = model.embed(texts);

        if (generated.size() != embeddableBooks.size()) {
            throw new IllegalStateException(
                    "Embedding model returned an unexpected batch size");
        }

        List<AudiobookEmbedding> embeddings = new ArrayList<>(generated.size());

        for (int index = 0; index < generated.size(); index++) {
            AudiobookRecord book = embeddableBooks.get(index);
            float[] normalized = VectorMath.normalize(generated.get(index));

            embeddings.add(new AudiobookEmbedding(book, normalized));
        }

        return embeddings;
    }

    /** Returns a normalized vector for the raw request and its parsed search constraints. */
    public float[] embedRequest(SessionRequest request) {
        String text = buildRetrievalText(request);

        return text.isBlank()
                ? new float[0]
                : VectorMath.normalize(model.embed(text));
    }

    /** Combines the request, positive concepts, and negative concepts into one search direction. */
    public float[] embedRequest(SessionRequest request, PreferenceSignals signals) {
        List<float[]> positive = new ArrayList<>();

        String text = buildRetrievalText(request);

        if (!text.isBlank()) {
            positive.add(VectorMath.normalize(model.embed(text)));
        }

        if (signals != null) {
            positive.addAll(signals.positive());
        }

        return VectorMath.directionalAverage(
                positive,
                signals == null ? List.of() : signals.negative());
    }

    /** Embeds independent preference terms without mixing positive and negative instructions. */
    public List<float[]> embedPreferenceTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }

        List<String> texts =
                terms.stream()
                        .filter(this::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();

        if (texts.isEmpty()) {
            return List.of();
        }

        return model.embed(texts)
                .stream()
                .map(VectorMath::normalize)
                .toList();
    }

    /** Builds canonical request text without serializing the Java object or response text. */
    public String buildRequestText(SessionRequest request) {
        SemanticQuery semanticQuery = request.getQuery();

        return List.of(
                        part("Request", request.getRawText()),
                        part(
                                "Intent",
                                request.getIntent() == null
                                        ? null
                                        : request.getIntent().name()),
                        part("Topics", semanticQuery == null ? null : semanticQuery.getTopics()),
                        part("Genres", semanticQuery == null ? null : semanticQuery.getGenres()),
                        part("Authors", semanticQuery == null ? null : semanticQuery.getAuthors()),
                        part("Narrators", semanticQuery == null ? null : semanticQuery.getNarrators()),
                        part("Keywords", semanticQuery == null ? null : semanticQuery.getKeywords()),
                        part("Positive", semanticQuery == null ? null : semanticQuery.getPositive()),
                        part("Negative", semanticQuery == null ? null : semanticQuery.getNegative()),
                        part(
                                "Included preferences",
                                request.getPreferences() == null
                                        ? null
                                        : request.getPreferences().getInclude()),
                        part(
                                "Excluded preferences",
                                request.getPreferences() == null
                                        ? null
                                        : request.getPreferences().getExclude()),
                        part(
                                "Duration",
                                request.getFilter() == null
                                        ? null
                                        : request.getFilter().getDuration()),
                        part(
                                "Language",
                                request.getFilter() == null
                                        ? null
                                        : request.getFilter().getLanguage()))
                .stream()
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /** Builds retrieval text without preference polarity, which is scored separately. */
    public String buildRetrievalText(SessionRequest request) {
        SemanticQuery semanticQuery = request.getQuery();

        String structuredText =
                List.of(
                                part("Topics", semanticQuery == null ? null : semanticQuery.getTopics()),
                                part("Genres", semanticQuery == null ? null : semanticQuery.getGenres()),
                                part("Authors", semanticQuery == null ? null : semanticQuery.getAuthors()),
                                part("Narrators", semanticQuery == null ? null : semanticQuery.getNarrators()),
                                part("Keywords", semanticQuery == null ? null : semanticQuery.getKeywords()),
                                part(
                                        "Duration",
                                        request.getFilter() == null
                                                ? null
                                                : request.getFilter().getDuration()),
                                part(
                                        "Language",
                                        request.getFilter() == null
                                                ? null
                                                : request.getFilter().getLanguage()))
                        .stream()
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.joining("\n"));

        return structuredText.isBlank()
                ? part("Request", request.getRawText())
                : structuredText;
    }

    /** Adds a label only when a scalar value contains text. */
    private String part(String label, String value) {
        return value == null || value.isBlank()
                ? ""
                : label + ": " + value.trim();
    }

    /** Reports whether a value contains usable text. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Adds a label only when a parsed list contains text. */
    private String part(String label, List<String> values) {
        if (values == null) {
            return "";
        }

        String joined =
                values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .collect(Collectors.joining(", "));

        return part(label, joined);
    }
}