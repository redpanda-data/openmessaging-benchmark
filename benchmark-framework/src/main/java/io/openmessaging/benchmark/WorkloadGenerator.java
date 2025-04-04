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
package io.openmessaging.benchmark;

import io.openmessaging.benchmark.utils.RandomGenerator;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.openmessaging.benchmark.utils.PaddingDecimalFormat;
import io.openmessaging.benchmark.utils.Timer;
import io.openmessaging.benchmark.utils.payload.FilePayloadReader;
import io.openmessaging.benchmark.utils.payload.PayloadReader;
import io.openmessaging.benchmark.worker.Worker;
import io.openmessaging.benchmark.worker.commands.ConsumerAssignment;
import io.openmessaging.benchmark.worker.commands.CountersStats;
import io.openmessaging.benchmark.worker.commands.CumulativeLatencies;
import io.openmessaging.benchmark.worker.commands.PeriodStats;
import io.openmessaging.benchmark.worker.commands.ProducerWorkAssignment;
import io.openmessaging.benchmark.worker.commands.TopicSubscription;
import io.openmessaging.benchmark.worker.commands.TopicsInfo;

public class WorkloadGenerator implements AutoCloseable {

    private final String driverName;
    private final Workload workload;
    private final Worker worker;

    private final ExecutorService executor = Executors
            .newCachedThreadPool(new DefaultThreadFactory("messaging-benchmark"));

    private volatile boolean runCompleted = false;
    private volatile boolean needToWaitForBacklogDraining = false;

    private volatile double targetPublishRate;

    public WorkloadGenerator(String driverName, Workload workload, Worker worker) {
        this.driverName = driverName;
        this.workload = workload;
        this.worker = worker;

        if (workload.consumerBacklogSizeGB > 0 && workload.producerRate == 0) {
            throw new IllegalArgumentException("Cannot probe producer sustainable rate when building backlog");
        }
    }

    public TestResult run() throws Exception {
        Timer timer = new Timer();
        TopicsInfo ti = TopicsInfo.fromWorkload(workload);
        List<String> topics = worker.createOrValidateTopics(ti);
        log.info("{} {} topics in {} ms", ti.isExistingTopics() ? "Validated" : "Created", topics.size(), timer.elapsedMillis());

        if (ti.isExistingTopics()) {
          createProducers(ti.existingProduceTopics);
          createConsumers(ti.existingConsumeTopics);
        } else {
          createConsumers(topics);
          createProducers(topics);
        }

        ensureTopicsAreReady();

        if (workload.producerRate > 0) {
            targetPublishRate = workload.producerRate;
        } else {
            // Producer rate is 0 and we need to discover the sustainable rate
            targetPublishRate = 10000;

            executor.execute(() -> {
                // Run background controller to adjust rate
                try {
                    findMaximumSustainableRate(targetPublishRate);
                } catch (IOException e) {
                    log.warn("Failure in finding max sustainable rate", e);
                }
            });
        }

        final PayloadReader payloadReader = new FilePayloadReader(workload.messageSize);

        ProducerWorkAssignment producerWorkAssignment = new ProducerWorkAssignment();
        producerWorkAssignment.keyDistributorType = workload.keyDistributor;
        producerWorkAssignment.publishRate = targetPublishRate;
        producerWorkAssignment.payloadData = new ArrayList<>();

        if(workload.useRandomizedPayloads) {
            // create messages that are part random and part zeros
            // better for testing effects of compression
            Random r = new Random();
            int randomBytes = (int)(workload.messageSize * workload.randomBytesRatio);
            int zerodBytes = workload.messageSize - randomBytes;
            for(int i = 0; i<workload.randomizedPayloadPoolSize; i++) {
                byte[] randArray = new byte[randomBytes];
                r.nextBytes(randArray);
                byte[] zerodArray = new byte[zerodBytes];
                byte[] combined = ArrayUtils.addAll(randArray, zerodArray);
                producerWorkAssignment.payloadData.add(combined);
            }
        }
        else {
            File payloadFile = new File(workload.payloadFile);
            if (payloadFile.isDirectory()) {
                File[] payloadFileList = payloadFile.listFiles();

                if (payloadFileList.length == 0) {
                    throw new IllegalArgumentException("Payload file must either point to a file or a directory with one or more payload files");
                }

                for (File payloadF : payloadFileList) {
                    producerWorkAssignment.payloadData.add(payloadReader.load(payloadF.getAbsolutePath()));
                }
            } else {
                producerWorkAssignment.payloadData.add(payloadReader.load(workload.payloadFile));
            }
        }

        worker.startLoad(producerWorkAssignment);

        if (workload.warmupDurationMinutes > 0) {
            log.info("----- Starting warm-up traffic ({}m) ------", workload.warmupDurationMinutes);
            printAndCollectStats(workload.warmupDurationMinutes, TimeUnit.MINUTES);
        }

        if (workload.consumerBacklogSizeGB > 0) {
            executor.execute(() -> {
                try {
                    buildAndDrainBacklog(topics);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        worker.resetStats();
        log.info("----- Starting benchmark traffic ({}m)------", workload.testDurationMinutes);

        TestResult result = printAndCollectStats(workload.testDurationMinutes, TimeUnit.MINUTES);
        runCompleted = true;

        try {
            worker.stopAll();
        } catch (Exception e) {
            log.error("Unable to stop workload - {}", e.toString());
        }
        return result;
    }

    private void ensureTopicsAreReady() throws IOException {

        if (workload.getConsumerCount() == 0) {
            // no consumers so the check below will always time out, so just
            // short-circuit here
            log.info("Not waiting for consumers because there are none");
            return;
        }

        log.info("Waiting for consumers to be ready");
        // This is work around the fact that there's no way to have a consumer ready in
        // Kafka without first publishing
        // some message on the topic, which will then trigger the partitions assignment
        // to the consumers

        int expectedMessages = workload.topics * workload.subscriptionsPerTopic;

        // In this case we just publish 1 message and then wait for consumers to receive
        // the data
        worker.probeProducers();

        long start = System.currentTimeMillis();
        long end = start + 60 * 1000;
        while (System.currentTimeMillis() < end) {
            CountersStats stats = worker.getCountersStats();
            if (stats.messagesReceived < expectedMessages) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                break;
            }
        }

        if (System.currentTimeMillis() >= end) {
            log.warn("Timed out waiting for consumers to be ready");
        } else {
            log.info("All consumers are ready");
        }
    }

    /**
     * Adjust the publish rate to a level that is sustainable, meaning that we can
     * consume all the messages that are being produced
     */
    private void findMaximumSustainableRate(double currentRate) throws IOException {
        double maxRate = Double.MAX_VALUE; // Discovered max sustainable rate
        double minRate = 0.1;

        CountersStats stats = worker.getCountersStats();

        long localTotalMessagesSentCounter = stats.messagesSent;
        long localTotalMessagesReceivedCounter = stats.messagesReceived;

        int controlPeriodMillis = 3000;
        long lastControlTimestamp = System.nanoTime();

        int successfulPeriods = 0;

        while (!runCompleted) {
            // Check every few seconds and adjust the rate
            try {
                Thread.sleep(controlPeriodMillis);
            } catch (InterruptedException e) {
                return;
            }

            // Consider multiple copies when using multiple subscriptions
            stats = worker.getCountersStats();
            long currentTime = System.nanoTime();
            long totalMessagesSent = stats.messagesSent;
            long totalMessagesReceived = stats.messagesReceived;
            long messagesPublishedInPeriod = totalMessagesSent - localTotalMessagesSentCounter;
            long messagesReceivedInPeriod = totalMessagesReceived - localTotalMessagesReceivedCounter;
            double publishRateInLastPeriod = messagesPublishedInPeriod / (double) (currentTime - lastControlTimestamp)
                    * TimeUnit.SECONDS.toNanos(1);
            double receiveRateInLastPeriod = messagesReceivedInPeriod / (double) (currentTime - lastControlTimestamp)
                    * TimeUnit.SECONDS.toNanos(1);

            if (log.isDebugEnabled()) {
                log.debug(
                        "total-send: {} -- total-received: {} -- int-sent: {} -- int-received: {} -- sent-rate: {} -- received-rate: {}",
                        totalMessagesSent, totalMessagesReceived, messagesPublishedInPeriod, messagesReceivedInPeriod,
                        publishRateInLastPeriod, receiveRateInLastPeriod);
            }

            localTotalMessagesSentCounter = totalMessagesSent;
            localTotalMessagesReceivedCounter = totalMessagesReceived;
            lastControlTimestamp = currentTime;

            if (log.isDebugEnabled()) {
                log.debug("Current rate: {} -- Publish rate {} -- Consume Rate: {} -- min-rate: {} -- max-rate: {}",
                        dec.format(currentRate), dec.format(publishRateInLastPeriod),
                        dec.format(receiveRateInLastPeriod), dec.format(minRate), dec.format(maxRate));
            }

            if (publishRateInLastPeriod < currentRate * 0.95) {
                // Producer is not able to publish as fast as requested
                maxRate = currentRate * 1.1;
                currentRate = minRate + (currentRate - minRate) / 2;

                log.debug("Publishers are not meeting requested rate. reducing to {}", currentRate);
            } else if (receiveRateInLastPeriod < publishRateInLastPeriod * 0.98) {
                // If the consumers are building backlog, we should slow down publish rate
                maxRate = currentRate;
                currentRate = minRate + (currentRate - minRate) / 2;
                log.debug("Consumers are not meeting requested rate. reducing to {}", currentRate);

                // Slows the publishes to let the consumer time to absorb the backlog
                worker.adjustPublishRate(minRate / 10);
                while (true) {
                    stats = worker.getCountersStats();
                    long backlog = workload.subscriptionsPerTopic * stats.messagesSent - stats.messagesReceived;
                    if (backlog < 1000) {
                        break;
                    }

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                log.debug("Resuming load at reduced rate");
                worker.adjustPublishRate(currentRate);

                try {
                    // Wait some more time for the publish rate to catch up
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }

                stats = worker.getCountersStats();
                localTotalMessagesSentCounter = stats.messagesSent;
                localTotalMessagesReceivedCounter = stats.messagesReceived;

            } else if (currentRate < maxRate) {
                minRate = currentRate;
                currentRate = Math.min(currentRate * 2, maxRate);
                log.debug("No bottleneck found, increasing the rate to {}", currentRate);
            } else if (++successfulPeriods > 3) {
                minRate = currentRate * 0.95;
                maxRate = currentRate * 1.05;
                successfulPeriods = 0;
            }

            worker.adjustPublishRate(currentRate);
        }
    }

    @Override
    public void close() throws Exception {
        worker.stopAll();
        executor.shutdownNow();
    }

    private void createConsumers(List<String> topics) throws IOException {
        ConsumerAssignment consumerAssignment = new ConsumerAssignment();

        for(String topic: topics){
            for(int i = 0; i < workload.subscriptionsPerTopic; i++){
                String subscriptionName = String.format("sub-%03d-%s", i, RandomGenerator.getRandomString());
                for (int j = 0; j < workload.consumerPerSubscription; j++) {
                    consumerAssignment.topicsSubscriptions
                        .add(new TopicSubscription(topic, subscriptionName));
                }
            }
        }

        Collections.shuffle(consumerAssignment.topicsSubscriptions);

        Timer timer = new Timer();

        worker.createConsumers(consumerAssignment);
        log.info("Created {} consumers in {} ms", consumerAssignment.topicsSubscriptions.size(), timer.elapsedMillis());
    }

    private void createProducers(List<String> topics) throws IOException {
        List<String> fullListOfTopics = new ArrayList<>();

        // Add the topic multiple times, one for each producer
        for (int i = 0; i < workload.producersPerTopic; i++) {
            topics.forEach(fullListOfTopics::add);
        }

        Collections.shuffle(fullListOfTopics);

        Timer timer = new Timer();

        worker.createProducers(fullListOfTopics);
        log.info("Created {} producers in {} ms", fullListOfTopics.size(), timer.elapsedMillis());
    }

    private void buildAndDrainBacklog(List<String> topics) throws IOException {
        log.info("Stopping all consumers to build backlog");
        worker.pauseConsumers();

        this.needToWaitForBacklogDraining = true;

        long requestedBacklogSize = workload.consumerBacklogSizeGB * 1024 * 1024 * 1024;

        while (true) {
            CountersStats stats = worker.getCountersStats();
            long currentBacklogSize = (workload.subscriptionsPerTopic * stats.messagesSent - stats.messagesReceived)
                    * workload.messageSize;

            if (currentBacklogSize >= requestedBacklogSize) {
                break;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        log.info("--- Start draining backlog ---");

        worker.resumeConsumers();

        final long minBacklog = 1000;

        while (true) {
            CountersStats stats = worker.getCountersStats();
            long currentBacklog = workload.subscriptionsPerTopic * stats.messagesSent - stats.messagesReceived;
            if (currentBacklog <= minBacklog) {
                log.info("--- Completed backlog draining ---");
                needToWaitForBacklogDraining = false;
                return;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static public void printPeriodStats(PeriodStats stats, double elapsedSeconds, double currentBacklog) {
        double publishRate = stats.messagesSent / elapsedSeconds;
        double consumeRate = stats.messagesReceived / elapsedSeconds;
        double publishThroughput = stats.bytesSent / elapsedSeconds / 1024 / 1024;
        double consumeThroughput = stats.bytesReceived / elapsedSeconds / 1024 / 1024;

        log.info(
            "Pub rate {} msg/s / {} MB/s | Cons rate {} msg/s / {} MB/s | Backlog: {} K | Pub Latency (ms) avg: {} - 50%: {} - 99%: {} - 99.9%: {} - Max: {} | Pub Delay Latency (us) avg: {} - 50%: {} - 99%: {} - 99.9%: {} - Max: {}",
            rateFormat.format(publishRate), throughputFormat.format(publishThroughput),
            rateFormat.format(consumeRate), throughputFormat.format(consumeThroughput),
            dec.format(currentBacklog / 1000.0), //
            dec.format(microsToMillis(stats.publishLatency.getMean())),
            dec.format(microsToMillis(stats.publishLatency.getValueAtPercentile(50))),
            dec.format(microsToMillis(stats.publishLatency.getValueAtPercentile(99))),
            dec.format(microsToMillis(stats.publishLatency.getValueAtPercentile(99.9))),
            throughputFormat.format(microsToMillis(stats.publishLatency.getMaxValue())),
            dec.format(stats.publishDelayLatency.getMean()),
            dec.format(stats.publishDelayLatency.getValueAtPercentile(50)),
            dec.format(stats.publishDelayLatency.getValueAtPercentile(99)),
            dec.format(stats.publishDelayLatency.getValueAtPercentile(99.9)),
            throughputFormat.format(stats.publishDelayLatency.getMaxValue()));

        log.info("E2E Latency (ms) avg: {} - 50%: {} - 99%: {} - 99.9%: {} - Max: {}",
            dec.format(microsToMillis(stats.endToEndLatency.getMean())),
            dec.format(microsToMillis(stats.endToEndLatency.getValueAtPercentile(50))),
            dec.format(microsToMillis(stats.endToEndLatency.getValueAtPercentile(99))),
            dec.format(microsToMillis(stats.endToEndLatency.getValueAtPercentile(99.9))),
            throughputFormat.format(microsToMillis(stats.endToEndLatency.getMaxValue())));
    }

    private TestResult printAndCollectStats(long testDurations, TimeUnit unit) throws IOException {
        long startTime = System.nanoTime();

        // Print report stats
        long oldTime = System.nanoTime();

        long testEndTime = testDurations > 0 ? startTime + unit.toNanos(testDurations) : Long.MAX_VALUE;

        TestResult result = new TestResult();
        result.workload = workload.name;
        result.driver = driverName;
        result.topics = workload.topics;
        result.partitions = workload.partitionsPerTopic;
        result.messageSize = workload.messageSize;
        result.producersPerTopic = workload.producersPerTopic;
        result.consumersPerTopic = workload.consumerPerSubscription;
        result.sampleRateMillis = workload.sampleRateMillis;

        while (true) {
            try {
                Thread.sleep(workload.sampleRateMillis);
            } catch (InterruptedException e) {
                break;
            }

            PeriodStats stats = worker.getPeriodStats();

            long now = System.nanoTime();
            double elapsed = (now - oldTime) / 1e9;

            double publishRate = stats.messagesSent / elapsed;
            double consumeRate = stats.messagesReceived / elapsed;

            long currentBacklog = workload.subscriptionsPerTopic * stats.totalMessagesSent
                    - stats.totalMessagesReceived;

            printPeriodStats(stats, elapsed, currentBacklog);

            result.sent.add(stats.messagesSent);
            result.consumed.add(stats.messagesReceived);
            result.publishFailed.add(stats.errors);
            result.consumeFailed.add(stats.pollErrors);

            result.publishRate.add(publishRate);
            result.consumeRate.add(consumeRate);
            result.backlog.add(currentBacklog);
            result.publishLatencyAvg.add(microsToMillis(stats.publishLatency.getMean()));
            result.publishLatencyMin.add(microsToMillis(stats.publishLatency.getMinValue()));
            result.publishLatency50pct.add(microsToMillis(stats.publishLatency.getValueAtPercentile(50)));
            result.publishLatency75pct.add(microsToMillis(stats.publishLatency.getValueAtPercentile(75)));
            result.publishLatency95pct.add(microsToMillis(stats.publishLatency.getValueAtPercentile(95)));
            result.publishLatency99pct.add(microsToMillis(stats.publishLatency.getValueAtPercentile(99)));
            result.publishLatency999pct.add(microsToMillis(stats.publishLatency.getValueAtPercentile(99.9)));
            result.publishLatency9999pct.add(microsToMillis(stats.publishLatency.getValueAtPercentile(99.99)));
            result.publishLatencyMax.add(microsToMillis(stats.publishLatency.getMaxValue()));

            result.scheduleLatencyMin.add(microsToMillis(stats.scheduleLatency.getMinValue()));
            result.scheduleLatency50pct.add(microsToMillis(stats.scheduleLatency.getValueAtPercentile(50)));
            result.scheduleLatency75pct.add(microsToMillis(stats.scheduleLatency.getValueAtPercentile(75)));
            result.scheduleLatency99pct.add(microsToMillis(stats.scheduleLatency.getValueAtPercentile(99)));
            result.scheduleLatencyMax.add(microsToMillis(stats.scheduleLatency.getMaxValue()));

            result.publishDelayLatencyAvg.add(stats.publishDelayLatency.getMean());
            result.publishDelayLatency50pct.add(stats.publishDelayLatency.getValueAtPercentile(50));
            result.publishDelayLatency75pct.add(stats.publishDelayLatency.getValueAtPercentile(75));
            result.publishDelayLatency95pct.add(stats.publishDelayLatency.getValueAtPercentile(95));
            result.publishDelayLatency99pct.add(stats.publishDelayLatency.getValueAtPercentile(99));
            result.publishDelayLatency999pct.add(stats.publishDelayLatency.getValueAtPercentile(99.9));
            result.publishDelayLatency9999pct.add(stats.publishDelayLatency.getValueAtPercentile(99.99));
            result.publishDelayLatencyMax.add(stats.publishDelayLatency.getMaxValue());

            result.endToEndLatencyAvg.add(microsToMillis(stats.endToEndLatency.getMean()));
            result.endToEndLatencyMin.add(microsToMillis(stats.endToEndLatency.getMinValue()));
            result.endToEndLatency50pct.add(microsToMillis(stats.endToEndLatency.getValueAtPercentile(50)));
            result.endToEndLatency75pct.add(microsToMillis(stats.endToEndLatency.getValueAtPercentile(75)));
            result.endToEndLatency95pct.add(microsToMillis(stats.endToEndLatency.getValueAtPercentile(95)));
            result.endToEndLatency99pct.add(microsToMillis(stats.endToEndLatency.getValueAtPercentile(99)));
            result.endToEndLatency999pct.add(microsToMillis(stats.endToEndLatency.getValueAtPercentile(99.9)));
            result.endToEndLatency9999pct.add(microsToMillis(stats.endToEndLatency.getValueAtPercentile(99.99)));
            result.endToEndLatencyMax.add(microsToMillis(stats.endToEndLatency.getMaxValue()));

            if (now >= testEndTime && !needToWaitForBacklogDraining) {
                CumulativeLatencies agg = worker.getCumulativeLatencies();;

                log.info(
                        "----- Aggregated Pub Latency (ms) avg: {} - 50%: {} - 95%: {} - 99%: {} - 99.9%: {} - 99.99%: {} - Max: {} | Pub Delay (us)  avg: {} - 50%: {} - 95%: {} - 99%: {} - 99.9%: {} - 99.99%: {} - Max: {}",
                        dec.format(agg.publishLatency.getMean() / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(50) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(95) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(99) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(99.9) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(99.99) / 1000.0),
                        throughputFormat.format(agg.publishLatency.getMaxValue() / 1000.0),
                        dec.format(agg.publishDelayLatency.getMean()),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(50)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(95)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(99)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(99.9)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(99.99)),
                        throughputFormat.format(agg.publishDelayLatency.getMaxValue()));

                result.aggregatedPublishLatencyAvg = microsToMillis(agg.publishLatency.getMean());
                result.aggregatedPublishLatency50pct = microsToMillis(agg.publishLatency.getValueAtPercentile(50));
                result.aggregatedPublishLatency75pct = microsToMillis(agg.publishLatency.getValueAtPercentile(75));
                result.aggregatedPublishLatency95pct = microsToMillis(agg.publishLatency.getValueAtPercentile(95));
                result.aggregatedPublishLatency99pct = microsToMillis(agg.publishLatency.getValueAtPercentile(99));
                result.aggregatedPublishLatency999pct = microsToMillis(agg.publishLatency.getValueAtPercentile(99.9));
                result.aggregatedPublishLatency9999pct = microsToMillis(agg.publishLatency.getValueAtPercentile(99.99));
                result.aggregatedPublishLatencyMax = microsToMillis(agg.publishLatency.getMaxValue());

                result.aggregatedEndToEndLatencyAvg = microsToMillis(agg.endToEndLatency.getMean());
                result.aggregatedEndToEndLatency50pct = microsToMillis(agg.endToEndLatency.getValueAtPercentile(50));
                result.aggregatedEndToEndLatency75pct = microsToMillis(agg.endToEndLatency.getValueAtPercentile(75));
                result.aggregatedEndToEndLatency95pct = microsToMillis(agg.endToEndLatency.getValueAtPercentile(95));
                result.aggregatedEndToEndLatency99pct = microsToMillis(agg.endToEndLatency.getValueAtPercentile(99));
                result.aggregatedEndToEndLatency999pct = microsToMillis(agg.endToEndLatency.getValueAtPercentile(99.9));
                result.aggregatedEndToEndLatency9999pct = microsToMillis(
                        agg.endToEndLatency.getValueAtPercentile(99.99));
                result.aggregatedEndToEndLatencyMax = microsToMillis(agg.endToEndLatency.getMaxValue());

                result.aggregatedPublishDelayLatencyAvg = agg.publishDelayLatency.getMean();
                result.aggregatedPublishDelayLatency50pct = agg.publishDelayLatency.getValueAtPercentile(50);
                result.aggregatedPublishDelayLatency75pct = agg.publishDelayLatency.getValueAtPercentile(75);
                result.aggregatedPublishDelayLatency95pct = agg.publishDelayLatency.getValueAtPercentile(95);
                result.aggregatedPublishDelayLatency99pct = agg.publishDelayLatency.getValueAtPercentile(99);
                result.aggregatedPublishDelayLatency999pct = agg.publishDelayLatency.getValueAtPercentile(99.9);
                result.aggregatedPublishDelayLatency9999pct = agg.publishDelayLatency.getValueAtPercentile(99.99);
                result.aggregatedPublishDelayLatencyMax = agg.publishDelayLatency.getMaxValue();

                agg.publishLatency.percentiles(100).forEach(value -> {
                    result.aggregatedPublishLatencyQuantiles.put(value.getPercentile(),
                            microsToMillis(value.getValueIteratedTo()));
                });

                agg.scheduleLatency.percentiles(100).forEach(value -> {
                    result.aggregatedScheduleLatencyQuantiles.put(value.getPercentile(),
                            microsToMillis(value.getValueIteratedTo()));
                });

                agg.publishDelayLatency.percentiles(100).forEach(value -> {
                    result.aggregatedPublishDelayLatencyQuantiles.put(value.getPercentile(),
                            value.getValueIteratedTo());
                });

                agg.publishDelayLatency.percentiles(100).forEach(value -> {
                    result.aggregatedPublishDelayLatencyQuantiles.put(value.getPercentile(),
                            value.getValueIteratedTo());
                });

                agg.endToEndLatency.percentiles(100).forEach(value -> {
                    result.aggregatedEndToEndLatencyQuantiles.put(value.getPercentile(),
                            microsToMillis(value.getValueIteratedTo()));
                });

                break;
            }

            oldTime = now;
        }

        return result;
    }

    private static double microsToMillis(double microTime) {
        return microTime / (1000);
    }

    private static double microsToMillis(long microTime) {
        return microTime / (1000.0);
    }

    private static final DecimalFormat rateFormat = new PaddingDecimalFormat("0.000", 7);
    private static final DecimalFormat throughputFormat = new PaddingDecimalFormat("0.000", 4);
    private static final DecimalFormat dec = new PaddingDecimalFormat("0.000", 4);

    private static final Logger log = LoggerFactory.getLogger(WorkloadGenerator.class);
}
