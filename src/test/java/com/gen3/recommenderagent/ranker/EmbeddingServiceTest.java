package com.gen3.recommenderagent.ranker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.recommenderagent.domain.Intent;
import com.gen3.recommenderagent.domain.session.Query;
import com.gen3.recommenderagent.domain.session.SessionRequest;
import java.util.List;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Test
    void shouldEmbedRequestUsingRawTextAndParsedFields() {
        EmbeddingService service = new EmbeddingService(embeddingModel);
        float[] rawEmbedding = new float[] { 0.1f, 0.2f };
        when(embeddingModel.embed(
                "I want a mystery audiobook. Intent: NEW_RECOMMENDATION. Genres: mystery. Authors: "
                        + "Jane Doe. Keywords: atmospheric"))
                .thenReturn(rawEmbedding);

        SessionRequest request = new SessionRequest();
        request.setRawText("I want a mystery audiobook");
        request.setIntent(Intent.NEW_RECOMMENDATION);
        Query query = new Query();
        query.setGenres(List.of("mystery"));
        query.setAuthors(List.of("Jane Doe"));
        query.setKeywords(List.of("atmospheric"));
        request.setQuery(query);

        assertArrayEquals(new float[] { 0.4472136f, 0.8944272f }, service.embedRequest(request));
        verify(embeddingModel)
                .embed(
                        "I want a mystery audiobook. Intent: NEW_RECOMMENDATION. Genres: mystery. Authors: "
                                + "Jane Doe. Keywords: atmospheric");
    }

    @Test
    void shouldEmbedAudiobookUsingSearchableMetadata() {
        EmbeddingService service = new EmbeddingService(embeddingModel);
        float[] rawEmbedding = new float[] { 0.3f, 0.4f };
        when(embeddingModel.embed(
                "Title: The Hidden Door. Authors: Jane Doe. Description: A mystery in London. "
                        + "Genres: mystery"))
                .thenReturn(rawEmbedding);

        SolrDocument audiobook = new SolrDocument();
        audiobook.setField("title", "The Hidden Door");
        audiobook.setField("authors", "Jane Doe");
        audiobook.setField("description", "A mystery in London");
        audiobook.setField("genres", "mystery");

        assertArrayEquals(new float[] { 0.6f, 0.8f }, service.embedAudiobook(audiobook));
    }

    @Test
    void shouldRejectBlankEmbeddingText() {
        EmbeddingService service = new EmbeddingService(embeddingModel);

        assertThrows(IllegalArgumentException.class, () -> service.embedText("  "));
    }

    @Test
    void shouldRejectNullAudiobook() {
        EmbeddingService service = new EmbeddingService(embeddingModel);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.embedAudiobook(null));

        assertEquals("Audiobook must not be null", exception.getMessage());
    }

        @Test
        void shouldRejectZeroEmbeddingVector() {
                EmbeddingService service = new EmbeddingService(embeddingModel);
                when(embeddingModel.embed("valid text")).thenReturn(new float[] {0.0f, 0.0f});

                assertThrows(IllegalArgumentException.class, () -> service.embedText("valid text"));
        }
}
