#!/usr/bin/env bash

set -e

if [ "$1" == "" ]; then
  echo "Must pass a label e.g. ./fetch-n-report.sh 22.2.1"
  exit 1
fi

mkdir -p results
mkdir results/$1
ansible-playbook fetch.yaml
if [ ! -d fetched ]; then
  echo "$(date) failed to fetch fetched" >> log
  exit 1
fi
find fetched | grep foot | xargs -I{} mv {} results/$1/footprint.tar.bz2
rm -rf fetched
pushd results/$1
tar xjf footprint.tar.bz2
rm footprint.tar.bz2
popd
python3 ../../../bin/make_charts.py $(pwd)/results/$1 $1