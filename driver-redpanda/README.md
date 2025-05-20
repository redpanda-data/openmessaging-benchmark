# Redpanda Benchmarks

## Requirements

- Terraform

- Ansible and modules:
    - geerlingguy.node_exporter
    - prometheus.prometheus
    - grafana.grafana

- Python 3 set as default.

- The [terraform inventory plugin](https://github.com/adammck/terraform-inventory)

- aws-cli
- gcloud

## Setup

1.  In the top level directory run `mvn clean package`. This will build the benchmark client needed during deployment.

2.  Create an environment variable for the cloud provider you're going to deploy on:

```bash
        export REDPANDA_CLOUD_PROVIDER=<aws | gcp | azure>
````

3.  Create an ssh key for the benchmark by running the following:

 ```bash
        ssh-keygen -f ~/.ssh/redpanda_${REDPANDA_CLOUD_PROVIDER} 
```
Set the password to blank when prompted.

4.  Copy & edit `terraform.tfvars` for your specific needs around instance types & quantities:

```bash
        cp ${REDPANDA_CLOUD_PROVIDER}/terraform.tfvars.example ${REDPANDA_CLOUD_PROVIDER}/terraform.tfvars
```

Hint:  if you're planning to benchmark against an existing Redpanda cluster, set the `num_instances` of `redpanda` to 0 in `terraform.tfvars`


5.  In the `driver-redpanda/deploy` directory run terraform apply. 

### AWS

```bash
        terraform -chdir=${REDPANDA_CLOUD_PROVIDER} init
        terraform -chdir=${REDPANDA_CLOUD_PROVIDER} plan
        aws sts get-caller-identity || aws sso login
        terraform -chdir=${REDPANDA_CLOUD_PROVIDER} apply --auto-approve
```

### GCP

```bash
        terraform -chdir=${REDPANDA_CLOUD_PROVIDER} init
        terraform -chdir=${REDPANDA_CLOUD_PROVIDER} plan
        gcloud auth print-access-token || gcloud auth login
        terraform -chdir=${REDPANDA_CLOUD_PROVIDER} apply --auto-approve
```

### Azure

_coming soon_

6.  To setup the deployed nodes, run the ansible playbook.  If running locally include `--ask-become-pass` and supply your admin password when prompted.   If running on a cloud VM run the command as `sudo` instead.

```bash
        if [ "$(uname)" = "Darwin" ]; then export OBJC_DISABLE_INITIALIZE_FORK_SAFETY=YES; fi
        if [ "$(uname)" = "Darwin" ]; then brew install gnu-tar; fi # https://github.com/prometheus-community/ansible/issues/186
        ansible-galaxy install -r requirements.yaml

        ansible-playbook --inventory ${REDPANDA_CLOUD_PROVIDER}/hosts.ini --ask-become-pass deploy.yaml
```

To instead use an existing Redpanda BYOC/Dedicated cluster, you'll need to add several things to the command:

An extra variable to enable TLS (required by Redpanda cloud clusters), and then a SASL username & password for a user already created on your cluster.
```bash
        -e "tls_enabled=true sasl_enabled=true sasl_username=<YOUR SASL USER> sasl_password=<YOUR SASL PASSWORD>" \
```

An extra variable to identify the bootstrap server address (e.g. `http://seed-abc123.redpanda.com:9092`)
```bash
        -e bootstrapServers="<YOUR BYOC KAFKA API ENDPOINT>" \
```

So the complete ansible-playbook command would look like this:
```bash
        ansible-playbook --inventory  ${REDPANDA_CLOUD_PROVIDER}/hosts.ini \
        --ask-become-pass \
        -e "tls_enabled=true sasl_enabled=true sasl_username=<YOUR SASL USER> sasl_password=<YOUR SASL PASSWORD>" \
        -e bootstrapServers="<YOUR BYOC KAFKA API ENDPOINT>" \
        deploy.yaml
```

---


## Running the benchmark

1. SSH to the client machine. 

        ssh -i ~/.ssh/redpanda_aws ubuntu@$(terraform output --raw client_ssh_host)

2. Change into the benchmark directory 

        cd /opt/benchmark

3. Run a benchmark using a specific driver and workload, for example: 

         bin/benchmark -d driver-redpanda/redpanda-ack-all-group-linger-10ms.yaml \
            driver-redpanda/deploy/workloads/1-topic-100-partitions-1kb-4-producers-500k-rate.yaml

While the benchmark is running, you can observe the cluster performance in Grafana, by navigating to:
`http://<prometheus.ip.address:3000`

The default username & password is admin/admin.

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


```bash
	terraform -chdir=${REDPANDA_CLOUD_PROVIDER} destroy --auto-approve
```
