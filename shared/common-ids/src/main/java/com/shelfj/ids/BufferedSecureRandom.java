package com.shelfj.ids;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Secure random longs handed out from pre-filled batches, so a draw is an array read and a CAS
 * instead of a trip through a SecureRandom lock.
 *
 * <p>Batches are striped by thread, and each stripe refills from its own source, so neither a draw
 * nor a refill waits on another stripe. The sources must really be independent: every {@code
 * NativePRNG} instance funnels into one JVM-wide lock, which capped throughput and put
 * millisecond-scale stalls in the tail until each stripe got its own DRBG.
 *
 * <p>A spent batch is replaced whole, never rewound, so no value is handed out twice — two ids that
 * share a millisecond and counter depend on that. When threads race to replace the same spent
 * batch, one fresh batch wins and the rest are dropped unused.
 */
final class BufferedSecureRandom {

  private static final long FIBONACCI = 0x9E37_79B9_7F4A_7C15L;

  private final Stripe[] stripes;
  private final int stripeMask;
  private final int batchSize;

  /**
   * @param newSource called once per stripe for a source that fills an array with secure random
   *     bytes, e.g. {@code SecureRandom::nextBytes}; each source must be thread-safe
   * @param stripes number of independent batches; a power of two
   * @param batchSize longs fetched from a source per refill
   */
  BufferedSecureRandom(Supplier<Consumer<byte[]>> newSource, int stripes, int batchSize) {
    if (stripes < 1 || Integer.bitCount(stripes) != 1) {
      throw new IllegalArgumentException("stripes must be a power of two, was " + stripes);
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive, was " + batchSize);
    }
    this.stripes = new Stripe[stripes];
    for (int i = 0; i < stripes; i++) {
      this.stripes[i] = new Stripe(newSource.get());
    }
    this.stripeMask = stripes - 1;
    this.batchSize = batchSize;
  }

  /**
   * @return the next secure random long
   */
  long nextLong() {
    Stripe stripe =
        stripes[(int) ((Thread.currentThread().threadId() * FIBONACCI) >>> 32) & stripeMask];
    while (true) {
      Batch batch = stripe.batch.get();
      int index = batch.claim();
      if (index >= 0) {
        return batch.values[index];
      }
      stripe.batch.compareAndSet(batch, Batch.filledFrom(stripe.source, batchSize));
    }
  }

  private static final class Stripe {
    private final Consumer<byte[]> source;
    private final AtomicReference<Batch> batch = new AtomicReference<>(new Batch(0));

    Stripe(Consumer<byte[]> source) {
      this.source = source;
    }
  }

  private static final class Batch {
    private final long[] values;
    private final AtomicInteger next = new AtomicInteger();

    private Batch(int size) {
      this.values = new long[size];
    }

    static Batch filledFrom(Consumer<byte[]> source, int size) {
      byte[] bytes = new byte[size * Long.BYTES];
      source.accept(bytes);
      Batch batch = new Batch(size);
      ByteBuffer.wrap(bytes).asLongBuffer().get(batch.values);
      return batch;
    }

    /**
     * @return an index no other caller has claimed, or -1 once every value has been handed out
     */
    int claim() {
      int index;
      do {
        index = next.get();
        if (index >= values.length) {
          return -1;
        }
      } while (!next.compareAndSet(index, index + 1));
      return index;
    }
  }
}
