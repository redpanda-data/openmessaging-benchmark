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

import io.openmessaging.benchmark.driver.BenchmarkProducer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

public class RedpandaBenchmarkProducer implements BenchmarkProducer {
    final Logger logger = LoggerFactory.getLogger(RedpandaBenchmarkProducer.class);
    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final int requestsPerTransaction;
    private final LongAdder cnt = new LongAdder();

    private enum ProducerState {
        NEEDS_INIT,
        NEEDS_COMMIT,
        NEEDS_ABORT,
        READY
    }

    private ProducerState state = ProducerState.NEEDS_INIT;

    public RedpandaBenchmarkProducer(KafkaProducer<String, byte[]> producer, String topic,
            int requestsPerTransaction) {

        this.producer = producer;
        this.topic = topic;

        this.requestsPerTransaction = requestsPerTransaction;

    }

    @Override
    public CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload) {
        if (payload.length < 16 + 8) {
            throw new RuntimeException();
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            if (cnt.sum() == requestsPerTransaction) {
                cnt.reset();
                state = ProducerState.NEEDS_COMMIT;
            }
            switch (state) {
                case READY:
                    break;
                case NEEDS_ABORT:
                    logger.info("Aborting transaction");
                    producer.abortTransaction();
                    producer.beginTransaction();
                    break;
                case NEEDS_INIT:
                    logger.info("Initializing producer transactions");
                    producer.initTransactions();
                    producer.beginTransaction();
                    break;
                case NEEDS_COMMIT:
                    try {
                        producer.commitTransaction();
                    } catch (TimeoutException e) {
                        // in case of commit timeout the commit has to be retried
                        future.completeExceptionally(e);
                        return future;
                    }
                    producer.beginTransaction();
                    break;
                default:
                    break;
            }
            state = ProducerState.READY;

            byte[] data = Arrays.copyOf(payload, payload.length);
            ProducerRecord<String, byte[]> record = new ProducerRecord<String, byte[]>(topic, key.orElse(null), data);

            cnt.increment();
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    if (exception instanceof InvalidProducerEpochException) {
                        state = ProducerState.NEEDS_ABORT;
                    }
                    future.completeExceptionally(exception);
                } else {
                    future.complete(null);
                }
            });
        } catch (Exception e) {
            state = ProducerState.NEEDS_ABORT;
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void close() throws Exception {
        producer.close();
    }

}
