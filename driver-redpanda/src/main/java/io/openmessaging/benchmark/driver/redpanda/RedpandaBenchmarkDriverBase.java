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

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.UnknownTopicIdException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkDriver;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class RedpandaBenchmarkDriverBase implements BenchmarkDriver {

    protected Config config;

    protected List<BenchmarkProducer> producers = Collections.synchronizedList(new ArrayList<>());
    protected List<BenchmarkConsumer> consumers = Collections.synchronizedList(new ArrayList<>());

    protected Properties topicProperties;
    protected Properties producerProperties;
    protected Properties consumerProperties;

    protected AdminClient admin;

    @Override
    public void initialize(File configurationFile, StatsLogger statsLogger) throws IOException {
        config = mapper.readValue(configurationFile, Config.class);

        Properties commonProperties = new Properties();
        commonProperties.load(new StringReader(config.commonConfig));

        producerProperties = new Properties();
        commonProperties.forEach((key, value) -> producerProperties.put(key, value));
        producerProperties.load(new StringReader(config.producerConfig));
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

        consumerProperties = new Properties();
        commonProperties.forEach((key, value) -> consumerProperties.put(key, value));
        consumerProperties.load(new StringReader(config.consumerConfig));
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        topicProperties = new Properties();
        topicProperties.load(new StringReader(config.topicConfig));

        admin = AdminClient.create(commonProperties);

        if (config.reset) {
            // List existing topics
            ListTopicsResult result = admin.listTopics();
            try {
                // Delete all existing topics matching the prefix
                String topicPrefix = getTopicNamePrefix();
                Set<String> topicsToDelete = result.names().get().stream()
                        .filter(topic -> topic.startsWith(topicPrefix))
                        .collect(Collectors.toSet());

                if (topicsToDelete.size() > 0) {
                    DeleteTopicsResult deletes = admin.deleteTopics(topicsToDelete);
                    deletes.all().get();
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownTopicOrPartitionException
                        || e.getCause() instanceof UnknownTopicIdException) {
                    log.warn("Topic(s) appeared to be deleted already (race condition)");
                } else {
                    throw new IOException("Could not delete previous topics", e);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new IOException(e);
            }
        }
    }

    @Override
    public String getTopicNamePrefix() {
        return "test-topic";
    }

    private static final Logger log = LoggerFactory.getLogger(RedpandaBenchmarkDriver.class);

    protected <T> CompletableFuture<T> toCompletableFuture(final KafkaFuture<T> kafkaFuture) {
        final CompletableFuture<T> wrappingFuture = new CompletableFuture<>();
        kafkaFuture.whenComplete((value, throwable) -> {
            if (throwable != null) {
                wrappingFuture.completeExceptionally(throwable);
            } else {
                wrappingFuture.complete(value);
            }
        });
        return wrappingFuture;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public CompletableFuture<Void> createTopic(String topic, int partitions) {
        log.info("Creating a topic: {}, with {} partitions and replication of: {}",
                topic, partitions, config.replicationFactor);
        NewTopic newTopic = new NewTopic(topic, partitions, config.replicationFactor);
        newTopic.configs(new HashMap<>((Map) topicProperties));
        return toCompletableFuture(admin.createTopics(Arrays.asList(newTopic)).all());

    }

    @Override
    public CompletableFuture<Boolean> validateTopicExists(String topicName) {
        return toCompletableFuture(admin.listTopics(new ListTopicsOptions()).names())
                .thenApply(names -> names.stream().anyMatch(topic -> topic.equals(topicName)));

    }

    @Override
    public void close() throws Exception {
        for (BenchmarkProducer producer : producers) {
            producer.close();
        }
        producers.clear();

        for (BenchmarkConsumer consumer : consumers) {
            consumer.close();
        }
        consumers.clear();

        if (admin != null) {
            admin.close();
            admin = null;
        }
    }

    protected static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

}
