#!/usr/bin/env bash

set -e

export OMB=/opt/benchmark

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
    ssh -i ~/.ssh/redpanda_aws $1 sudo rm /etc/redpanda/redpanda.yaml
    ssh -i ~/.ssh/redpanda_aws $1 sudo cp /etc/redpanda/redpanda.backup.yaml /etc/redpanda/redpanda.yaml
    ssh -i ~/.ssh/redpanda_aws $1 sudo chown redpanda:redpanda /etc/redpanda/redpanda.yaml
}
function redpanda_configure () {
    ssh -i ~/.ssh/redpanda_aws $2 sudo /home/ubuntu/configure.$1.sh
}
function redpanda_start () {
    ssh -i ~/.ssh/redpanda_aws $1 sudo systemctl start redpanda
}
function redpanda_fetch () {
    ssh -i ~/.ssh/redpanda_aws $2 sudo journalctl --rotate
    ssh -i ~/.ssh/redpanda_aws $2 sudo bash -c "journalctl > /home/ubuntu/$2.log"
    ssh -i ~/.ssh/redpanda_aws $2 sudo journalctl --vacuum-time=1s
    ssh -i ~/.ssh/redpanda_aws $2 sudo sleep 2s
    ssh -i ~/.ssh/redpanda_aws $2 sudo journalctl --vacuum-time=1w
    ssh -i ~/.ssh/redpanda_aws $2 sudo chown ubuntu:ubuntu /home/ubuntu/$2.log
    sudo mkdir -p $1
    sudo scp -i ~/.ssh/redpanda_aws ubuntu@$2:/home/ubuntu/$2.log $1/$2.log
}

export -f worker_stop
export -f worker_start
export -f redpanda_stop
export -f redpanda_wipe
export -f redpanda_configure
export -f redpanda_start
export -f redpanda_fetch

function reset () {
    sudo bash -c 'echo "$(date) Restarting workload" >> log'
    cat /opt/benchmark/client | xargs -L 1 bash -c 'worker_stop "$@"' _
    sudo bash -c 'echo "$(date) Restarting redpanda" >> log'
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c 'redpanda_stop "$@"' _
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c 'redpanda_wipe "$@"' _
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c "redpanda_configure $1 \"\$@\"" _
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c 'redpanda_start "$@"' _
    sleep 10s
    sudo bash -c 'echo "$(date) Redpanda is restarted" >> log'
    cat /opt/benchmark/client | xargs -L 1 bash -c 'worker_start "$@"' _
    sudo bash -c 'echo "$(date) Workload is restarted" >> log'
}

function fetch-logs () {
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c 'redpanda_stop "$@"' _
    cat /opt/benchmark/redpanda | xargs -L 1 bash -c "redpanda_fetch $1 \"\$@\"" _
}

function retry-on-error () {
    args="$*"
    sudo bash -c "echo $(date) retry-on-error $args >> log"
    reset $1
    shift
    args="$*"

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