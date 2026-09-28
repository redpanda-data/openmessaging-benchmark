/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openmessaging.benchmark.utils.distributor;

import java.util.Map;
import java.util.Random;

/**
 * Picks the partition for each message from per-partition traffic shares, e.g. {"3": 0.91, "*": 0.09}: listed
 * partitions get their share and "*" is spread evenly over the unlisted ones. Shares are relative, so they need not
 * sum to 1.
 */
public class PartitionChooser {

    public static final String OTHER_PARTITIONS = "*";

    private final double[] cumulativeShares;

    public PartitionChooser(Map<String, Double> weights, int partitionCount) {
        checkWeights(weights);

        double[] shares = new double[partitionCount];
        boolean[] listed = new boolean[partitionCount];
        int listedCount = 0;
        for (Map.Entry<String, Double> weight : weights.entrySet()) {
            if (OTHER_PARTITIONS.equals(weight.getKey())) {
                continue;
            }
            int partition = Integer.parseInt(weight.getKey());
            if (partition >= partitionCount) {
                throw new IllegalArgumentException(String.format(
                    "partitionWeights lists partition %d but the topic has %d partitions", partition, partitionCount));
            }
            shares[partition] = weight.getValue();
            listed[partition] = true;
            listedCount++;
        }

        Double otherShare = weights.get(OTHER_PARTITIONS);
        if (otherShare != null && listedCount < partitionCount) {
            for (int partition = 0; partition < partitionCount; partition++) {
                if (!listed[partition]) {
                    shares[partition] = otherShare / (partitionCount - listedCount);
                }
            }
        }

        cumulativeShares = new double[partitionCount];
        double total = 0;
        for (int partition = 0; partition < partitionCount; partition++) {
            total += shares[partition];
            cumulativeShares[partition] = total;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("partitionWeights gives no traffic to any partition of the topic");
        }
    }

    /**
     * @return a partition drawn according to the configured shares
     */
    public int next(Random random) {
        double draw = random.nextDouble() * cumulativeShares[cumulativeShares.length - 1];
        int low = 0;
        int high = cumulativeShares.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cumulativeShares[mid] > draw) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    /**
     * Checks what can be checked without the topic: keys are partition numbers in canonical form (so "3" and "03"
     * cannot both name partition 3) or "*", and shares are non-negative.
     */
    public static void checkWeights(Map<String, Double> weights) {
        for (Map.Entry<String, Double> weight : weights.entrySet()) {
            String key = weight.getKey();
            if (!OTHER_PARTITIONS.equals(key) && !isPartitionNumber(key)) {
                throw new IllegalArgumentException(String.format(
                    "partitionWeights key '%s' must be a partition number or \"%s\"", key, OTHER_PARTITIONS));
            }
            Double share = weight.getValue();
            if (share == null || share < 0 || share.isNaN() || share.isInfinite()) {
                throw new IllegalArgumentException(String.format(
                    "partitionWeights share for '%s' must be a non-negative number, got %s", key, share));
            }
        }
    }

    private static boolean isPartitionNumber(String key) {
        if (!key.matches("0|[1-9]\\d*")) {
            return false;
        }
        try {
            Integer.parseInt(key);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
