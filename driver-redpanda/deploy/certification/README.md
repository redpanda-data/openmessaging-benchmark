# Redpanda Benchmarks

## Requirements

- Terraform

        https://learn.hashicorp.com/tutorials/terraform/install-cli

- Ansible

        sudo apt update
        sudo apt install software-properties-common -y
        sudo add-apt-repository --yes --update ppa:ansible/ansible
        sudo apt install ansible -y
        ansible-galaxy install mrlesmithjr.mdadm

- Python 3 set as default.

        sudo apt install python3-pip
        pip3 install sh
        sudo apt install gnuplot

- Java

        sudo apt install maven

- bzip2

        sudo apt install bzip2

## Setup

1. In the top level directory run `mvn clean install -Dlicense.skip=true`. This will build the benchmark client needed during deployment.

2. Create an ssh key for the benchmark using the following: `ssh-keygen -f ~/.ssh/redpanda_aws`. Set the password to blank.

3. In the `driver-redpanda/deploy/certification` directory.  Run the following: 

        terraform init

## Running the benchmark

Use `./test-load-10h.sh`, `test-simple-8h.sh` or `test-smoke-4h.sh` script to run a test suite

### Manual test execution

Inspect the benchmark scripts to learn how to manually run tests and how to test different versions

## Cleanup

Cleanup is done automaticly unless a tests failed. In this case you need to destroy the vms manually:

	terraform destroy -auto-approve -var="username=owner"
