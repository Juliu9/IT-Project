package com.gen3.recommenderagent.embedding;

import com.gen3.recommenderagent.ranker.AudiobookRecord;
import com.gen3.recommenderagent.ranker.AudiobookRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the Solr vector catalogue before the application accepts searches.
 */
@Component
public class AudiobookEmbeddingStartupIndexer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudiobookEmbeddingStartupIndexer.class);

    private final AudiobookRepository audiobookRepository;
    private final EmbeddingIndexer embeddingIndexer;
    private final int pageSize;
    private final boolean enabled;

    public AudiobookEmbeddingStartupIndexer(
            AudiobookRepository audiobookRepository,
            EmbeddingIndexer embeddingIndexer,
            @Value("${solr.embedding-index-page-size:100}") int pageSize,
            @Value("${solr.embedding-index-on-startup:true}") boolean enabled) {
        this.audiobookRepository = audiobookRepository;
        this.embeddingIndexer = embeddingIndexer;
        this.pageSize = Math.max(pageSize, 1);
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!enabled) {
            LOGGER.info("Audiobook embedding startup indexing is disabled");
            return;
        }

        int offset = 0;
        int indexed = 0;
        while (true) {
            List<AudiobookRecord> page = audiobookRepository.findAllBooks(offset, pageSize);
            if (page.isEmpty()) {
                break;
            }

            List<AudiobookRepository.AudiobookEmbedding> batch = new ArrayList<>();
            for (AudiobookRecord book : page) {
                float[] vector = embeddingIndexer.ensureAudiobookEmbedding(book);
                if (vector.length > 0) {
                    batch.add(new AudiobookRepository.AudiobookEmbedding(book, vector));
                }
            }
            audiobookRepository.indexEmbeddings(batch);
            indexed += batch.size();
            offset += page.size();
            LOGGER.info("Indexed {} audiobook embeddings", indexed);

            if (page.size() < pageSize) {
                break;
            }
        }

        LOGGER.info("Audiobook embedding startup indexing complete: {} vectors", indexed);
    }
}
