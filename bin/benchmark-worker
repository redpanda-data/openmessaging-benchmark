#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

if [ -d "./lib" ]; then
        CLASSPATH=$CLASSPATH:lib/*
else
    CLASSPATH=benchmark-framework/target/classes:`cat benchmark-framework/target/classpath.txt`
fi

#KAFKA_JMX_OPTS="-Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote=true -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djava.rmi.server.hostname=0.0.0.0 -Djava.net.preferIPv4Stack=true"
JVM_MEM="-Xms4G -Xmx8G -XX:+UseG1GC"
#KAFKA_OPTS="-javaagent:/opt/benchmark/jmx_prometheus_javaagent-0.13.0.jar=9090:/opt/benchmark/metrics.yml"
JVM_GC_LOG=" -XX:+PrintGCDetails -XX:+PrintGCApplicationStoppedTime  -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=64m  -Xloggc:/dev/shm/benchmark-client-gc_%p.log"

exec java -server $KAFKA_JMX_OPTS -cp $CLASSPATH $JVM_MEM $KAFKA_OPTS io.openmessaging.benchmark.worker.BenchmarkWorker $*
