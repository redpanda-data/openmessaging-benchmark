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
            --user ubuntu \
            --inventory `which terraform-inventory` \
        deploy.yaml

Note: you might encounter the following error:

```
[WARNING]:  * Failed to parse /opt/homebrew/bin/terraform-inventory with script plugin: Inventory script (/opt/homebrew/bin/terraform-inventory) had an execution error: Error reading tfstate file:
0.12 format error: <nil>; pre-0.12 format error: <nil> (nil error means no content/modules found in the respective format)
[WARNING]:  * Failed to parse /opt/homebrew/bin/terraform-inventory with yaml plugin: 'utf-8' codec can't encode characters in position 0-3: surrogates not allowed
[WARNING]:  * Failed to parse /opt/homebrew/bin/terraform-inventory with ini plugin: /opt/homebrew/bin/terraform-inventory:3: Expected key=value host variable assignment, got: 
[WARNING]: Unable to parse /opt/homebrew/bin/terraform-inventory as an inventory source

```

In that case, we've found this workaround setting the TF_STATE environment variable helps:

```
export TF_STATE=./
```

Then try running the ansible command again.

## Running the benchmark

1. SSH to the client machine. 

		ssh -i ~/.ssh/redpanda_aws ubuntu@$(terraform output --raw client_ssh_host)

2. Change into the benchmark directory 

		cd /opt/benchmark

3. Run a benchmark using a specific driver and workload, for example: 

		 sudo bin/benchmark -d driver-redpanda/redpanda-ack-all-group-linger-10ms.yaml \
			driver-redpanda/deploy/workloads/1-topic-100-partitions-1kb-4-producers-500k-rate.yaml

## Cleanup

Once you are done. Tear down the cluster with the following command: 

	terraform destroy -auto-approve

