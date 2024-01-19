#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

FROM ${base}

ARG BENCHMARK_TARBALL
ADD ${BENCHMARK_TARBALL} /

RUN mv openmessaging-benchmark-* /opt/benchmark
WORKDIR /opt/benchmark

ENV JMX_EXPORTER="/opt/benchmark/driver-redpanda/deploy/monitoring/jmx_exporter"
ENV KAFKA_OPTS="-javaagent:${JMX_EXPORTER}/jmx_prometheus_javaagent-0.13.0.jar=9090:${JMX_EXPORTER}/metrics.yml"

ENTRYPOINT [ "/bin/bash" ]