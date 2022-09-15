#!/usr/bin/env bash

set -e

. /opt/benchmark/suites/utils.sh

sudo rm -rf footprint.tar.bz2
sudo rm -rf *.json

retry-on-error base sudo $OMB/bin/benchmark -t swarm -d $OMB/driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml  $OMB/driver-redpanda/deploy/certification/workloads/full/load.625k.yaml

sudo tar cjf footprint.tar.bz2 $(ls *json)