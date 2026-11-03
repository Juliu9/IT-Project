package com.gen3.shared.jobruntime;

import java.time.Instant;
import java.util.UUID;

/** Creates a unique default log file for each maintenance-process launch. */
public final class MaintenanceLogFile {
  private MaintenanceLogFile() {}

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
