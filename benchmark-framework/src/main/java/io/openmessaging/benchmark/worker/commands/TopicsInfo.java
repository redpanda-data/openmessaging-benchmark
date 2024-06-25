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
package io.openmessaging.benchmark.worker.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;

import io.openmessaging.benchmark.Workload;

public class TopicsInfo {
    public int numberOfTopics;
    public int numberOfPartitionsPerTopic;

    /** If the following arg non-empty, the properties above are zero and vice-versa. */
    @JsonProperty
    public List<String> existingProduceTopics = Collections.emptyList();
    @JsonProperty
    public List<String> existingConsumeTopics = Collections.emptyList();

    public TopicsInfo() {
    }

    public TopicsInfo(int numberOfTopics, int numberOfPartitionsPerTopic) {
        this.numberOfTopics = numberOfTopics;
        this.numberOfPartitionsPerTopic = numberOfPartitionsPerTopic;
    }

    public TopicsInfo(List<String> existingProduceTopics, List<String> existingConsumeTopics) {
        Preconditions.checkNotNull(existingProduceTopics);
        Preconditions.checkNotNull(existingConsumeTopics);
        Preconditions.checkArgument(!existingProduceTopics.isEmpty());
        Preconditions.checkArgument(!existingConsumeTopics.isEmpty());
        this.existingProduceTopics = existingProduceTopics;
        this.existingConsumeTopics = existingConsumeTopics;
    }

    /** @return true iff existing topics are to be used  */
    @JsonIgnore
    public boolean isExistingTopics() {
        return !allExistingTopics().isEmpty();
    }

    @JsonIgnore
    public List<String> allExistingTopics() {
      List<String> combined = new ArrayList<>();
      combined.addAll(existingProduceTopics);
      combined.addAll(existingConsumeTopics);
      return combined;
    }

    /**
     * Create a TopicsInfo object based on a workload object, either populating
     * the numberOfTopics + partitions field for a workload which will use newly
     * creted topics, or the existing topics field if existing topics are to be used.
     */
    public static TopicsInfo fromWorkload(Workload w) {
        Preconditions.checkNotNull(w);
        Preconditions.checkArgument(w.topics > 0 != w.isUsingExistingTopics());
        if (w.topics > 0) {
            return new TopicsInfo(w.topics, w.partitionsPerTopic);
        } else {
            List<String> consume = new ArrayList<>(w.existingTopicList);
            consume.addAll(w.existingConsumeTopicList);
            List<String> produce = new ArrayList<>(w.existingTopicList);
            produce.addAll(w.existingProduceTopicList);
            return new TopicsInfo(produce, consume);
        }
    }
}
