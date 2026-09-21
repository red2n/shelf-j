package com.storeql.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The abuse case integration tests share: the same call made many times at once. */
public final class Concurrency {

  private Concurrency() {}

  /**
   * Runs a call {@code n} times at once, released together, and returns each result.
   *
   * @param n how many at once
   * @param call the call, e.g. a request returning its status
   * @return the results, in submission order
   * @throws Exception the first call's failure, or a timeout after two minutes
   */
  public static <R> List<R> inParallel(int n, Callable<R> call) throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
      try {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<R>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
          futures.add(
              pool.submit(
                  () -> {
                    start.await();
                    return call.call();
                  }));
        }
        start.countDown();
        List<R> out = new ArrayList<>();
        for (Future<R> f : futures) out.add(f.get(120, TimeUnit.SECONDS));
        return out;
      } finally {
        pool.shutdownNow();
      }
    }
  }
}
