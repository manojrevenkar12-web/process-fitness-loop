package io.github.mkp.pfl.worker;

import io.github.mkp.pfl.camunda.ActivatedJob;
import io.github.mkp.pfl.camunda.CamundaClient;
import io.github.mkp.pfl.camunda.CamundaException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Polls the cluster for jobs of the registered types and hands each job to its handler on a
 * virtual thread. One poller per type; long polling keeps idle traffic low.
 */
public final class JobWorker implements AutoCloseable {

  /** Counters per job type. */
  public record Counts(long completed, long bpmnErrors, long failed) {}

  private final CamundaClient client;
  private final String name;
  private final Map<String, JobHandler> handlers;
  private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean running = new AtomicBoolean();
  private final Map<String, LongAdder[]> counts = new ConcurrentHashMap<>();
  private final Duration lock;

  public JobWorker(CamundaClient client, String name, Map<String, JobHandler> handlers, Duration lock) {
    this.client = client;
    this.name = name;
    this.handlers = Map.copyOf(handlers);
    this.lock = lock;
    handlers.keySet().forEach(t -> counts.put(t, new LongAdder[] {new LongAdder(), new LongAdder(), new LongAdder()}));
  }

  public JobWorker start() {
    if (running.compareAndSet(false, true)) {
      handlers.forEach((type, handler) -> pool.submit(() -> poll(type, handler)));
    }
    return this;
  }

  public Counts counts(String type) {
    LongAdder[] c = counts.get(type);
    return new Counts(c[0].sum(), c[1].sum(), c[2].sum());
  }

  private void poll(String type, JobHandler handler) {
    while (running.get()) {
      try {
        List<ActivatedJob> jobs = client.activate(type, name, 16, lock, Duration.ofSeconds(5));
        for (ActivatedJob job : jobs) {
          pool.submit(() -> work(job, handler));
        }
      } catch (CamundaException e) {
        sleep(Duration.ofSeconds(1));
      }
    }
  }

  private void work(ActivatedJob job, JobHandler handler) {
    LongAdder[] c = counts.get(job.type());
    JobResult result;
    try {
      result = handler.handle(job);
    } catch (RuntimeException e) {
      result = new JobResult.Fail("handler crashed: " + e, Duration.ofSeconds(10));
    }
    try {
      switch (result) {
        case JobResult.Complete done -> {
          client.complete(job, done.variables());
          c[0].increment();
        }
        case JobResult.BpmnError error -> {
          client.throwError(job, error.code(), error.message());
          c[1].increment();
        }
        case JobResult.Fail fail -> {
          client.fail(job, Math.max(0, job.retries() - 1), fail.message(), fail.backoff());
          c[2].increment();
        }
      }
    } catch (CamundaException e) {
      // the job lock runs out and the job is offered again; the handlers are safe to repeat
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    running.set(false);
    pool.shutdown();
    try {
      pool.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
