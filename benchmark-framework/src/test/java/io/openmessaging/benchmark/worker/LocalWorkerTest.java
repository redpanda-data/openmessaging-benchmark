package io.openmessaging.benchmark.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkDriver;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import io.openmessaging.benchmark.utils.distributor.KeyDistributorType;
import io.openmessaging.benchmark.worker.commands.ProducerWorkAssignment;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.bookkeeper.stats.StatsLogger;
import org.junit.Test;

public class LocalWorkerTest {

    private static class KeyOnlyProducer implements BenchmarkProducer {
        @Override
        public CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public int partitionCount() {
            return 4;
        }

        @Override
        public void close() {
        }
    }

    private static class PartitionedProducer extends KeyOnlyProducer {
        @Override
        public CompletableFuture<Void> sendAsync(Optional<String> key, int partition, byte[] payload) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class RecordingProducer extends PartitionedProducer {
        /** Each send as {partition asked for (-1 when left to the client), nanoTime}. */
        final List<long[]> sends = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload) {
            sends.add(new long[] {-1, System.nanoTime()});
            return super.sendAsync(key, payload);
        }

        @Override
        public CompletableFuture<Void> sendAsync(Optional<String> key, int partition, byte[] payload) {
            sends.add(new long[] {partition, System.nanoTime()});
            return super.sendAsync(key, partition, payload);
        }
    }

    /** Loaded by name from the driver config, so public with a no-arg constructor. */
    public static class RecordingDriver implements BenchmarkDriver {
        static volatile RecordingProducer lastProducer;

        @Override
        public void initialize(File configurationFile, StatsLogger statsLogger) {
        }

        @Override
        public String getTopicNamePrefix() {
            return "test";
        }

        @Override
        public CompletableFuture<Void> createTopic(String topic, int partitions) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<BenchmarkProducer> createProducer(String topic) {
            lastProducer = new RecordingProducer();
            return CompletableFuture.completedFuture(lastProducer);
        }

        @Override
        public CompletableFuture<BenchmarkConsumer> createConsumer(String topic, String subscriptionName,
                ConsumerCallback consumerCallback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    /** Runs one producer at publishRate until at least {@code atLeast} sends happened, then stops it. */
    private static List<long[]> sends(double publishRate, double stickyRecords, int atLeast) throws Exception {
        File driverFile = File.createTempFile("driver", ".yaml");
        driverFile.deleteOnExit();
        Files.write(driverFile.toPath(),
                ("name: test\ndriverClass: " + RecordingDriver.class.getName() + "\n").getBytes());
        LocalWorker worker = new LocalWorker();
        List<long[]> sends;
        try {
            worker.initializeDriver(driverFile);
            worker.createProducers(Collections.singletonList("topic"));
            sends = RecordingDriver.lastProducer.sends;
            ProducerWorkAssignment assignment = new ProducerWorkAssignment();
            assignment.publishRate = publishRate;
            assignment.keyDistributorType = KeyDistributorType.NO_KEY;
            assignment.payloadData = Collections.singletonList(new byte[10]);
            assignment.partitionStickyRecords = stickyRecords;
            worker.startLoad(assignment);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (sends.size() < atLeast && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            worker.stopAll();
        } finally {
            worker.close();
        }
        assertTrue("only " + sends.size() + " sends", sends.size() >= atLeast);
        return new ArrayList<>(sends);
    }

    @Test
    public void detectsProducerWithoutPartitionedSend() {
        assertFalse(LocalWorker.overridesPartitionedSend(new KeyOnlyProducer()));
    }

    @Test
    public void detectsProducerWithPartitionedSend() {
        assertTrue(LocalWorker.overridesPartitionedSend(new PartitionedProducer()));
    }

    @Test
    public void stickyRunGoesToOnePartitionBackToBack() throws Exception {
        // 2 permits per second: a run of 4 takes 4 permits, so the next run starts 2 s after the first. A paced run
        // would span 1.5 s. The bounds leave 500 ms or more for a slow machine.
        List<long[]> sends = sends(2, 4, 5);
        assertTrue(sends.get(0)[0] >= 0);
        for (int i = 1; i < 4; i++) {
            assertEquals(sends.get(0)[0], sends.get(i)[0]);
        }
        long runNs = sends.get(3)[1] - sends.get(0)[1];
        assertTrue("run of 4 took " + runNs + " ns", runNs < TimeUnit.MILLISECONDS.toNanos(500));
        long nextRunNs = sends.get(4)[1] - sends.get(0)[1];
        assertTrue("next run after " + nextRunNs + " ns", nextRunNs >= TimeUnit.MILLISECONDS.toNanos(1000));
    }

    @Test
    public void defaultSendsOneRecordPerPermitAndLeavesPartitioningToTheClient() throws Exception {
        // 2 permits per second: sends at 0, 500 and 1000 ms.
        List<long[]> sends = sends(2, 1, 3);
        for (long[] send : sends.subList(0, 3)) {
            assertEquals(-1, send[0]);
        }
        long spanNs = sends.get(2)[1] - sends.get(0)[1];
        assertTrue("3 sends took " + spanNs + " ns", spanNs >= TimeUnit.MILLISECONDS.toNanos(500));
    }
}
