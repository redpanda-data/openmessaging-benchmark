# Redpanda Benchmarks

## Requirements

- Terraform

- Ansible

- Python 3 set as default.


## Setup

1. In the top level directory run `mvn clean install -Dlicense.skip=true`. This will build the benchmark client needed during deployment.

2. Create an ssh key for the benchmark using the following: `ssh-keygen -f ~/.ssh/redpanda_aws`. Set the password to blank.

3. In the `driver-redpanda/deploy` directory.  Run the following: 

        terraform init
        terraform apply -auto-approve

4. To setup the deployed nodes. Run:

        ansible-playbook deploy.yaml
        ansible-playbook redpanda.install.yaml --extra-vars "redpanda_version=22.2.1~rc1-1"
        ansible-playbook redpanda.configure.yaml
        ansible-playbook redpanda.start.yaml

## Running the benchmark

1. SSH to the client machine. 

        ssh -i ~/.ssh/redpanda_aws ubuntu@$(terraform output --raw client_ssh_host)

2. Change into the benchmark directory 

        cd /opt/benchmark

3. Run a benchmark using a specific driver and workload, for example: 

        sudo bin/benchmark -d driver-redpanda/redpanda-ack-all-group-linger-10ms.yaml \
                workloads/blog/1-topic-100-partitions-1kb-4-producers-500k-rate.yaml

## Test another redpanda version

        ansible-playbook redpanda.stop.yaml
        ansible-playbook redpanda.uninstall.yaml
        ansible-playbook redpanda.install.yaml --extra-vars "redpanda_version=22.1.5~rc1-1"
        ansible-playbook redpanda.configure.yaml
        ansible-playbook redpanda.start.yaml

## Cleanup

Once you are done. Tear down the cluster with the following command: 

	terraform destroy -auto-approve

