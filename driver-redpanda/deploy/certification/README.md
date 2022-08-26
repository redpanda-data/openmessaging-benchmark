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

3. In the `driver-redpanda/deploy` directory.  Run the following: 

        terraform init
        terraform apply -auto-approve -var="username=your-user-name"

4. To setup the deployed nodes. Run:

        ansible-playbook deploy.yaml
        ansible-playbook redpanda.install.yaml --extra-vars "redpanda_version=22.2.1~rc1-1"
        ansible-playbook redpanda.pre.configure.base.yaml
        ansible-playbook redpanda.configure.base.yaml
        ansible-playbook redpanda.start.yaml

## Running the benchmark

1. SSH to the client machine. 

        ssh -i ~/.ssh/redpanda_aws ubuntu@$(terraform output --raw client_ssh_host)

2. Change into the benchmark directory 

        cd /opt/benchmark

3. Run a benchmark using a specific driver and workload, for example: 

        sudo bin/benchmark -t swarm -d driver-redpanda/redpanda-ack-all-linger-1ms-eod-true.yaml driver-redpanda/deploy/certification/workloads/smoke/load.400k.yaml

## Test another redpanda version

        ansible-playbook redpanda.stop.yaml
        ansible-playbook redpanda.uninstall.yaml
        ansible-playbook redpanda.install.yaml --extra-vars "redpanda_version=22.1.5~rc1-1"
        ansible-playbook redpanda.pre.configure.base.yaml
        ansible-playbook redpanda.configure.base.yaml
        ansible-playbook redpanda.start.yaml

## Cleanup

Once you are done. Tear down the cluster with the following command: 

	terraform destroy -auto-approve -var="username=your-user-name"

