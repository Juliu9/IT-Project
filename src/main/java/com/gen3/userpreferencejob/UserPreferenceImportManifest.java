package com.gen3.userpreferencejob;

import com.gen3.catalogue.snapshot.BookVectorSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;

/** Binds write mode to the exact inputs and outcome reviewed in a preceding dry run. */
public class UserPreferenceImportManifest {
  private final Path path;

  public UserPreferenceImportManifest(Path path) {
    this.path = path;
  }

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

  private Properties properties(
      Path favourites,
      Path history,
      BookVectorSnapshot.Identity snapshot,
      UserPreferenceAggregatorService.Result plan)
      throws IOException {
    var properties = new Properties();
    file(properties, "favourites", favourites);
    file(properties, "history", history);
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

  private void file(Properties properties, String prefix, Path file) throws IOException {
    Path normalized = file.toAbsolutePath().normalize();
    properties.setProperty(prefix + ".file", normalized.toString());
    properties.setProperty(prefix + ".sha256", sha256(normalized));
  }

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
