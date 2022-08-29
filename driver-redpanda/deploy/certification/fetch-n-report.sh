#!/usr/bin/env bash

set -e

if [ "$1" == "" ]; then
  echo "Must pass a destination and a title e.g. ./fetch-n-report.sh results/22.2.1 22.2.1"
  exit 1
fi

if [ "$2" == "" ]; then
  echo "Must pass a destination and a title e.g. ./fetch-n-report.sh results/22.2.1 22.2.1"
  exit 1
fi

mkdir -p $1
ansible-playbook fetch.yaml
if [ ! -d fetched ]; then
  echo "$(date) failed to fetch fetched" >> log
  exit 1
fi
find fetched | grep foot | xargs -I{} mv {} $1/footprint.tar.bz2
rm -rf fetched
pushd $1
tar xjf footprint.tar.bz2
rm footprint.tar.bz2
popd
python3 ../../../bin/gnuplot_charts.py $1 $2