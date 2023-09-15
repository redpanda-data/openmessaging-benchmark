package io.openmessaging.benchmark.utils.distributor;

import org.junit.Ignore;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class KeyDistributorTest {

  public static final long ITERATIONS = Integer.MAX_VALUE;

  /**
   * Microbenchmark for the {@link KeyDistributor} implementations. Calls `.next()` in a tight loop.
   */
  @Test
  @Ignore("slow test")
  public void benchmarkKeyDistributors() {
    final Map<KeyDistributorType, Long> results = new HashMap<>();
    System.out.printf("Using test iterations of: %,d\n", ITERATIONS);

    for (KeyDistributorType keyType : KeyDistributorType.values()) {
      KeyDistributor distributor = KeyDistributor.build(keyType);

      System.out.println("Warming up " + keyType);
      for (long i = 0; i < ITERATIONS; i++) {
        distributor.next();
      }

      System.out.println("Testing " + keyType);
      final long start = System.nanoTime();
      for (long i = 0; i < ITERATIONS; i++) {
        distributor.next();
      }
      final long result = System.nanoTime() - start;
      results.put(keyType, result);
    }
    System.out.println("------------------------------------------------");

    // Show results.
    System.out.printf("%-20s %-20s %-20s\n", "Key Distributor", "Duration (s)", "Rate (calls/s)");
    results.forEach((k, v) -> {
      float seconds = v / 1e9f;
      long rate = (long) (ITERATIONS / seconds);
      System.out.printf("%-20s %,-20f %,-20d\n", k, seconds, rate);
    });
  }

  @Test
  @Ignore("slow test")
  public void benchmarkByteArrayKeyDistributors() {
    final Map<KeyDistributorType, Long> results = new HashMap<>();
    System.out.printf("Using test iterations of: %,d\n", ITERATIONS);

    for (KeyDistributorType keyType : KeyDistributorType.values()) {
      KeyDistributor distributor = KeyDistributor.build(keyType);

      System.out.println("Warming up " + keyType);
      for (long i = 0; i < ITERATIONS; i++) {
        distributor.next();
      }

      System.out.println("Testing " + keyType);
      final long start = System.nanoTime();
      for (long i = 0; i < ITERATIONS; i++) {
        distributor.nextBytes();
      }
      final long result = System.nanoTime() - start;
      results.put(keyType, result);
    }
    System.out.println("------------------------------------------------");

    // Show results.
    System.out.printf("%-20s %-20s %-20s\n", "Key Distributor", "Duration (s)", "Rate (calls/s)");
    results.forEach((k, v) -> {
      float seconds = v / 1e9f;
      long rate = (long) (ITERATIONS / seconds);
      System.out.printf("%-20s %,-20f %,-20d\n", k, seconds, rate);
    });
  }

  @Test
  @Ignore("slow test")
  public void testRandNanoKeyCoverage() {
    RandomNano rn = new RandomNano();
    Set<Integer> set = new HashSet<>();

    for (long i = 0; i < ITERATIONS; i++) {
      set.add(ByteBuffer.wrap(rn.nextBytes()).getInt());
    }

    System.out.println("Set cardinality: " + set.size());
  }
}
