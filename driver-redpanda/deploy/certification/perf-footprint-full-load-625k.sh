#!/usr/bin/env bash

set -e

. perf-lib.sh

sudo rm -rf footprint.tar.bz2
sudo rm -rf *.json

retry-on-error sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  workloads/release/full/load.625k.yaml

sudo tar cjf footprint.tar.bz2 $(ls *json)