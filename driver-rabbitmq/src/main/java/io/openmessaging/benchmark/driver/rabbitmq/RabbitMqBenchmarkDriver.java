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
package io.openmessaging.benchmark.driver.rabbitmq;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.io.BaseEncoding;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkDriver;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import io.openmessaging.benchmark.driver.rabbitmq.RabbitMqConfig.QueueType;

import org.apache.bookkeeper.stats.StatsLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Based on the KeyDistributor from the framework
// Generates routing keys to "channel" messages to specific queues
// Common to all producers for a workload
class RoutingKeyGenerator {
    private AtomicInteger idx = new AtomicInteger(0);
    private final int KEY_COUNT;
    private final String[] keys;

    RoutingKeyGenerator(int partitions, int keyLength) {
        KEY_COUNT = partitions;
        keys = new String[KEY_COUNT];

        // byte[] buffer = new byte[keyLength];
        // Random random = new Random();
        // for (int i = 0; i < keys.length; i++) {
        // random.nextBytes(buffer);
        // keys[i] = BaseEncoding.base64Url().omitPadding().encode(buffer);
        // }

        // Repeatable routing keys - Partition Number as string
        // When run on workers hosted on different physical machines/processes
        // consumers need to listen to the same routing keys as producers
        // TODO: Pass list of routing keys along w/ topics to consumer
        for (int i = 0; i < partitions; i++) {
            keys[i] = String.valueOf(i);
        }
    }

    public String next() {
        return keys[idx.getAndIncrement() % KEY_COUNT];
    }
}

class ConnectionManager {
    private List<ConnectionFactory> connectionFactory = new ArrayList<>();
    private List<Connection> connections = new ArrayList<>();
    private AtomicInteger idx = new AtomicInteger(0);

    ConnectionManager(String[] brokers) {
        for (String broker : brokers) {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setAutomaticRecoveryEnabled(true);
            factory.setHost(broker);
            factory.setUsername("admin");
            factory.setPassword("admin");
            connectionFactory.add(factory);
        }
    }

    // Round robins across all brokers
    public Connection connectAny() throws IOException, TimeoutException {
        Connection connection = connectionFactory.get(idx.getAndIncrement() % connectionFactory.size()).newConnection();
        connections.add(connection);

        return connection;
    }

    public Connection connect(int brokerIdx) throws IOException, TimeoutException, IllegalArgumentException {
        if (brokerIdx > connectionFactory.size()) {
            throw new IllegalArgumentException("Broker index > total no of brokers");
        }
        Connection connection = connectionFactory.get(brokerIdx).newConnection();
        connections.add(connection);

        return connection;
    }

    public void close() {
        connections.forEach(connection -> {
            try {
                connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}

public class RabbitMqBenchmarkDriver implements BenchmarkDriver {

    private RabbitMqConfig config;

    private ConnectionManager connectionManager;

    private Map<String, RoutingKeyGenerator> topicRoutingKeyGenerator = new HashMap<>();

    private int routingKeyLength;

    private static final Logger log = LoggerFactory.getLogger(RabbitMqBenchmarkDriver.class);

    public static final String TIMESTAMP_HEADER = "timestamp";

    @Override
    public void initialize(File configurationFile, StatsLogger statsLogger) throws IOException {
        config = mapper.readValue(configurationFile, RabbitMqConfig.class);
        connectionManager = new ConnectionManager(config.brokers);
        routingKeyLength = config.routingKeyLength;
    }

    @Override
    public void close() throws Exception {
        connectionManager.close();
    }

    @Override
    public String getTopicNamePrefix() {
        return config.topicPrefix;
    }

    @Override
    public CompletableFuture<Void> createTopic(String topic, int partitions) {
        CompletableFuture<BenchmarkProducer> future = new CompletableFuture<>();

        try {
            Connection connection = config.singleNode ? connectionManager.connect(0) : connectionManager.connectAny();
            Channel channel = connection.createChannel();
            channel.exchangeDeclare(topic, config.exchangeType, config.messagePersistence);
        } catch (IOException | TimeoutException | IllegalArgumentException e) {
            e.printStackTrace();
            future.completeExceptionally(e);
        }

        if (!topicRoutingKeyGenerator.containsKey(topic)) {
            topicRoutingKeyGenerator.put(topic, new RoutingKeyGenerator(partitions, routingKeyLength));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> notifyTopicCreation(String topic, int partitions) {
        if (!topicRoutingKeyGenerator.containsKey(topic)) {
            topicRoutingKeyGenerator.put(topic, new RoutingKeyGenerator(partitions, routingKeyLength));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<BenchmarkProducer> createProducer(String topic) {
        CompletableFuture<BenchmarkProducer> future = new CompletableFuture<>();

        try {
            Connection connection = config.singleNode ? connectionManager.connect(0) : connectionManager.connectAny();
            Channel channel = connection.createChannel();
            channel.confirmSelect();
            future = CompletableFuture.completedFuture(new RabbitMqBenchmarkProducer(channel, topic,
                    config.messagePersistence, topicRoutingKeyGenerator.get(topic)));
        } catch (IOException | TimeoutException | IllegalArgumentException e) {
            e.printStackTrace();
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<BenchmarkConsumer> createConsumer(String topic, String subscriptionName,
            Optional<Integer> partition, ConsumerCallback consumerCallback) {

        CompletableFuture<BenchmarkConsumer> future = new CompletableFuture<>();
        ForkJoinPool.commonPool().execute(() -> {
            try {
                String queueName = topic + "-" + subscriptionName;
                Connection connection = config.singleNode ? connectionManager.connect(0)
                        : connectionManager.connectAny();
                Channel channel = connection.createChannel();
                channel.exchangeDeclare(topic, config.exchangeType, config.messagePersistence);

                Map<String, Object> args = new HashMap<>();
                args.put("x-queue-type", config.queueType.toString().toLowerCase());
                String routingKey = "";
                if (config.exchangeType == BuiltinExchangeType.TOPIC) {
                    // Bind to all, if topic-based exchange
                    routingKey = "#";
                } else if (config.exchangeType == BuiltinExchangeType.DIRECT) {
                    if (partition.isPresent()) {
                        routingKey = partition.get().toString();
                    }
                    queueName += ("-part-" + routingKey);
                }
                channel.queueDeclare(queueName, config.messagePersistence, config.exclusive, false, args);
                channel.queueBind(queueName, topic, routingKey);
                log.info("Bound queue -> {} to exchange -> {}", queueName, topic);
                future.complete(new RabbitMqBenchmarkConsumer(channel, queueName, consumerCallback));
            } catch (IOException | TimeoutException | IllegalArgumentException e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
}
