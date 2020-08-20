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
package io.openmessaging.benchmark.worker;

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import org.HdrHistogram.Histogram;
import org.apache.pulsar.common.util.FutureUtil;
import org.asynchttpclient.AsyncHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.internal.Maps;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.openmessaging.benchmark.utils.ListPartition;
import io.openmessaging.benchmark.worker.commands.ConsumerAssignment;
import io.openmessaging.benchmark.worker.commands.CountersStats;
import io.openmessaging.benchmark.worker.commands.CumulativeLatencies;
import io.openmessaging.benchmark.worker.commands.PeriodStats;
import io.openmessaging.benchmark.worker.commands.ProducerWorkAssignment;
import io.openmessaging.benchmark.worker.commands.TopicSubscription;
import io.openmessaging.benchmark.worker.commands.TopicsInfo;
import static org.asynchttpclient.Dsl.*;

public class DistributedWorkersEnsemble implements Worker {

    private final static int REQUEST_TIMEOUT_MS = 300_000;
    private final static int READ_TIMEOUT_MS = 300_000;
    private final List<String> workers;
    private final List<String> producerWorkers;
    private final List<String> consumerWorkers;

    private final AsyncHttpClient httpClient;

    private int numberOfUsedProducerWorkers;

    public DistributedWorkersEnsemble(List<String> workers) {
        Preconditions.checkArgument(workers.size() > 1);

        this.workers = workers;
        List<List<String>> partitions = Lists.partition(workers, workers.size() / 2);
        this.producerWorkers = partitions.get(0);
        this.consumerWorkers = partitions.get(1);

        log.info("Workers list - producers: {}", producerWorkers);
        log.info("Workers list - consumers: {}", consumerWorkers);

        httpClient = asyncHttpClient(config().setRequestTimeout(REQUEST_TIMEOUT_MS).setReadTimeout(READ_TIMEOUT_MS));
    }

    @Override
    public void initializeDriver(File configurationFile) throws IOException {
        byte[] confFileContent = Files.readAllBytes(Paths.get(configurationFile.toString()));
        sendPost(workers, "/initialize-driver", confFileContent);
    }

    @Override
    public List<Topic> createTopics(TopicsInfo topicsInfo) throws IOException {
        // Create all topics from a single worker node
        return (List<Topic>) post(workers.get(0), "/create-topics", writer.writeValueAsBytes(topicsInfo),
                new TypeReference<List<Topic>>() {
                }).join();
    }

    @Override
    public void notifyTopicCreation(List<Topic> topics) throws IOException {
        List<CompletableFuture<Void>> futures = workers.stream().map(worker -> {
            try {
                return sendPost(worker, "/notify-topic-creation", writer.writeValueAsBytes(topics));
            } catch (JsonProcessingException e) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
            }
        }).collect(toList());

        FutureUtil.waitForAll(futures).join();
    }

    @Override
    public void createProducers(List<String> topics) {
        // topics is a normalized list i.e. it accounts for duplicated entries in case
        // of m topics and n producers where m < n. In this case, map the topics as is
        // to honor the number of producers per topic configured for the workload
        List<List<String>> topicsPerProducer;
        if (topics.size() <= producerWorkers.size()) {
            topicsPerProducer = new ArrayList<>();
            for (String topic : topics) {
                List<String> topicList = new ArrayList<>();
                topicList.add(topic);
                topicsPerProducer.add(topicList);
            }
        } else {
            topicsPerProducer = ListPartition.partitionList(topics, producerWorkers.size());
        }

        Map<String, List<String>> topicsPerProducerMap = Maps.newHashMap();
        int i = 0;
        for (List<String> assignedTopics : topicsPerProducer) {
            topicsPerProducerMap.put(producerWorkers.get(i++), assignedTopics);
        }

        // Number of actually used workers might be less than available workers
        numberOfUsedProducerWorkers = i;

        log.info("Number of producers configured for the topic: " + numberOfUsedProducerWorkers);

        List<CompletableFuture<Void>> futures = topicsPerProducerMap.keySet().stream().map(producer -> {
            try {
                return sendPost(producer, "/create-producers",
                        writer.writeValueAsBytes(topicsPerProducerMap.get(producer)));
            } catch (Exception e) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
            }
        }).collect(toList());

        FutureUtil.waitForAll(futures).join();
    }

    @Override
    public void startLoad(ProducerWorkAssignment producerWorkAssignment) throws IOException {
        // Reduce the publish rate across all the brokers
        producerWorkAssignment.publishRate /= numberOfUsedProducerWorkers;
        sendPost(producerWorkers, "/start-load", writer.writeValueAsBytes(producerWorkAssignment));
    }

    @Override
    public void probeProducers() throws IOException {
        sendPost(producerWorkers, "/probe-producers", new byte[0]);
    }

    @Override
    public void adjustPublishRate(double publishRate) throws IOException {
        // Reduce the publish rate across all the brokers
        publishRate /= numberOfUsedProducerWorkers;
        sendPost(producerWorkers, "/adjust-publish-rate", writer.writeValueAsBytes(publishRate));
    }

    @Override
    public void stopAll() {
        sendPost(workers, "/stop-all", new byte[0]);
    }

    @Override
    public void pauseConsumers() throws IOException {
        sendPost(consumerWorkers, "/pause-consumers", new byte[0]);
    }

    @Override
    public void resumeConsumers() throws IOException {
        sendPost(consumerWorkers, "/resume-consumers", new byte[0]);
    }

    @Override
    public void createConsumers(ConsumerAssignment overallConsumerAssignment) {
        List<List<TopicSubscription>> subscriptionsPerConsumer = ListPartition
                .partitionList(overallConsumerAssignment.topicsSubscriptions, consumerWorkers.size());
        Map<String, ConsumerAssignment> topicsPerWorkerMap = Maps.newHashMap();
        int i = 0;
        for (List<TopicSubscription> tsl : subscriptionsPerConsumer) {
            ConsumerAssignment individualAssignement = new ConsumerAssignment();
            individualAssignement.topicsSubscriptions = tsl;
            topicsPerWorkerMap.put(consumerWorkers.get(i++), individualAssignement);
        }

        List<CompletableFuture<Void>> futures = topicsPerWorkerMap.keySet().stream().map(consumer -> {
            try {
                return sendPost(consumer, "/create-consumers",
                        writer.writeValueAsBytes(topicsPerWorkerMap.get(consumer)));
            } catch (Exception e) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
            }
        }).collect(toList());

        FutureUtil.waitForAll(futures).join();
    }

    @Override
    public PeriodStats getPeriodStats() {
        List<PeriodStats> individualStats = get(workers, "/period-stats", PeriodStats.class);
        PeriodStats stats = new PeriodStats();
        individualStats.forEach(is -> {
            stats.messagesSent += is.messagesSent;
            stats.bytesSent += is.bytesSent;
            stats.messagesReceived += is.messagesReceived;
            stats.bytesReceived += is.bytesReceived;
            stats.totalMessagesSent += is.totalMessagesSent;
            stats.totalMessagesReceived += is.totalMessagesReceived;

            try {
                stats.publishLatency.add(Histogram.decodeFromCompressedByteBuffer(
                        ByteBuffer.wrap(is.publishLatencyBytes), TimeUnit.SECONDS.toMicros(30)));

                stats.endToEndLatency.add(Histogram.decodeFromCompressedByteBuffer(
                        ByteBuffer.wrap(is.endToEndLatencyBytes), TimeUnit.HOURS.toMicros(12)));
            } catch (ArrayIndexOutOfBoundsException | DataFormatException e) {
                throw new RuntimeException(e);
            }
        });

        return stats;
    }

    @Override
    public CumulativeLatencies getCumulativeLatencies() {
        List<CumulativeLatencies> individualStats = get(workers, "/cumulative-latencies", CumulativeLatencies.class);

        CumulativeLatencies stats = new CumulativeLatencies();
        individualStats.forEach(is -> {
            try {
                stats.publishLatency.add(Histogram.decodeFromCompressedByteBuffer(
                        ByteBuffer.wrap(is.publishLatencyBytes), TimeUnit.SECONDS.toMicros(30)));
            } catch (Exception e) {
                log.error("Failed to decode publish latency");
                throw new RuntimeException(e);
            }

            try {
                stats.endToEndLatency.add(Histogram.decodeFromCompressedByteBuffer(
                        ByteBuffer.wrap(is.endToEndLatencyBytes), TimeUnit.HOURS.toMicros(12)));
            } catch (Exception e) {
                log.error("Failed to decode end-to-end latency: {}",
                        ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(is.endToEndLatencyBytes)));
                throw new RuntimeException(e);
            }
        });

        return stats;

    }

    @Override
    public CountersStats getCountersStats() throws IOException {
        List<CountersStats> individualStats = get(workers, "/counters-stats", CountersStats.class);

        CountersStats stats = new CountersStats();
        individualStats.forEach(is -> {
            stats.messagesSent += is.messagesSent;
            stats.messagesReceived += is.messagesReceived;
        });

        return stats;
    }

    @Override
    public void resetStats() throws IOException {
        sendPost(workers, "/reset-stats", new byte[0]);
    }

    /**
     * Send a request to multiple hosts and wait for all responses
     */
    private void sendPost(List<String> hosts, String path, byte[] body) {
        FutureUtil.waitForAll(hosts.stream().map(w -> sendPost(w, path, body)).collect(toList())).join();
    }

    private CompletableFuture<Void> sendPost(String host, String path, byte[] body) {
        return httpClient.preparePost(host + path).setBody(body).execute().toCompletableFuture().thenApply(x -> {
            if (x.getStatusCode() != 200) {
                log.error("Failed to do HTTP post request to {}{} -- code: {} error: {}", host, path, x.getStatusCode(),
                        x.getResponseBody());
            }
            Preconditions.checkArgument(x.getStatusCode() == 200);
            return (Void) null;
        });
    }

    private <T> List<T> get(List<String> hosts, String path, Class<T> clazz) {
        List<CompletableFuture<T>> futures = hosts.stream().map(w -> get(w, path, clazz)).collect(toList());

        CompletableFuture<List<T>> resultFuture = new CompletableFuture<>();
        FutureUtil.waitForAll(futures).thenRun(() -> {
            resultFuture.complete(futures.stream().map(CompletableFuture::join).collect(toList()));
        }).exceptionally(ex -> {
            resultFuture.completeExceptionally(ex);
            return null;
        });

        return resultFuture.join();
    }

    private <T> CompletableFuture<T> get(String host, String path, Class<T> clazz) {
        return httpClient.prepareGet(host + path).execute().toCompletableFuture().thenApply(response -> {
            try {
                if (response.getStatusCode() != 200) {
                    log.error("Failed to do HTTP get request to {}{} -- code: {}", host, path,
                            response.getStatusCode());
                }
                Preconditions.checkArgument(response.getStatusCode() == 200);
                return mapper.readValue(response.getResponseBody(), clazz);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private <T> CompletableFuture<T> post(String host, String path, byte[] body, TypeReference<T> type) {
        return httpClient.preparePost(host + path).setBody(body).execute().toCompletableFuture().thenApply(response -> {
            try {
                if (response.getStatusCode() != 200) {
                    log.error("Failed to do HTTP post request to {}{} -- code: {}", host, path,
                            response.getStatusCode());
                }
                Preconditions.checkArgument(response.getStatusCode() == 200);
                return mapper.readValue(response.getResponseBody(), type);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }

    private static final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }

    private static final Logger log = LoggerFactory.getLogger(DistributedWorkersEnsemble.class);

    @Override
    public void pauseProducers() throws IOException {
        sendPost(producerWorkers, "/pause-producers", new byte[0]);
    }

    @Override
    public void resumeProducers() throws IOException {
        sendPost(producerWorkers, "/resume-producers", new byte[0]);
    }

}
