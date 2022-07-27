/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.openmessaging.benchmark.driver.redpanda.swarm;

import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedpandaBenchmarkConsumer implements BenchmarkConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedpandaBenchmarkConsumer.class);

    private final KafkaConsumer<String, byte[]> consumer;

    private final ExecutorService executor;
    private final Future<?> consumerTask;
    private final UUID nodeId;
    private volatile boolean closing = false;

    public RedpandaBenchmarkConsumer(UUID nodeId, KafkaConsumer<String, byte[]> consumer, ConsumerCallback callback) {
        this.consumer = consumer;
        this.executor = Executors.newSingleThreadExecutor();
        this.nodeId = nodeId;

        this.consumerTask = this.executor.submit(() -> {
            while (!closing) {
                try {
                    ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                    Map<TopicPartition, OffsetAndMetadata> offsetMap = new HashMap<>();
                    for (ConsumerRecord<String, byte[]> record : records) {
                        
                        ByteBuffer bb = ByteBuffer.wrap(record.value());

                        UUID producerNodeId = RedpandaBenchmarkDriver.getUuid(bb, 0);
                        
                        if (producerNodeId.equals(nodeId)) {
                            long producedNs = bb.getLong(16);
                            long consumedNs = System.nanoTime();
                            long e2eLantencyNs = consumedNs - producedNs;

                            if (e2eLantencyNs < 0) {
                                throw new RuntimeException("e2e latency can't be negative: " + e2eLantencyNs);
                            }
                            
                            callback.messageReceived(record.value().length, e2eLantencyNs);

                            offsetMap.put(new TopicPartition(record.topic(), record.partition()),
                                    new OffsetAndMetadata(record.offset()));
                        }
                    }

                    if (!records.isEmpty()) {
                        // Async commit all messages polled so far
                        consumer.commitAsync(offsetMap, null);
                    }
                } catch (Exception e) {
                    callback.error();
                    log.error("exception occur while consuming message", e);
                }
            }
        });
    }

    @Override
    public void close() throws Exception {
        closing = true;
        executor.shutdown();
        consumerTask.get();
        consumer.close();
    }

}
