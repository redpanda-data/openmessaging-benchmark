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
package io.openmessaging.benchmark.driver.redpanda.tx;

import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import io.openmessaging.benchmark.driver.redpanda.RedpandaBenchmarkDriverBase;

import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class RedpandaBenchmarkDriver extends RedpandaBenchmarkDriverBase {
    private final AtomicInteger producerId = new AtomicInteger(0);
    private Config extra_config;

    @Override
    public void initialize(File configurationFile, StatsLogger statsLogger) throws IOException {
        extra_config = mapper.readValue(configurationFile, Config.class);
        super.initialize(configurationFile, statsLogger);
    }

    @Override
    public CompletableFuture<BenchmarkProducer> createProducer(String topic) {
        Properties properties = producerProperties;
        properties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                String.format("omb-tx-%d-%s", producerId.getAndIncrement(), UUID.randomUUID()));
        KafkaProducer<String, byte[]> kafkaProducer = new KafkaProducer<>(properties);
        // initialize producer transactions

        BenchmarkProducer benchmarkProducer = new RedpandaBenchmarkProducer(kafkaProducer, topic,
                extra_config.requestsPerTransaction);
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
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, subscriptionName);
        KafkaConsumer<String, byte[]> kafkaConsumer = new KafkaConsumer<>(properties);
        try {
            // Subscribe
            kafkaConsumer.subscribe(Arrays.asList(topic));

            // Start polling
            BenchmarkConsumer benchmarkConsumer = new io.openmessaging.benchmark.driver.redpanda.RedpandaBenchmarkConsumer(
                    kafkaConsumer, consumerProperties,
                    consumerCallback, 5000);

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

}
