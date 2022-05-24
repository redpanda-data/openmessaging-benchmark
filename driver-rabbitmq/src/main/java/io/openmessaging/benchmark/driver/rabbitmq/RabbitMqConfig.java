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
package io.openmessaging.benchmark.driver.rabbitmq;

import com.rabbitmq.client.BuiltinExchangeType;

public class RabbitMqConfig {
    public enum QueueType {
        CLASSIC, QUORUM
    }

    public String[] brokers;
    public boolean messagePersistence = false;
    public QueueType queueType = QueueType.CLASSIC;
    public int routingKeyLength = 7;
    public String topicPrefix = "test-topic";
    public BuiltinExchangeType exchangeType = BuiltinExchangeType.DIRECT;
    public boolean exclusive = false;
    public boolean singleNode = false;
}
