package io.openmessaging.benchmark.utils.distributor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class PartitionChooserTest {

    private static final int DRAWS = 100_000;

    private static int[] draw(PartitionChooser chooser, int partitionCount) {
        Random random = new Random(42);
        int[] counts = new int[partitionCount];
        for (int i = 0; i < DRAWS; i++) {
            counts[chooser.next(random)]++;
        }
        return counts;
    }

    private static Map<String, Double> weights(Object... keysAndShares) {
        Map<String, Double> weights = new HashMap<>();
        for (int i = 0; i < keysAndShares.length; i += 2) {
            weights.put((String) keysAndShares[i], (Double) keysAndShares[i + 1]);
        }
        return weights;
    }

    @Test
    public void hotPartitionAndRestSpreadEvenly() {
        int[] counts = draw(new PartitionChooser(weights("3", 0.91, "*", 0.09), 8), 8);
        assertEquals(0.91, counts[3] / (double) DRAWS, 0.01);
        for (int partition = 0; partition < 8; partition++) {
            if (partition != 3) {
                assertEquals(0.09 / 7, counts[partition] / (double) DRAWS, 0.005);
            }
        }
    }

    @Test
    public void sharesAreRelative() {
        int[] counts = draw(new PartitionChooser(weights("0", 3.0, "1", 1.0), 2), 2);
        assertEquals(0.75, counts[0] / (double) DRAWS, 0.01);
    }

    @Test
    public void unlistedPartitionsGetNothingWithoutStar() {
        int[] counts = draw(new PartitionChooser(weights("1", 1.0), 4), 4);
        assertEquals(DRAWS, counts[1]);
    }

    private static void assertFails(Runnable check, String expectedMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, check::run);
        assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
    }

    @Test
    public void partitionBeyondTopicFails() {
        assertFails(() -> new PartitionChooser(weights("8", 1.0), 8), "lists partition 8 but the topic has 8");
    }

    @Test
    public void allZeroSharesFail() {
        assertFails(() -> new PartitionChooser(weights("*", 0.0), 4), "gives no traffic");
    }

    @Test
    public void nonNumericKeyFails() {
        assertFails(() -> PartitionChooser.checkWeights(weights("hot", 1.0)), "key 'hot' must be a partition number");
    }

    @Test
    public void keyOutsideIntRangeFails() {
        assertFails(() -> PartitionChooser.checkWeights(weights("99999999999", 1.0)),
                "key '99999999999' must be a partition number");
    }

    @Test
    public void nonCanonicalKeyFails() {
        assertFails(() -> PartitionChooser.checkWeights(weights("3", 0.9, "03", 0.1)),
                "key '03' must be a partition number");
    }

    @Test
    public void negativeShareFails() {
        assertFails(() -> PartitionChooser.checkWeights(weights("0", -0.5)), "must be a non-negative number");
    }
}
