package com.gen3.maintenance;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodic visibility while work or remote I/O is pending; never declares a job successful. */
public final class JobProgress implements AutoCloseable {
  private static final String FORMAT =
      "maintenance.progress job={} stage={} processed={} total={} elapsedMs={} sinceProgressMs={}";
  private final String job;
  private final Logger logger;
  private final long started = System.nanoTime();
  private final ScheduledExecutorService scheduler;
  private volatile State state = new State("starting", 0, -1, started);

  public JobProgress(String job) {
    this(job, Duration.ofSeconds(10), LoggerFactory.getLogger(JobProgress.class));
  }

  JobProgress(String job, Duration interval, Logger logger) {
    if (interval.toMillis() < 1) {
      throw new IllegalArgumentException("Progress interval must be positive");
    }
    this.job = job;
    this.logger = logger;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var thread = new Thread(task, "maintenance-progress");
              thread.setDaemon(true);
              return thread;
            });
    report();
    scheduler.scheduleAtFixedRate(
        this::report,
        interval.toMillis(),
        interval.toMillis(),
        java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  public void stage(String stage) {
    State before = state;
    if (!before.stage().equals(stage)) {
      state = new State(stage, before.processed(), before.total(), System.nanoTime());
    }
  }

  public void update(long processed, long total) {
    State before = state;
    state =
        new State(
            before.stage(),
            processed,
            total,
            processed != before.processed() || total != before.total()
                ? System.nanoTime()
                : before.lastProgress());
  }

  private void report() {
    State current = state;
    long now = System.nanoTime();
    logger.info(
        FORMAT,
        job,
        current.stage(),
        current.processed(),
        current.total() < 0 ? "unknown" : Long.toString(current.total()),
        (now - started) / 1_000_000,
        (now - current.lastProgress()) / 1_000_000);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  private record State(String stage, long processed, long total, long lastProgress) {}
}
