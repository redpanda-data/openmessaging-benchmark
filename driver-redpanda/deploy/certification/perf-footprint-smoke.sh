#!/usr/bin/env bash

set -e

. perf-lib.sh

sudo rm -rf footprint.tar.bz2
sudo rm -rf *.json

retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/smoke/load.400k.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/smoke/load.625k.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/smoke/load.900k.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/smoke/load.1250k.yaml

retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml  workloads/release/smoke/load.625k.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  workloads/release/smoke/load.625k.yaml

retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml  workloads/release/smoke/simple.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/smoke/simple.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  workloads/release/smoke/simple.yaml

retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml  workloads/release/smoke/e2e.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/smoke/e2e.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  workloads/release/smoke/e2e.yaml

sudo tar cjf footprint.tar.bz2 $(ls *json)