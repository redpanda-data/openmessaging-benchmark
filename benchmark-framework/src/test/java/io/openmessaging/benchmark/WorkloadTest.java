package io.openmessaging.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Arrays;
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

    @Test
    public void partitionWeightsDefaultToEmpty() throws Exception {
        Workload w = MAPPER.readValue(BASE, Workload.class);
        assertTrue(w.partitionWeights.isEmpty());
        w.validate();
    }

    @Test
    public void subscriptionNamesDefaultToEmpty() throws Exception {
        Workload w = MAPPER.readValue(BASE + "subscriptionsPerTopic: 2\n", Workload.class);
        assertTrue(w.subscriptionNames.isEmpty());
        w.validate();
    }

    @Test
    public void partitionWeightsParseIntAndStarKeys() throws Exception {
        Workload w = MAPPER.readValue(BASE.replace("partitionsPerTopic: 1", "partitionsPerTopic: 8")
                + "partitionWeights: {3: 0.91, \"*\": 0.09}\n", Workload.class);
        assertEquals(Double.valueOf(0.91), w.partitionWeights.get("3"));
        assertEquals(Double.valueOf(0.09), w.partitionWeights.get("*"));
        w.validate();
    }

    @Test
    public void existingTopicsSkipTheEarlyPartitionCheck() throws Exception {
        // partitionsPerTopic is a placeholder for existing topics; their real count is only known on the worker.
        Workload w = MAPPER.readValue(BASE.replace("\ntopics: 1\n", "\ntopics: 0\nexistingTopicList: [orders]\n")
                + "partitionWeights: {3: 0.91, \"*\": 0.09}\n", Workload.class);
        assertEquals(1, w.partitionsPerTopic);
        w.validate();
    }

    @Test
    public void emptyPartitionWeightsValueIsTreatedAsUnset() throws Exception {
        Workload w = MAPPER.readValue(BASE + "partitionWeights:\n", Workload.class);
        w.validate();
        assertTrue(w.partitionWeights.isEmpty());
    }

    private static void assertValidationFails(String yaml, String expectedMessage) throws Exception {
        Workload w = MAPPER.readValue(yaml, Workload.class);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, w::validate);
        assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
    }

    @Test
    public void invalidPartitionWeightsKeyFailsValidation() throws Exception {
        assertValidationFails(BASE + "partitionWeights: {hot: 1.0}\n", "key 'hot' must be a partition number");
    }

    @Test
    public void partitionBeyondCreatedTopicsFailsValidation() throws Exception {
        assertValidationFails(BASE.replace("partitionsPerTopic: 1", "partitionsPerTopic: 4")
                + "partitionWeights: {7: 1.0}\n", "lists partition 7 but the topic has 4");
    }

    @Test
    public void allZeroSharesFailValidation() throws Exception {
        assertValidationFails(BASE + "partitionWeights: {\"*\": 0.0}\n", "gives no traffic");
    }

    @Test
    public void subscriptionNamesMatchingSubscriptionCountValidate() throws Exception {
        Workload w = MAPPER.readValue(
                BASE + "subscriptionsPerTopic: 2\nsubscriptionNames: [billing, search]\n", Workload.class);
        assertEquals(Arrays.asList("billing", "search"), w.subscriptionNames);
        w.validate();
    }

    @Test
    public void subscriptionNamesCountMismatchFailsValidation() throws Exception {
        assertValidationFails(BASE + "subscriptionsPerTopic: 2\nsubscriptionNames: [billing]\n",
                "subscriptionNames has 1 entries but subscriptionsPerTopic is 2");
    }

    @Test
    public void duplicateSubscriptionNamesFailValidation() throws Exception {
        assertValidationFails(BASE + "subscriptionsPerTopic: 2\nsubscriptionNames: [billing, billing]\n",
                "must not contain duplicate names");
    }

    @Test
    public void blankSubscriptionNameFailsValidation() throws Exception {
        assertValidationFails(BASE + "subscriptionsPerTopic: 1\nsubscriptionNames: [\" \"]\n",
                "must not contain blank names");
    }
}
