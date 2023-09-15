package io.openmessaging.benchmark.utils.distributor;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class KeyDistributorTest {

  public static final long ITERATIONS = Integer.MAX_VALUE * 2L;

  /**
   * Microbenchmark for the {@link KeyDistributor} implementations. Calls `.next()` in a tight loop.
   */
  @Test
  public void benchmarkKeyDistributors() {
    final Map<KeyDistributorType, Long> results = new HashMap<>();
    System.out.println("Using test iterations of: " + ITERATIONS);

    for (KeyDistributorType keyType : KeyDistributorType.values()) {
      KeyDistributor distributor = KeyDistributor.build(keyType);
      // 3 times around the sun...
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
      float rate = ITERATIONS / seconds;
      System.out.printf("%-20s %-20f %-20f\n", k, seconds, rate);
    });
  }
}
