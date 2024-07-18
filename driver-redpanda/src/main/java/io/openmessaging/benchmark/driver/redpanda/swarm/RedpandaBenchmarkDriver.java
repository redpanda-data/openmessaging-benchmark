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
package io.openmessaging.benchmark.driver.redpanda.swarm;

import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import io.openmessaging.benchmark.driver.redpanda.Config;
import io.openmessaging.benchmark.driver.redpanda.RedpandaBenchmarkDriverBase;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import io.openmessaging.benchmark.driver.ConsumerCallback;

public class RedpandaBenchmarkDriver extends RedpandaBenchmarkDriverBase {
    private UUID nodeId = UUID.randomUUID();
    private static int INIT_RETRIES = 5;

    private Config config;

    private List<BenchmarkProducer> producers = Collections.synchronizedList(new ArrayList<>());
    private List<BenchmarkConsumer> consumers = Collections.synchronizedList(new ArrayList<>());

    private Properties topicProperties;
    private Properties producerProperties;
    private Properties consumerProperties;

    private AdminClient admin;

    public static void putUuid(ByteBuffer bb, int offset, UUID uuid) {
        bb.putLong(offset, uuid.getMostSignificantBits());
        bb.putLong(offset + 8, uuid.getLeastSignificantBits());
    }

    public static UUID getUuid(ByteBuffer bb, int offset) {
        bb.position(offset);
        long high = bb.getLong();
        long low = bb.getLong();
        return new UUID(high, low);
    }

    @Override
    public CompletableFuture<BenchmarkProducer> createProducer(String topic) {
        KafkaProducer<String, byte[]> kafkaProducer = new KafkaProducer<>(producerProperties);
        BenchmarkProducer benchmarkProducer = new RedpandaBenchmarkProducer(nodeId, kafkaProducer, topic);
        try {
            // Add to producer list to close later
            producers.add(benchmarkProducer);
            return CompletableFuture.completedFuture(benchmarkProducer);
        } catch (Throwable t) {
            kafkaProducer.close();
            CompletableFuture<BenchmarkProducer> future = new CompletableFuture<>();
            future.completeExceptionally(t);
            return future;
        }
    }

    @Override
    public CompletableFuture<BenchmarkConsumer> createConsumer(String topic, String subscriptionName,
            ConsumerCallback consumerCallback) {
        Properties properties = new Properties();
        consumerProperties.forEach((key, value) -> properties.put(key, value));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, nodeId.toString() + "-" + subscriptionName);
        KafkaConsumer<String, byte[]> kafkaConsumer = new KafkaConsumer<>(properties);
        try {
            // Subscribe
            kafkaConsumer.subscribe(Arrays.asList(topic));

            // Start polling
            BenchmarkConsumer benchmarkConsumer = new RedpandaBenchmarkConsumer(nodeId, kafkaConsumer,
                    consumerCallback);

            // Add to consumer list to close later
            consumers.add(benchmarkConsumer);
            return CompletableFuture.completedFuture(benchmarkConsumer);
        } catch (Throwable t) {
            kafkaConsumer.close();
            CompletableFuture<BenchmarkConsumer> future = new CompletableFuture<>();
            future.completeExceptionally(t);
            return future;
        }
    }

    @Override
    public void close() throws Exception {
        for (BenchmarkProducer producer : producers) {
            producer.close();
        }

        for (BenchmarkConsumer consumer : consumers) {
            consumer.close();
        }
        admin.close();
    }

}
