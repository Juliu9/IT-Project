package com.gen3.userpreferencejob;

import com.gen3.shared.audiobookcatalogue.qdrant.QdrantConfig;
import com.gen3.shared.audiobookcatalogue.snapshot.BookVectorSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Binds write mode to the exact inputs and outcome reviewed in a preceding dry run. */
public class UserPreferenceImportManifest {
  private final Path path;
  private final Environment environment;

  /** Selects the manifest file used to bind writes to an approved dry run. */
  public UserPreferenceImportManifest(Path path, Environment environment) {
    this.path = path;
    this.environment = environment;
  }

  /**
   * Writes a dry-run manifest or rejects write inputs that differ from the approved hashes and
   * counts.
   */
  public void approve(
      String mode,
      Path favourites,
      Path history,
      BookVectorSnapshot snapshot,
      UserPreferenceAggregatorService.Result plan)
      throws IOException {
    Properties actual = properties(favourites, history, snapshot.identity(), plan);
    if ("dry-run".equals(mode)) {
      write(actual);
      return;
    }
    Properties approved = new Properties();
    if (!Files.isRegularFile(path)) {
      throw new IOException("Write mode requires an approved dry-run manifest: " + path);
    }
    try (InputStream input = Files.newInputStream(path)) {
      approved.load(input);
    }
    for (String name : actual.stringPropertyNames()) {
      if (!actual.getProperty(name).equals(approved.getProperty(name))) {
        throw new IOException(
            "Import input or outcome differs from the approved dry run (" + name + ")");
      }
    }
  }

  /** Builds the input hashes, snapshot identity, and outcome counts bound by import approval. */
  private Properties properties(
      Path favourites,
      Path history,
      BookVectorSnapshot.Identity snapshot,
      UserPreferenceAggregatorService.Result plan)
      throws IOException {
    var properties = new Properties();
    file(properties, "favourites", favourites);
    file(properties, "history", history);
    String backend = snapshot.backend();
    properties.setProperty("destination.backend", backend);
    properties.setProperty("destination.endpoint", destinationEndpoint(backend));
    properties.setProperty(
        "destination.collection",
        environment.getProperty(backend + ".user-preference-collection", "user"));
    properties.setProperty("snapshot.file", snapshot.file());
    properties.setProperty("snapshot.sha256", snapshot.sha256());
    properties.setProperty("snapshot.backend", snapshot.backend());
    properties.setProperty("snapshot.collection", snapshot.collection());
    properties.setProperty("snapshot.model", snapshot.model());
    properties.setProperty("snapshot.dimensions", Integer.toString(snapshot.dimensions()));
    properties.setProperty("snapshot.created-at", snapshot.createdAt());
    properties.setProperty("outcome.full-updates", Long.toString(plan.fullUpdates()));
    properties.setProperty("outcome.partial-updates", Long.toString(plan.partialUpdates()));
    properties.setProperty("outcome.unmapped-users", Integer.toString(plan.unmappedUsers().size()));
    return properties;
  }

  /** Uses the actual Qdrant gRPC address or a credential-free profile server URL. */
  private String destinationEndpoint(String backend) {
    String url = environment.getRequiredProperty(backend + ".url");
    try {
      if ("qdrant".equals(backend)) {
        URI uri = QdrantConfig.parseUrl(url);
        String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
        return scheme
            + "://"
            + uri.getHost()
            + ":"
            + environment.getProperty("qdrant.grpc-port", Integer.class, 6334);
      }
      URI uri = URI.create(url);
      if (uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null) {
        return uri.toString();
      }
    } catch (IllegalArgumentException ignored) {
      // URI parse errors can contain credentials from the supplied URL.
    }
    throw new IllegalArgumentException("Invalid profile destination URL for " + backend);
  }

  /** Adds the normalized input path and its checksum to the manifest properties. */
  private void file(Properties properties, String prefix, Path file) throws IOException {
    Path normalized = file.toAbsolutePath().normalize();
    properties.setProperty(prefix + ".file", normalized.toString());
    properties.setProperty(prefix + ".sha256", sha256(normalized));
  }

  /** Writes the dry-run approval manifest and restricts its permissions where supported. */
  private void write(Properties properties) throws IOException {
    Path parent = path.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    try (OutputStream output = Files.newOutputStream(path)) {
      properties.store(output, "Approve this exact dry-run result by rerunning in write mode");
    }
    try {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX platforms retain their normal owner ACLs.
    }
  }

  /** Calculates the input file checksum without loading the whole file into memory. */
  private String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
