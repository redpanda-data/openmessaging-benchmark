#!/bin/sh
################################################################
# Start a new benchmark run.
################################################################

TEST_NAME="/run/omb/test-$(date '+%Y-%m-%dT%H%M%S').json"
DRIVER="${DRIVER:-/etc/omb/driver.yaml}"
WORKLOAD="${WORKLOAD:-/etc/omb/workload.yaml}"

if [ -z "${WORKERS}" ]; then
  echo "WORKERS environment variable not set!" > /dev/stderr;
  echo "Check your environment and set it to the list of worker urls." > /dev/stderr;
  exit 1;
fi

/opt/benchmark/bin/benchmark \
  --output "${TEST_NAME}" \
  --driver "${DRIVER}" \
  --workers "${WORKERS}" \
  "${WORKLOAD}"
