#!/usr/bin/env bash

set -e

. /opt/benchmark/suites/utils.sh

sudo rm -rf footprint.tar.bz2
sudo rm -rf *.json

retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/load.400k.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/load.625k.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/load.900k.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/load.1250k.yaml

retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/load.625k.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/load.625k.yaml

retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/simple.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/simple.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/simple.yaml

retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/e2e.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/e2e.yaml
retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml $OMB/driver-redpanda/deploy/certification/workloads/smoke/e2e.yaml

sudo tar cjf footprint.tar.bz2 $(ls *json)