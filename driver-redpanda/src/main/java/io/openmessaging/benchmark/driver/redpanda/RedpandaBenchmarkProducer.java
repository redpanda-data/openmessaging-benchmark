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
package io.openmessaging.benchmark.driver.redpanda;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang.ArrayUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import io.openmessaging.benchmark.driver.BenchmarkProducer;

public class RedpandaBenchmarkProducer implements BenchmarkProducer {

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public RedpandaBenchmarkProducer(KafkaProducer<String, byte[]> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key.orElse(null), payload);

        CompletableFuture<Void> future = new CompletableFuture<>();

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                future.completeExceptionally(exception);
            } else {
                future.complete(null);
            }
        });

        return future;
    }

    @Override
    public void close() throws Exception {
        producer.close();
    }

}
