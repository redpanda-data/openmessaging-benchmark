#!/usr/bin/env bash

set -e

if [ "$1" = "" ]; then
    echo "Must provide owner ./test-load user-name"
    exit 1
fi

pushd $(dirname $0)
setup=$(pwd)
cd ..

echo "$(date) terraforming" >> log
terraform apply -auto-approve -var="username=$1"
sleep 1m

echo "$(date) deploying" >> log
ansible-playbook deploy.yaml

echo "$(date) installing 22.2.1" >> log
ansible-playbook redpanda.install.yaml --extra-vars "redpanda_version=22.2.1~rc1-1"
ansible-playbook redpanda.pre.configure.autobalancing.yaml
ansible-playbook redpanda.pre.configure.base.yaml
# terraform destroy -auto-approve -var="username=$1"