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

import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import io.openmessaging.benchmark.driver.BenchmarkProducer;

public class RedpandaBenchmarkProducer implements BenchmarkProducer {
    private final UUID nodeId;
    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public RedpandaBenchmarkProducer(UUID nodeId, KafkaProducer<String, byte[]> producer, String topic) {
        this.nodeId = nodeId;
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload) {
        if (payload.length < 16 + 8) {
            throw new RuntimeException();
        }

        byte[] data = Arrays.copyOf(payload, payload.length);
        ByteBuffer bb = ByteBuffer.wrap(data);

        RedpandaBenchmarkDriver.putUuid(bb, 0, nodeId);
        bb.putLong(16, System.nanoTime());
        
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key.orElse(null), data);

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    future.completeExceptionally(exception);
                } else {
                    future.complete(null);
                }
            });
        } catch(Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void close() throws Exception {
        producer.close();
    }

}
