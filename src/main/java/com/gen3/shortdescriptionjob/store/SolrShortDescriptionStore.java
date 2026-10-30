package com.gen3.shortdescriptionjob.store;

import com.gen3.catalogue.model.StoredAudiobook;
import com.gen3.catalogue.solr.SolrAudiobookRepository;
import com.gen3.shortdescriptionjob.ShortDescriptionMode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Selects eligible summaries and applies version-checked updates without replacing catalogue data.
 */
@Component
@ConditionalOnProperty(name = "audiobook.database", havingValue = "solr")
public class SolrShortDescriptionStore implements AudiobookEnrichmentStore {
  private final SolrClient client;
  private final SolrAudiobookRepository catalogue;
  private final String collection;

  public SolrShortDescriptionStore(
      SolrClient client,
      SolrAudiobookRepository catalogue,
      @Value("${solr.collection}") String collection) {
    this.client = client;
    this.catalogue = catalogue;
    this.collection = collection;
  }

  @Override
  public List<StoredAudiobook> findRecordsForShortDescription(
      int maximumRecords, ShortDescriptionMode mode) throws IOException {
    List<StoredAudiobook> missing = new ArrayList<>();
    String afterId = null;
    while (missing.size() < maximumRecords) {
      var page = catalogue.findAllBooks(afterId, 500);
      for (var book : page) {
        var stored = new StoredAudiobook(book, book.id());
        if ((mode == ShortDescriptionMode.REBUILD_ALL
                || book.shortDescription() == null
                || book.shortDescription().isBlank())
            && AudiobookEnrichmentStore.eligible(stored)) {
          missing.add(stored);
          if (missing.size() == maximumRecords) {
            break;
          }
        }
      }
      if (!page.isEmpty()) {
        afterId = page.getLast().id();
      }
      if (page.size() < 500) {
        break;
      }
    }
    return missing;
  }

  @Override
  public void updateShortDescriptions(
      List<ShortDescriptionUpdate> updates, ShortDescriptionMode mode) throws IOException {
    if (updates == null || updates.isEmpty()) {
      return;
    }
    try {
      var valid =
          updates.stream()
              .filter(
                  update ->
                      update != null
                          && update.storageReference() != null
                          && !update.storageReference().isBlank()
                          && update.description() != null
                          && !update.description().isBlank())
              .toList();
      for (int start = 0; start < valid.size(); start += 500) {
        var batch = valid.subList(start, Math.min(start + 500, valid.size()));
        var parameters = new org.apache.solr.common.params.ModifiableSolrParams();
        parameters.set("fl", "id,short_description,_version_");
        var currentRecords =
            client.getById(
                collection,
                batch.stream().map(ShortDescriptionUpdate::storageReference).distinct().toList(),
                parameters);
        var byId = new java.util.HashMap<String, org.apache.solr.common.SolrDocument>();
        for (var current : currentRecords) {
          byId.put(String.valueOf(current.getFieldValue("id")), current);
        }
        List<SolrInputDocument> documents = new ArrayList<>();
        for (var update : batch) {
          var current = byId.get(update.storageReference());
          if (current == null) {
            continue;
          }
          Object description = current.getFieldValue("short_description");
          if (mode == ShortDescriptionMode.MISSING_ONLY
              && description != null
              && !description.toString().isBlank()) {
            continue;
          }
          Object version = current.getFieldValue("_version_");
          if (!(version instanceof Number)) {
            throw new IOException("Solr must return _version_ for safe summary updates");
          }
          SolrInputDocument document = new SolrInputDocument();
          document.addField("id", update.storageReference());
          document.addField("_version_", version);
          document.addField("short_description", Map.of("set", update.description()));
          documents.add(document);
        }
        if (!documents.isEmpty()) {
          client.add(collection, documents);
          client.commit(collection);
        }
        org.slf4j.LoggerFactory.getLogger(getClass())
            .info(
                "short.description.write.progress processed={} total={}",
                start + batch.size(),
                valid.size());
      }
    } catch (SolrServerException exception) {
      throw new IOException(
          "Could not safely update Solr short descriptions; rerun to fill remaining missing summaries",
          exception);
    }
  }
}
