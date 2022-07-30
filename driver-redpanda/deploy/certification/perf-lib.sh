#!/usr/bin/env bash

set -e

function worker_stop() {
    ssh -i ~/.ssh/redpanda_aws $1 sudo systemctl stop benchmark-worker
}

function worker_start() {
    ssh -i ~/.ssh/redpanda_aws $1 sudo systemctl start benchmark-worker
}

function redpanda_stop () {
    ssh -i ~/.ssh/redpanda_aws $1 sudo systemctl stop redpanda
}
function redpanda_wipe () {
    ssh -i ~/.ssh/redpanda_aws $1 sudo rm -rf /mnt/vectorized/redpanda/data
    ssh -i ~/.ssh/redpanda_aws $1 sudo rm -rf /mnt/vectorized/redpanda/coredump
}
function redpanda_start () {
    ssh -i ~/.ssh/redpanda_aws $1 sudo systemctl start redpanda
}

export -f worker_stop
export -f worker_start
export -f redpanda_stop
export -f redpanda_wipe
export -f redpanda_start

function reset_all () {
    sudo bash -c 'echo "$(date) Restarting workload" >> log'
    cat /opt/benchmark/client | xargs -L 1 bash -c 'worker_stop "$@"' _
    sudo bash -c 'echo "$(date) Restarting redpanda" >> log'
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c 'redpanda_stop "$@"' _
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c 'redpanda_wipe "$@"' _
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c 'redpanda_start "$@"' _
    sleep 10s
    sudo bash -c 'echo "$(date) Redpanda is restarted" >> log'
    cat /opt/benchmark/client | xargs -L 1 bash -c 'worker_start "$@"' _
    sudo bash -c 'echo "$(date) Workload is restarted" >> log'
}

function retry-on-error () {
    args="$*"
    sudo bash -c "echo $(date) retry-on-error $args >> log"
    reset_all

    attempt=0
    while (( attempt < 5)); do
        sudo bash -c "echo $(date) attempting $args >> log"
        stated_s=$(date +%s)
        eval $@ || true
        duration_s=$(( $(date +%s) - stated_s ))
        if (( duration_s > 60 )); then
            return 0
        fi
        sleep 5s
        attempt=$(( $attempt + 1))
    done
    exit 1
}