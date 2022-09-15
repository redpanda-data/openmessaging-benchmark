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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Preconditions;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.openmessaging.benchmark.worker.commands.ConsumerAssignment;
import io.openmessaging.benchmark.worker.commands.CountersStats;
import io.openmessaging.benchmark.worker.commands.CumulativeLatencies;
import io.openmessaging.benchmark.worker.commands.PeriodStats;
import io.openmessaging.benchmark.worker.commands.ProducerWorkAssignment;
import io.openmessaging.benchmark.worker.commands.TopicsInfo;
import static org.asynchttpclient.Dsl.*;

/*
SwarmWorker is similar to DistributedWorkersEnsemble, the key distinctions:
 
 - it doesn't depends on the synchronized clocks
 - each worker play a role of a producer and a consumer worker
   - the producer workers are homogeneous:
       - each has {topics} x {producersPerTopic} producers
       - producerRate is adjusted by the number of the workers
   - the consumer workers are homogeneous too:
       - each worker has {topics} x {subscriptionsPerTopic} unique consumer groups
       - each consumer group has {consumerPerSubscription} subscriptions (only one it making a progress)
 - swarm workload is more chatty and consume more network bandwidth
 
Let's focus on the last point and assume we use two workers with the following settings:

    name: load.400k
    topics: 1
    partitionsPerTopic: 100
    messageSize: 1024
    payloadFile: "payload/payload-1Kb.data"
    subscriptionsPerTopic: 1
    consumerPerSubscription: 8
    producersPerTopic: 16
    producerRate: 400000 (ops)
    consumerBacklogSizeGB: 0
    testDurationMinutes: 60
    warmupDurationMinutes: 5
 
## DistributedWorkersEnsemble
 
 - We have two nodes: one becomes a producer and another a consumer workers.
 - The producer worker creates 16 producers = {topics = 1} x {producersPerTopic = 16}.
 - The producer worker aims: 390 MB/s = {messageSize = 1000b} x {producerRate = 400000 ops}.
 - The consumer worker has 8 {topics = 1} x {subscriptionsPerTopic = 1} x {consumerPerSubscription = 8}
   consumers, a single consumer group so only one consumer consumes all partitions; ideally a consumer
   is caught up with the producers so it consumers 390 MB/s
 
Each worker consumes 390 MB/s of traffic
 
## SwarmWorker
 
 - Each node is both a consumer and a producer worker.
 - Each worker has 16 producers = {topics = 1} x {producersPerTopic = 16}
 - producerRate is split across nodes so each node has producerRate = 200000 ops
 - It gives 195 MB/s = {messageSize = 1000b} x {producerRate = 200000 ops} write traffic per worker
 - Each node is a consumer which has
    - 1 consumer group = {topics = 1} x {subscriptionsPerTopic = 1}
    - 8 consumers = {topics = 1} x {subscriptionsPerTopic = 1} x {consumerPerSubscription = 8}
 - Ideally consumer is caught up with the producers so it had to consume 2x195 MB/s = 390 MB/s
 Each node consumes 585 MB/s of traffic = 195 MB/s of write traffic + 390 MB/s of read traffic
 
We should not compare Swarm & Dist directly because by the nature of the workload Swarm may saturate the network earlier.
*/
public class SwarmWorker implements Worker {

    private final static int REQUEST_TIMEOUT_MS = 300_000;
    private final static int READ_TIMEOUT_MS = 300_000;
    private final List<String> workers;

    private final AsyncHttpClient httpClient;

    public SwarmWorker(List<String> workers) {
        Preconditions.checkArgument(workers.size() > 1, "Workers must be > 1");

        this.workers = workers;

        httpClient = asyncHttpClient(config().setRequestTimeout(REQUEST_TIMEOUT_MS).setReadTimeout(READ_TIMEOUT_MS));
    }

    @Override
    public void initializeDriver(File configurationFile) throws IOException {
        byte[] confFileContent = Files.readAllBytes(Paths.get(configurationFile.toString()));
        sendPost(workers, "/initialize-driver", confFileContent);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> createTopics(TopicsInfo topicsInfo) throws IOException {
        // Create all topics from a single worker node
        return (List<String>) post(workers.get(0), "/create-topics", writer.writeValueAsBytes(topicsInfo), List.class)
                .join();
    }

    @Override
    public void createProducers(List<String> topics) {
        List<CompletableFuture<Void>> futures = workers.stream().map(worker -> {
            try {
                return sendPost(worker, "/create-producers",
                        writer.writeValueAsBytes(topics));
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
        producerWorkAssignment.publishRate /= workers.size();
        sendPost(workers, "/start-load", writer.writeValueAsBytes(producerWorkAssignment));
    }

    @Override
    public void probeProducers() throws IOException {
        sendPost(workers, "/probe-producers", new byte[0]);
    }

    @Override
    public void adjustPublishRate(double publishRate) throws IOException {
        // Reduce the publish rate across all the brokers
        publishRate /= workers.size();
        sendPost(workers, "/adjust-publish-rate", writer.writeValueAsBytes(publishRate));
    }

    @Override
    public void stopAll() {
        sendPost(workers, "/stop-all", new byte[0]);
    }

    @Override
    public void pauseConsumers() throws IOException {
        sendPost(workers, "/pause-consumers", new byte[0]);
    }

    @Override
    public void resumeConsumers() throws IOException {
        sendPost(workers, "/resume-consumers", new byte[0]);
    }

    @Override
    public void createConsumers(ConsumerAssignment overallConsumerAssignment) {
        List<CompletableFuture<Void>> futures = workers.stream().map(worker -> {
            try {
                return sendPost(worker, "/create-consumers",
                        writer.writeValueAsBytes(overallConsumerAssignment));
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
            stats.errors += is.errors;
            stats.messagesSent += is.messagesSent;
            stats.bytesSent += is.bytesSent;
            stats.messagesReceived += is.messagesReceived;
            stats.bytesReceived += is.bytesReceived;
            stats.totalMessagesSent += is.totalMessagesSent;
            stats.totalMessagesReceived += is.totalMessagesReceived;
            stats.totalErrors += is.totalErrors;

            try {
                stats.publishLatency.add(Histogram.decodeFromCompressedByteBuffer(
                        ByteBuffer.wrap(is.publishLatencyBytes), TimeUnit.SECONDS.toMicros(30)));
                
                stats.scheduleLatency.add(Histogram.decodeFromCompressedByteBuffer(
                    ByteBuffer.wrap(is.scheduleLatencyBytes), TimeUnit.SECONDS.toMicros(30)));

                stats.publishDelayLatency.add(Histogram.decodeFromCompressedByteBuffer(
                        ByteBuffer.wrap(is.publishDelayLatencyBytes), TimeUnit.SECONDS.toMicros(30)));

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
                stats.scheduleLatency.add(Histogram.decodeFromCompressedByteBuffer(
                    ByteBuffer.wrap(is.scheduleLatencyBytes), TimeUnit.SECONDS.toMicros(30)));
            } catch (Exception e) {
                log.error("Failed to decode schedule latency");
                throw new RuntimeException(e);
            }

            try {
                stats.publishDelayLatency.add(Histogram.decodeFromCompressedByteBuffer(
                        ByteBuffer.wrap(is.publishDelayLatencyBytes), TimeUnit.SECONDS.toMicros(30)));
            } catch (Exception e) {
                log.error("Failed to decode publish delay latency: {}",
                          ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(is.publishDelayLatencyBytes)));
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
            Preconditions.checkArgument(x.getStatusCode() == 200, "Status should be 200");
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
                Preconditions.checkArgument(response.getStatusCode() == 200, "Status should be 200");
                return mapper.readValue(response.getResponseBody(), clazz);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private <T> CompletableFuture<T> post(String host, String path, byte[] body, Class<T> clazz) {
        return httpClient.preparePost(host + path).setBody(body).execute().toCompletableFuture().thenApply(response -> {
            try {
                if (response.getStatusCode() != 200) {
                    log.error("Failed to do HTTP post request to {}{} -- code: {}", host, path, response.getStatusCode());
                }
                Preconditions.checkArgument(response.getStatusCode() == 200, "Status should be 200");
                return mapper.readValue(response.getResponseBody(), clazz);
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
}
