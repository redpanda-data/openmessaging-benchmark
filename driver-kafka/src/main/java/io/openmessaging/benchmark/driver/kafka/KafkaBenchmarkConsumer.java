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
package io.openmessaging.benchmark.driver.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;

import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaBenchmarkConsumer implements BenchmarkConsumer, OffsetCommitCallback {

    private static final Logger log = LoggerFactory.getLogger(KafkaBenchmarkConsumer.class);

    private final KafkaConsumer<String, byte[]> consumer;

    private final ExecutorService executor;
    private final Future<?> consumerTask;
    private final ConsumerCallback callback;
    private volatile boolean closing = false;

    private long timeSinceOffsetCommitCallback = 0;
    private long offsetCommitLingerMs;
    private boolean autoCommit;

    private static final String OFFSET_COMMIT_CONFIG = "offsetCommitLingerMs";

    public KafkaBenchmarkConsumer(KafkaConsumer<String, byte[]> consumer,
                                  Properties consumerConfig,
                                  ConsumerCallback callback) {
        this(consumer, consumerConfig, callback, 100L);
    }

    public KafkaBenchmarkConsumer(KafkaConsumer<String, byte[]> consumer,
                                  Properties consumerConfig,
                                  ConsumerCallback callback,
                                  long pollTimeoutMs) {
        this.consumer = consumer;
        this.executor = Executors.newSingleThreadExecutor();
        this.callback = callback;

        this.offsetCommitLingerMs = Long.valueOf((String)consumerConfig.getOrDefault(OFFSET_COMMIT_CONFIG, "0"));
        this.autoCommit = Boolean.valueOf((String)consumerConfig.getOrDefault(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,"true"));

        this.consumerTask = this.executor.submit(() -> {
            long lastOffsetNanos = System.nanoTime();
            Map<TopicPartition, OffsetAndMetadata> offsetMap = new HashMap<>();
            while (!closing) {
                try {
                    ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(pollTimeoutMs));

                    for (ConsumerRecord<String, byte[]> record : records) {
                        callback.messageReceived(record.value(), TimeUnit.MILLISECONDS.toNanos(record.timestamp()));

                        offsetMap.put(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset()+1));
                    }

                    /* We're only going to submit an async commit request if:
                        - autoCommit is disabled AND
                        - there are offsets to commit AND
                            - offsetCommitLingerMs is negative OR
                            - we've waited lingerMs milliseconds since the last callback completed (+ve or -ve)
                     */
                    long now = System.currentTimeMillis();
                    long timeSinceOffsetCommitComplete = now - timeSinceOffsetCommitCallback;
                    if (!autoCommit && !offsetMap.isEmpty() &&
                            (offsetCommitLingerMs < 0  || timeSinceOffsetCommitComplete >= offsetCommitLingerMs)) {
                        log.debug("msec since last offset commit complete: {}", timeSinceOffsetCommitComplete);
                        // Async commit all messages polled so far
                        consumer.commitAsync(offsetMap, this);
                        offsetMap.clear();
                        // We set timeSinceOffsetCommitCallback to max value to ensure that
                        // (now - timeSinceOffsetCommitCallback) is not positive until we next get a callback
                        timeSinceOffsetCommitCallback = Long.MAX_VALUE;
                    }
                }
                catch(Exception e){
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

    @Override
    public void onComplete(Map<TopicPartition, OffsetAndMetadata> map, Exception e) {
        timeSinceOffsetCommitCallback = System.currentTimeMillis();
        if (e != null) {
            log.warn("Error committing offsets", e);
            callback.error();
        }
    }
}
