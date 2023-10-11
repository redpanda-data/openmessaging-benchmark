# Redpanda Benchmarks

## Requirements

- Terraform

- Ansible and modules:
    - geerlingguy.node_exporter
    - cloudalchemy.prometheus
    - cloudalchemy.grafana

- Python 3 set as default.

- The [terraform inventory plugin](https://github.com/adammck/terraform-inventory)

## Setup

1. Normally the framework launches both Redpanda cluster and OMB workers (and prometheus) that work together.
   For connectors benchmarking purposes, Cloudv2 Redpanda (with connectors) needs to be used instead.
   You may want to pre-configure the setup with a proper Redpanda cluster URL that will be used. To do it set the
   `bootstrapServers` property in `driver-redpanda/deploy/deploy.yaml` file, e.g.:

```yaml
- name: Store server lists
  hosts: all
  connection: ssh
  tags: client
  tasks:
    - set_fact:
        bootstrapServers: "seed-5500114e.cck1ms1f50m6pfhgech0.staging.byoc.int.cloud.redpanda.com:9092"
```

2. Specify region and az to match your Redpanda region in the `driver-redpanda/deploy/terraform.tfvars` file.

```
public_key_path = "~/.ssh/redpanda_aws.pub"
region          = "us-east-1"
az		        = "us-east-1b"
profile         = "default"

instance_types = {
  "redpanda"      = "i3en.6xlarge"
  "client"        = "m5.large"
  "prometheus"    = "c5.2xlarge"
}

num_instances = {
  "client"     = 2
  "redpanda"   = 0
  "prometheus" = 0
}

```

3. In the top level directory run `mvn clean install -Dlicense.skip=true`. This will build the benchmark client needed during deployment.

4. Create an ssh key for the benchmark using the following: `ssh-keygen -f ~/.ssh/redpanda_aws`. Set the password to blank.

5. In the `driver-redpanda/deploy` directory.  Run the following: 
```
       terraform init
        terraform apply -auto-approve
```

6. To setup the deployed nodes. Run:

```
        ansible-playbook deploy.yaml
```
NOTE: You might experience an issue with child forks crashing.  In that case,
try running this `export OBJC_DISABLE_INITIALIZE_FORK_SAFETY=YES` and deploying again.

## Running the benchmark

1. SSH to the client machine. 

        ssh -i ~/.ssh/redpanda_aws ubuntu@$(terraform output --raw client_ssh_host)

2. Change into the benchmark directory 

        cd /opt/benchmark

3. If you did not configure the brokers URLs before, do it now in the driver config file that will be used, in
   `driver-redpanda` directory.

4. Run a benchmark using a specific driver and workload, for example: 

        sudo bin/benchmark -d driver-redpanda/redpanda-ack-all-group-linger-1ms.yaml \
            workloads/simple-workload.yaml

## Generating charts

Once you have ran a benchmark, a json file will be generated in the data directory. You can use `bin/generate_charts.py` to generate a a visual representation of this data.

First install the python script's prerequisites (Note Python 3.10 or later is not currently supported):

```bash
python3 -m pip install numpy jinja2 pygal
```

The script has a few flags to say where the benchmark output file is, where the output will be stored, etc. Run the script with the help flag for more details (from the project's root directory):

```bash
./bin/generate_charts.py -h
```

You will need to create an output folder (named `output` here):

```bash
mkdir output
```

Then run the script. The following example looks for benchmark files in `bin/data` and sends output to the folder created above:

```bash
./bin/generate_charts.py --results ./bin/data --output ./output
```

The output of this command is web page with charts for throughput, publish latency, end-to-end latency, publish rate, and consume rate (open in your favorite browser).

## Cleanup

Once you are done. Tear down the cluster with the following command: 

	terraform destroy -auto-approve

