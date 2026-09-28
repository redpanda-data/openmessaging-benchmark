package io.openmessaging.benchmark.worker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.openmessaging.benchmark.driver.BenchmarkProducer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    @Test
    public void detectsProducerWithoutPartitionedSend() {
        assertFalse(LocalWorker.overridesPartitionedSend(new KeyOnlyProducer()));
    }

    @Test
    public void detectsProducerWithPartitionedSend() {
        assertTrue(LocalWorker.overridesPartitionedSend(new PartitionedProducer()));
    }
}
