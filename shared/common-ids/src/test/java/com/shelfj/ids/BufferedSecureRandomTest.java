package com.shelfj.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The source here writes 0, 1, 2, … instead of random bytes, so a value handed out twice — the
 * failure that would let two ids collide — shows up as a plain duplicate.
 */
class BufferedSecureRandomTest {

  private final AtomicLong written = new AtomicLong();
  private final AtomicInteger refills = new AtomicInteger();

  private final Consumer<byte[]> counting =
      bytes -> {
        refills.incrementAndGet();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= Long.BYTES) {
          buffer.putLong(written.getAndIncrement());
        }
      };

  @Test
  void aSpentBatchIsReplacedWholeNeverRewound() {
    BufferedSecureRandom random = new BufferedSecureRandom(() -> counting, 1, 4);

    long[] drawn = new long[10];
    for (int i = 0; i < drawn.length; i++) {
      drawn[i] = random.nextLong();
    }

    assertEquals("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]", Arrays.toString(drawn));
    assertEquals(3, refills.get());
  }

  /** A source shared between stripes would bring back the lock the stripes exist to avoid. */
  @Test
  void eachStripeRefillsFromItsOwnSource() {
    AtomicInteger sourcesMade = new AtomicInteger();

    new BufferedSecureRandom(
        () -> {
          sourcesMade.incrementAndGet();
          return counting;
        },
        16,
        8);

    assertEquals(16, sourcesMade.get());
  }

  /**
   * Tiny batches and few stripes, so threads constantly race to replace the same spent batch. The
   * losers' batches are dropped, which is fine; a value reaching two callers is not.
   */
  @Test
  void noValueReachesTwoCallersWhileThreadsRaceToRefill() {
    int threads = 16;
    int perThread = 50_000;
    BufferedSecureRandom random = new BufferedSecureRandom(() -> counting, 2, 8);
    long[][] byThread = new long[threads][perThread];
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int t = 0; t < threads; t++) {
        long[] mine = byThread[t];
        pool.submit(
            () -> {
              start.await();
              for (int i = 0; i < perThread; i++) {
                mine[i] = random.nextLong();
              }
              return null;
            });
      }
      start.countDown();
    }

    long[] all = Arrays.stream(byThread).flatMapToLong(Arrays::stream).sorted().toArray();
    assertEquals(threads * perThread, all.length);
    for (int i = 1; i < all.length; i++) {
      assertTrue(all[i] != all[i - 1], "value " + all[i] + " was handed out twice");
    }
    assertTrue(all[all.length - 1] < written.get(), "every value came from the source");
  }

  @Test
  void stripesMustBeAPowerOfTwo() {
    assertThrows(
        IllegalArgumentException.class, () -> new BufferedSecureRandom(() -> counting, 3, 8));
    assertThrows(
        IllegalArgumentException.class, () -> new BufferedSecureRandom(() -> counting, 0, 8));
  }
}
