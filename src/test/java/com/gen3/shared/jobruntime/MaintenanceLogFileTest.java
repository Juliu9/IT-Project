package com.gen3.shared.jobruntime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaintenanceLogFileTest {
  @Test
  void createsUniqueLogFilesInsideTheJobDirectory() {
    String first = MaintenanceLogFile.property("snapshot");
    String second = MaintenanceLogFile.property("snapshot");

    assertThat(first).matches("logging\\.file\\.name=logs/snapshot/[0-9]+-[0-9a-f-]+\\.log");
    assertThat(second).isNotEqualTo(first);
  }
}
