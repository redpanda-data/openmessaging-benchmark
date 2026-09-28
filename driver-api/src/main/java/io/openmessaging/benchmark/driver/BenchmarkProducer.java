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
package io.openmessaging.benchmark.driver;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface BenchmarkProducer extends AutoCloseable {

    /**
     * Publish a message and return a callback to track the completion of the operation.
     *
     * @param key
     *            the key associated with this message
     * @param payload
     *            the message payload
     * @return a future that will be triggered when the message is successfully published
     */
    CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload);

    /**
     * Publish a message to a specific partition. Only called when {@link #partitionCount()} is non-negative, so
     * drivers that override one must override both.
     *
     * @param key
     *            the key associated with this message
     * @param partition
     *            the partition to publish to
     * @param payload
     *            the message payload
     * @return a future that will be triggered when the message is successfully published
     */
    default CompletableFuture<Void> sendAsync(Optional<String> key, int partition, byte[] payload) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(new UnsupportedOperationException("this driver cannot target a partition"));
        return future;
    }

    /**
     * @return the partition count of this producer's topic, or -1 if the driver cannot target a partition
     */
    default int partitionCount() {
        return -1;
    }

}
