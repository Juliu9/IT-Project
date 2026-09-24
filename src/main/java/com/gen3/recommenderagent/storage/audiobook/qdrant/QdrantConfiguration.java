package com.gen3.recommenderagent.storage.audiobook.qdrant;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the native Qdrant client from environment-backed application properties. */
@Configuration
public class QdrantConfiguration {

  /**
   * Creates the gRPC client used by the repository and Spring AI vector-store integration.
   *
   * <p>{@code qdrant.url} may be a full cloud URL. Qdrant's Java client receives its hostname and
   * TLS setting separately, while {@code qdrant.grpc-port} identifies the gRPC endpoint.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(QdrantClient.class)
  public QdrantClient qdrantClient(
      @Value("${qdrant.url}") String qdrantUrl,
      @Value("${qdrant.grpc-port:6334}") int grpcPort,
      @Value("${qdrant.api-key:}") String apiKey) {
    URI uri = parseUrl(qdrantUrl);
    boolean useTls = "https".equalsIgnoreCase(uri.getScheme());
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(uri.getHost(), grpcPort, useTls);
    if (apiKey != null && !apiKey.isBlank()) {
      builder.withApiKey(apiKey.trim());
    }
    return new QdrantClient(builder.build());
  }

  /** Ensures a scheme is present so local hostnames and full cloud URLs are both accepted. */
  private URI parseUrl(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Qdrant URL must not be blank");
    }
    String normalized = value.contains("://") ? value.trim() : "http://" + value.trim();
    URI uri = URI.create(normalized);
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("Qdrant URL must contain a hostname");
    }
    return uri;
  }
}
