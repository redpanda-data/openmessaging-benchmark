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
package io.openmessaging.benchmark;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.openmessaging.benchmark.utils.distributor.KeyDistributorType;
import io.openmessaging.benchmark.utils.distributor.PartitionChooser;

public class Workload {
    public String name;

    /**
     * Number of topics to create in the test, must be zero iff
     * existingTopicList is used.
     */
    public int topics;

    /**
     * A list of existing topics names to use instead of randomly generated
     * new topics. If this is list is non-empty, topics must be zero.
     */
    public List<String> existingTopicList = Collections.emptyList();
    /** producer only `existingTopicList`. */
    public List<String> existingProduceTopicList = Collections.emptyList();
    /** consumer only `existingTopicList`. */
    public List<String> existingConsumeTopicList = Collections.emptyList();

    /** Number of partitions each topic will contain */
    public int partitionsPerTopic;

    public KeyDistributorType keyDistributor = KeyDistributorType.NO_KEY;

    /**
     * Optional per-partition traffic shares, e.g. {"3": 0.91, "*": 0.09}: listed partitions get their share and "*"
     * is spread evenly over the unlisted ones. Shares are relative. The producer picks the partition, so the driver
     * must support partition targeting and keys no longer decide the partition. Empty (the default) leaves
     * partitioning to the client.
     */
    public Map<String, Double> partitionWeights = Collections.emptyMap();

    public int messageSize;

    public boolean useRandomizedPayloads;
    public double randomBytesRatio;
    public int randomizedPayloadPoolSize;

    public String payloadFile;

    public int subscriptionsPerTopic;

    public int producersPerTopic;

    public int consumerPerSubscription;

    public int producerRate;

    /**
     * If the consumer backlog is > 0, the generator will accumulate messages until the requested amount of storage is
     * retained and then it will start the consumers to drain it.
     *
     * The testDurationMinutes will be overruled to allow the test to complete when the consumer has drained all the
     * backlog and it's on par with the producer
     */
    public long consumerBacklogSizeGB = 0;

    /**
     * Producer publish rate (msg/s) to apply during the backlog-drain phase.
     * Default -1 means "unset" — producers keep producerRate through the drain
     * (original behavior). >= 0 throttles producers for the drain only; 0 is
     * floored to ~1 msg/s by Worker.adjustPublishRate (effectively stopped).
     */
    public int drainProducerRate = -1;

    public int warmupDurationMinutes = 30;
    public int sampleRateMillis = 10000;
    public int testDurationMinutes;


    /**
     * Return true if existing topics are being used or false if the workload will create its own topics.
     */
    public boolean isUsingExistingTopics() {
        return !existingTopicList.isEmpty() || !existingConsumeTopicList.isEmpty() || !existingProduceTopicList.isEmpty();
    }
    /**
     * Return the total number of consumers defined in the workload.
     */
    public int getConsumerCount() {
        int topicCount = topics;
        if (isUsingExistingTopics()) {
            topicCount = existingTopicList.size() + existingConsumeTopicList.size();
        }
        return topicCount * subscriptionsPerTopic * consumerPerSubscription;
    }

    /**
     * Perform basic validation on the workload and throw an exception if
     * any invalid configuration is found.
     */
    public void validate() {
        checkNonNegative(topics, "topics");
        checkNonNegative(partitionsPerTopic, "partitionsPerTopic");
        checkNonNegative(messageSize, "messageSize");
        checkNonNegative(randomizedPayloadPoolSize, "randomizedPayloadPoolSize");
        checkNonNegative(subscriptionsPerTopic, "subscriptionsPerTopic");
        checkNonNegative(producersPerTopic, "producersPerTopic");
        checkNonNegative(consumerPerSubscription, "consumerPerSubscription");
        checkNonNegative(producerRate, "producerRate");
        checkNonNegative(consumerBacklogSizeGB, "consumerBacklogSizeGB");

        boolean usingExistingTopics = isUsingExistingTopics();

        if (topics > 0 && usingExistingTopics) {
            throw new RuntimeException(String.format(
                "Workload specified both non-zero topic count (%d) and explicit topic list: these options " +
                "are mutually incompatible.", topics));
        }

        if (topics == 0 && !usingExistingTopics) {
            throw new RuntimeException("The workload must specify non-zero topics or a non-empty existingTopicList");
        }

        if (existingTopicList.isEmpty() && (existingConsumeTopicList.isEmpty() != existingProduceTopicList.isEmpty())) {
            throw new RuntimeException("The workload must specify a non-empty existingTopicList");
        }

        if (partitionWeights == null) {
            partitionWeights = Collections.emptyMap();
        }
        PartitionChooser.checkWeights(partitionWeights);
        if (!partitionWeights.isEmpty() && !usingExistingTopics) {
            // Created topics have partitionsPerTopic partitions, so the full check can run before anything starts;
            // for existing topics the count is only known on the worker.
            new PartitionChooser(partitionWeights, partitionsPerTopic);
        }
    }

    private void checkNonNegative(long val, String fieldName) {
        if (val < 0) {
            throw new RuntimeException(String.format(
                "In workload file field %s had invalid negative value: %d", fieldName, val));
        }
    }
}
