package com.gen3.shared.jobruntime;

import java.time.Instant;
import java.util.UUID;

/** Creates a unique default log file for each maintenance-process launch. */
public final class MaintenanceLogFile {
  /** Prevents instantiation of this utility class. */
  private MaintenanceLogFile() {}

  /** Builds a per-run maintenance log-file property for the named job. */
  public static String property(String job) {
    return "logging.file.name=logs/"
        + job
        + "/"
        + Instant.now().toEpochMilli()
        + "-"
        + UUID.randomUUID()
        + ".log";
  }
}
