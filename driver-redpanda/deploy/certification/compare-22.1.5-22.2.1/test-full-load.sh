#!/usr/bin/env bash

set -e

if [ "$1" = "" ]; then
    echo "Must provide owner ./test-load user-name"
    exit 1
fi

pushd $(dirname $0)
setup=$(pwd)
cd ..

## suite-full-load-all

for i in 1; do
    deployment=$(date +%s)
    echo "$(date) terraforming" >> log
    terraform apply -auto-approve -var="username=$1"
    sleep 1m

    echo "$(date) deploying" >> log
    ansible-playbook deploy.yaml

    for v in "22.1.5" "22.2.1"; do
        echo "$(date) installing $v" >> log
        ansible-playbook redpanda.install.yaml --extra-vars "redpanda_version=$v~rc1-1"
        ansible-playbook redpanda.pre.configure.base.yaml
        echo "$(date) testing suite-full-load-all" >> log
        ansible-playbook test.yaml --extra-vars "suite=suite-full-load-all"
        echo "$(date) tested suite-full-load-all" >> log
        results="$setup/results/full/$deployment/$v"
        ./fetch-n-report.sh $results $v
        if [ ! -d "$results" ]; then
            echo "$(date) fetch-n-report.sh failed to build $results" >> log
            exit 1
        fi
        echo "$(date) stopping redpanda" >> log
        ansible-playbook redpanda.stop.yaml
        echo "$(date) uninstalling redpanda" >> log
        ansible-playbook redpanda.uninstall.yaml
    done
    echo "$(date) destroying" >> log
    terraform destroy -auto-approve -var="username=$1"
done

## suite-full-load-625k x2

for i in 1 2; do
    deployment=$(date +%s)
    echo "$(date) terraforming" >> log
    terraform apply -auto-approve -var="username=$1"
    sleep 1m
    
    echo "$(date) deploying" >> log
    ansible-playbook deploy.yaml
    
    for v in "22.1.5" "22.2.1"; do
        echo "$(date) installing $v" >> log
        ansible-playbook redpanda.install.yaml --extra-vars "redpanda_version=$v~rc1-1"
        ansible-playbook redpanda.pre.configure.base.yaml
        echo "$(date) testing suite-full-load-625k" >> log
        ansible-playbook test.yaml --extra-vars "test=suite-full-load-625k"
        echo "$(date) tested suite-full-load-625k" >> log
        results="$setup/results/full/$deployment/$v"
        ./fetch-n-report.sh $results $v
        if [ ! -d "$results" ]; then
            echo "$(date) fetch-n-report.sh failed to build $results" >> log
            exit 1
        fi
        echo "$(date) stopping redpanda" >> log
        ansible-playbook redpanda.stop.yaml
        echo "$(date) uninstalling redpanda" >> log
        ansible-playbook redpanda.uninstall.yaml
    done
    echo "$(date) destroying" >> log
    terraform destroy -auto-approve -var="username=$1"
done

popd