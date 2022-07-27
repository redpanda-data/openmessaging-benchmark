#!/usr/bin/env bash

set -e

function retry-on-error () {
    attempt=0
    while (( attempt < 5)); do
        stated_s=$(date +%s)
        eval $@
        duration_s=$(( $(date +%s) - stated_s ))
        if (( duration_s > 60 )); then
            return 0
        fi
        sleep 1s
        attempt=$(( $attempt + 1))
    done
    exit 1
}

retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/full/load.400k.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml  workloads/release/full/load.400k.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  workloads/release/full/load.400k.yaml

retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml  workloads/release/full/simple.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-1-linger-1ms-eod-false.yaml  workloads/release/full/e2e.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/full/simple.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-false.yaml  workloads/release/full/e2e.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  workloads/release/full/simple.yaml
retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  workloads/release/full/e2e.yaml

sudo tar cjf footprint.tar.bz2 $(ls *json)