# Redpanda Benchmarks

## Requirements

- Terraform

- Ansible

- Python 3 set as default.

- The [terraform inventory plugin](https://github.com/adammck/terraform-inventory)

## Setup

1. In the top level directory run `mvn clean install`. This will build the benchmark client needed during deployment.

2. Create an ssh key for the benchmark using the following: `ssh-keygen -f ~/.ssh/redpanda_aws`. Set the password to blank.

3. In the `driver-redpanda/deploy` directory.  Run the following: 

        terraform init
        terraform apply -auto-approve

4. To setup the deployed nodes. Run:

        ansible-playbook \
            --user fedora \
            --inventory `which terraform-inventory` \
        deploy.yaml

## Running the benchmark

1. SSH to the client machine. 
		ssh -i ~/.ssh/redpanda_aws fedora@$(terraform output client_ssh_host)

2. Change into the benchmark directory 
		cd /opt/benchmark

3. Run a benchmark using a specific driver and workload, for example: 
		 sudo bin/benchmark -d driver-redpanda/redpanda-ack-all-group-linger-10ms.yaml \
			workloads/blog/1-topic-100-partitions-1kb-4-producers-500k-rate.yaml

## Cleanup

Once you are done. Tear down the cluster with the following command: 
	terraform destroy -force

