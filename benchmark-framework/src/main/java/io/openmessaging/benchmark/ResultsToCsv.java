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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ResultsToCsv {

    private static final Logger log = LoggerFactory.getLogger(ResultsToCsv.class);

    public void writeAllResultFiles(File directory) {
        try {
            File[] directoryListing = directory.listFiles();
            Arrays.sort(directoryListing);

            List<String> lines = new ArrayList<>();
            lines.add("workload,driver,version,beginTime,endTime,topics,partitions," +
                    "message-size,producers-per-topic,consumers-per-topic," +
                    "prod-rate-min,prod-rate-avg,prod-rate-std-dev,prod-rate-max," +
                    "con-rate-min,con-rate-avg,con-rate-std-dev,con-rate-max," +
                    "aggregatedPublishLatencyAvg,aggregatedPublishLatency50pct,aggregatedPublishLatency75pct," +
                    "aggregatedPublishLatency95pct,aggregatedPublishLatency99pct,aggregatedPublishLatency999pct," +
                    "aggregatedPublishLatency9999pct,aggregatedPublishLatencyMax,aggregatedPublishDelayLatencyAvg," +
                    "aggregatedPublishDelayLatency50pct,aggregatedPublishDelayLatency75pct," +
                    "aggregatedPublishDelayLatency95pct,aggregatedPublishDelayLatency99pct," +
                    "aggregatedPublishDelayLatency999pct,aggregatedPublishDelayLatency9999pct," +
                    "aggregatedPublishDelayLatencyMax,aggregatedEndToEndLatencyAvg,aggregatedEndToEndLatency50pct," +
                    "aggregatedEndToEndLatency75pct,aggregatedEndToEndLatency95pct,aggregatedEndToEndLatency99pct," +
                    "aggregatedEndToEndLatency999pct,aggregatedEndToEndLatency9999pct,aggregatedEndToEndLatencyMax");

            List<TestResult> results = new ArrayList<>();
            for (File file : directoryListing) {
                if (file.isFile() && file.getAbsolutePath().endsWith(".json")) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    TestResult tr = objectMapper.readValue(new File(file.getAbsolutePath()), TestResult.class);
                    results.add(tr);
                }
            }

            List<TestResult> sortedResults = results.stream().sorted(
                                                Comparator.comparing(TestResult::getMessageSize)
                                                .thenComparing(TestResult::getTopics)
                                                .thenComparing(TestResult::getPartitions)).collect(Collectors.toList());
            for(TestResult tr : sortedResults) {
                lines.add(extractResults(tr));
            }

            File csvFile = new File(directory, "results-" + Instant.now().getEpochSecond() + ".csv");
            FileWriter writer = new FileWriter(csvFile);
            for (String str : lines) {
                writer.write(str + System.lineSeparator());
            }
            writer.close();
            log.info("Results extracted into CSV " + csvFile.getAbsolutePath());
        }
        catch(Exception e) {
            log.error("Failed creating csv file.", e);
        }
    }

    public String extractResults(TestResult tr) {
        try {
            Histogram prodRateHistogram = new Histogram(10000000, 1);
            Histogram conRateHistogram = new Histogram(10000000, 1);

            for(Double rate : tr.publishRate) {
                prodRateHistogram.recordValueWithCount(rate.longValue(), 2);
            }

            for(Double rate : tr.consumeRate) {
                conRateHistogram.recordValueWithCount(rate.longValue(), 2);
            }

            String line = MessageFormat.format("{0},{1},{2},{3},{4},{5,number,#}," +
                            "{6,number,#},{7,number,#},{8,number,#},{9,number,#}," +
                            "{10,number,#},{11,number,#},{12,number,#.##},{13,number,#}," +
                            "{14,number,#},{15,number,#},{16,number,#.##},{17,number,#}," +
                            "{18,number,#.###},{19,number,#.###},{20,number,#.###},{21,number,#.###}," +
                            "{22,number,#.###},{23,number,#.###},{24,number,#.###},{25,number,#.###}," +
                            "{26,number,#.###},{27,number,#.###},{28,number,#.###},{29,number,#.###}," +
                            "{30,number,#.###},{31,number,#.###},{32,number,#.###},{33,number,#.###}," +
                            "{34,number,#.###},{35,number,#.###},{36,number,#.###},{37,number,#.###}," +
                            "{38,number,#.###},{39,number,#.###},{40,number,#.###},{41,number,#.###}",
                    tr.workload,
                    tr.driver,
                    tr.version,
                    tr.beginTime,
                    tr.endTime,
                    tr.topics,
                    tr.partitions,
                    tr.messageSize,
                    tr.producersPerTopic,
                    tr.consumersPerTopic,
                    prodRateHistogram.getMinNonZeroValue(),
                    prodRateHistogram.getMean(),
                    prodRateHistogram.getStdDeviation(),
                    prodRateHistogram.getMaxValue(),
                    conRateHistogram.getMinNonZeroValue(),
                    conRateHistogram.getMean(),
                    conRateHistogram.getStdDeviation(),
                    conRateHistogram.getMaxValue(),
                    tr.aggregatedPublishLatencyAvg,
                    tr.aggregatedPublishLatency50pct,
                    tr.aggregatedPublishLatency75pct,
                    tr.aggregatedPublishLatency95pct,
                    tr.aggregatedPublishLatency99pct,
                    tr.aggregatedPublishLatency999pct,
                    tr.aggregatedPublishLatency9999pct,
                    tr.aggregatedPublishLatencyMax,
                    tr.aggregatedPublishDelayLatencyAvg,
                    tr.aggregatedPublishDelayLatency50pct,
                    tr.aggregatedPublishDelayLatency75pct,
                    tr.aggregatedPublishDelayLatency95pct,
                    tr.aggregatedPublishDelayLatency99pct,
                    tr.aggregatedPublishDelayLatency999pct,
                    tr.aggregatedPublishDelayLatency9999pct,
                    tr.aggregatedPublishDelayLatencyMax,
                    tr.aggregatedEndToEndLatencyAvg,
                    tr.aggregatedEndToEndLatency50pct,
                    tr.aggregatedEndToEndLatency75pct,
                    tr.aggregatedEndToEndLatency95pct,
                    tr.aggregatedEndToEndLatency99pct,
                    tr.aggregatedEndToEndLatency999pct,
                    tr.aggregatedEndToEndLatency9999pct,
                    tr.aggregatedEndToEndLatencyMax
                    );

            return line;
        }
        catch(Exception e) {
            log.error("Error writing results csv", e);
            throw new RuntimeException(e);
        }
    }


}
