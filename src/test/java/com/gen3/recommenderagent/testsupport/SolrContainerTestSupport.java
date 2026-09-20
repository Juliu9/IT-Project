package com.gen3.recommenderagent.testsupport;

import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.SolrInputDocument;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.DockerImageName;

public final class SolrContainerTestSupport {

  public static final String COLLECTION = "audiobooks-test";
  private static final DockerImageName SOLR_IMAGE = DockerImageName.parse("solr:9.6.0");

  private SolrContainerTestSupport() {}

  public static SolrContainer newContainer() {
    return new SolrContainer(SOLR_IMAGE).withCollection(COLLECTION);
  }

  public static SolrClient newClient(SolrContainer container) {
    return new HttpJdkSolrClient.Builder(baseUrl(container)).build();
  }

  public static String baseUrl(SolrContainer container) {
    return "http://" + container.getHost() + ":" + container.getSolrPort() + "/solr";
  }

  public static void configureSchema(SolrClient client) throws Exception {
    addField(client, "source", "string", false);
    addField(client, "title", "text_general", false);
    addField(client, "rt_title", "text_general", false);
    addField(client, "authors", "text_general", true);
    addField(client, "rt_authors", "text_general", true);
    addField(client, "description", "text_general", false);
    addField(client, "all", "text_general", false);
  }

  public static void seedBooks(SolrClient client) throws Exception {
    client.add(
        COLLECTION,
        List.of(
            book(
                "book-101",
                "Dune",
                "Frank Herbert",
                "science fiction",
                "A desert-planet science fiction epic."),
            book(
                "book-202",
                "Neuromancer",
                "William Gibson",
                "science fiction cyberpunk",
                "A cyberpunk science fiction novel."),
            book(
                "book-303",
                "Foundation",
                "Isaac Asimov",
                "science fiction space",
                "A science fiction story about a galactic empire."),
            book(
                "book-505",
                "The Martian",
                "Andy Weir",
                "science fiction survival",
                "A science fiction survival story set on Mars."),
            book(
                "book-606",
                "Snow Crash",
                "Neal Stephenson",
                "science fiction cyberpunk",
                "A fast-paced science fiction cyberpunk story."),
            book(
                "book-404",
                "The Hobbit",
                "J. R. R. Tolkien",
                "fantasy adventure",
                "A fantasy adventure.")));
    client.commit(COLLECTION);
  }

  private static void addField(SolrClient client, String name, String type, boolean multiValued)
      throws Exception {
    Map<String, Object> definition =
        Map.of(
            "name", name,
            "type", type,
            "stored", true,
            "indexed", true,
            "multiValued", multiValued);

    new SchemaRequest.AddField(definition).process(client, COLLECTION);
  }

  private static SolrInputDocument book(
      String id, String title, String author, String searchableText, String description) {
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", id);
    document.addField("source", "integration-test");
    document.addField("title", title);
    document.addField("rt_title", title);
    document.addField("authors", List.of(author));
    document.addField("rt_authors", List.of(author));
    document.addField("description", description);
    document.addField("all", title + " " + author + " " + searchableText + " " + description);
    return document;
  }
}
