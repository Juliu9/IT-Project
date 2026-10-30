package com.gen3.shared.jobruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

class JobProgressTest {
  @Test
  void reportsDuringPendingWorkAndStopsAfterClose() throws Exception {
    var logger = mock(Logger.class);
    var reports = new CountDownLatch(3);
    var laterReports = new CountDownLatch(2);
    var closed = new AtomicBoolean();
    doAnswer(
            call -> {
              if (closed.get()) {
                laterReports.countDown();
              }
              reports.countDown();
              return null;
            })
        .when(logger)
        .info(anyString(), any(Object[].class));
    try (var progress = new JobProgress("demo", Duration.ofMillis(20), logger)) {
      progress.stage("fetching vectors");
      progress.update(12, 100);
      assertThat(reports.await(2, TimeUnit.SECONDS)).isTrue();
    }
    closed.set(true);
    assertThat(laterReports.await(100, TimeUnit.MILLISECONDS)).isFalse();
    var values = ArgumentCaptor.forClass(Object[].class);
    verify(logger, org.mockito.Mockito.atLeast(3)).info(anyString(), values.capture());
    assertThat(values.getAllValues())
        .anySatisfy(
            fields -> {
              assertThat(fields[0]).isEqualTo("demo");
              assertThat(fields[1]).isEqualTo("fetching vectors");
              assertThat(fields[2]).isEqualTo(12L);
              assertThat(fields[3]).isEqualTo("100");
            });
  }

  @Test
  void closesOnFailureWithoutReportingSuccess() throws Exception {
    var logger = mock(Logger.class);
    var reports = new CountDownLatch(2);
    doAnswer(
            call -> {
              reports.countDown();
              return null;
            })
        .when(logger)
        .info(anyString(), any(Object[].class));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> {
              try (var progress = new JobProgress("failing", Duration.ofMillis(20), logger)) {
                progress.stage("waiting for database");
                assertThat(reports.await(2, TimeUnit.SECONDS)).isTrue();
                throw new java.io.IOException("unavailable");
              }
            })
        .isInstanceOf(java.io.IOException.class);
    var values = ArgumentCaptor.forClass(Object[].class);
    verify(logger, org.mockito.Mockito.atLeast(2)).info(anyString(), values.capture());
    assertThat(values.getAllValues())
        .allSatisfy(
            fields -> {
              assertThat(fields[1]).isNotEqualTo("completed");
              assertThat(fields[3]).isEqualTo("unknown");
            });
  }
}
