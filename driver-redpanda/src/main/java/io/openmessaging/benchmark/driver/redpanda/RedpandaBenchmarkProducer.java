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
package io.openmessaging.benchmark.driver.redpanda;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import io.openmessaging.benchmark.driver.BenchmarkProducer;

public class RedpandaBenchmarkProducer implements BenchmarkProducer {

    private final KafkaProducer<byte[], byte[]> producer;

    private final String topic;

    public RedpandaBenchmarkProducer(KafkaProducer<byte[], byte[]> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }


    @Override
    public CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload) {
        final byte[] binaryKey = key.map(String::getBytes).orElse(null);
        return sendAsync(binaryKey, payload);
    }

    @Override
    public CompletableFuture<Void> sendAsync(byte[] key, byte[] payload) {
        final ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, key, payload);
        final CompletableFuture<Void> future = new CompletableFuture<>();

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
