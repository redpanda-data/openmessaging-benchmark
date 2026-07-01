package io.openmessaging.benchmark;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

public class WorkloadTest {

    // Mirror the strict YAML workload mapper used in Benchmark.java.
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private static final String BASE =
            "name: drain-test\n"
          + "topics: 1\n"
          + "partitionsPerTopic: 1\n"
          + "messageSize: 100\n"
          + "producerRate: 1000\n"
          + "testDurationMinutes: 1\n";

    @Test
    public void drainProducerRateDefaultsToUnset() throws Exception {
        Workload w = MAPPER.readValue(BASE, Workload.class);
        assertEquals(-1, w.drainProducerRate);
    }

    @Test
    public void drainProducerRateParsesWhenPresent() throws Exception {
        Workload w = MAPPER.readValue(BASE + "drainProducerRate: 5000\n", Workload.class);
        assertEquals(5000, w.drainProducerRate);
    }
}
