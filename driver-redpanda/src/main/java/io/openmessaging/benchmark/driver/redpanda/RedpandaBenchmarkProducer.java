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
package io.openmessaging.benchmark.driver.redpanda;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;

import io.openmessaging.benchmark.driver.BenchmarkProducer;

public class RedpandaBenchmarkProducer implements BenchmarkProducer {

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public RedpandaBenchmarkProducer(KafkaProducer<String, byte[]> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    long nextOut;

    private static void print_metric(String name, Map<MetricName, ? extends Metric> map) {
        for (Map.Entry<MetricName, ? extends Metric> e : map.entrySet()) {
            if (e.getKey().name().equals(name)) {
                System.err.println(name + ": " + e.getValue().metricValue());
                return;
            }
        }
        System.err.println(name + ": NOT FOUND");
    }

    @Override
    public CompletableFuture<Void> sendAsync(Optional<String> key, byte[] payload) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key.orElse(null), payload);

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    future.completeExceptionally(exception);
                } else {
                    future.complete(null);
                }
            });
        } catch(Exception e) {
            future.completeExceptionally(e);
        }

        if (System.nanoTime() > nextOut) {
            Map<MetricName, ? extends Metric> m = producer.metrics();
            // System.err.print("Got " + m.size() + " metrics\n");
            // for (Map.Entry<MetricName, ? extends Metric> e : m.entrySet()) {
            //     System.err.print(e.getKey() + ":\n    " + e.getValue().metricValue().getClass() + "\n");
            //     try {
            //         Measurable mes = ((KafkaMetric)e.getValue()).measurable();
            //         System.err.print("        " + mes.getClass() + "\n");
            //         System.err.print("        " + mes + "\n");
            //     } catch (IllegalStateException ise) {
            //         System.err.print("        GAUGE\n");
            //     }
            // }
            print_metric("batch-size-avg", m);
            print_metric("batch-size-max", m);

            nextOut = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        }

        return future;
    }

    @Override
    public void close() throws Exception {
        producer.close();
    }

}
