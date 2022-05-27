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

import io.openmessaging.benchmark.utils.distributor.KeyDistributorType;

public class Workload {
    public String name;

    /** Number of topics to create in the test */
    public int topics;

    /** Number of partitions each topic will contain */
    public int partitionsPerTopic;

    public KeyDistributorType keyDistributor = KeyDistributorType.NO_KEY;

    public int messageSize;

    public String payloadFile;

    public int subscriptionsPerTopic;

    public int producersPerTopic;

    public int consumerPerSubscription;

    public int producerRate;

    /**
     * If the consumer backlog is > 0, the generator will accumulate messages until
     * the requested amount of storage is retained and then it will start the
     * consumers to drain it.
     *
     * The testDurationMinutes will be overruled to allow the test to complete when
     * the consumer has drained all the backlog and it's on par with the producer
     */
    public long consumerBacklogSizeGB = 0;

    public int warmupDurationMinutes = 30;
    public int testDurationMinutes;

    /**
     * Consumer only tests. The warm-up phase will be run w/ the specified producer
     * configuration until the specified backlog is built. Following that, the
     * producers are shut off and the consumers are run until the backlog is drained
     */
    public boolean consumerOnly;

    public void validate() throws IllegalArgumentException {
        if (consumerOnly && (subscriptionsPerTopic == 0 || consumerPerSubscription == 0)) {
            throw new IllegalArgumentException("Consumer only tests need subscriptions/consumers");
        }

        if (consumerOnly && consumerBacklogSizeGB <= 0) {
            throw new IllegalArgumentException("Consumer only tests need a backlog specification");
        }

        if (producerRate <= 0) {
            throw new IllegalArgumentException("Producer rate should be > 0");
        }
    }
}
