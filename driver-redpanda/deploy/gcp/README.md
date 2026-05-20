# Redpanda Benchmark — GCP Deployment

Deploy Redpanda brokers and OMB benchmark clients on GCP using Terraform + Ansible.

## Prerequisites

1. [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.0
2. [gcloud CLI](https://cloud.google.com/sdk/docs/install) authenticated:
   ```bash
   gcloud auth application-default login
   ```
3. Compute API enabled in your project:
   ```bash
   gcloud services enable compute.googleapis.com --project=<your-project>
   ```
4. An SSH key pair:
   ```bash
   ssh-keygen -t rsa -f ~/.ssh/redpanda_gcp
   ```

## Usage

```bash
cd driver-redpanda/deploy/gcp

cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars — set project_name, ssh_user, public_key_path at minimum

terraform init
terraform apply
```

Run Ansible from the `deploy/` directory (one level up — `hosts.ini` is written there by Terraform).
`ansible.cfg` hardcodes `~/.ssh/redpanda_aws`, so pass your GCP key explicitly with `--private-key`.

**Standalone Redpanda cluster:**
```bash
cd ..
ansible-playbook deploy.yaml -i hosts.ini --private-key ~/.ssh/redpanda_gcp
```

**Against an existing BYOC/Dedicated cluster** (set `num_instances.redpanda = 0` in tfvars):
```bash
cd ..
ansible-playbook deploy.yaml -i hosts.ini \
  --private-key ~/.ssh/redpanda_gcp \
  --ask-become-pass \
  -e "tls_enabled=true sasl_enabled=true sasl_username=<user> sasl_password=<password>" \
  -e "bootstrapServers=<seed-host>:9092"
```

> `--ask-become-pass` is needed when running Ansible from a local Mac (the playbook has tasks requiring sudo). Omit it when running from a cloud VM as root.

## VPC Peering (optional)

Set `byoc_vpc_name` in `terraform.tfvars` to peer the benchmark network with an existing
BYOC network. `terraform apply` creates peering in both directions automatically.

### IAM requirement

Your credentials need `compute.networks.addPeering` on both the benchmark project and the
BYOC project. Grant it with:

```bash
gcloud projects add-iam-policy-binding <byoc-project> \
  --member="user:<your-email>" \
  --role="roles/compute.networkAdmin"
```

### Fallback: manual peering

If you cannot obtain IAM access to the BYOC project (e.g. it is managed by Redpanda),
create the return peering manually after `terraform apply`:

```bash
BENCHMARK_NETWORK=$(terraform output -raw benchmark_network_name)
BENCHMARK_PROJECT=<your-benchmark-project>

gcloud compute networks peerings create rp-byoc-to-benchmark \
  --network=<your-byoc-network> \
  --peer-project=$BENCHMARK_PROJECT \
  --peer-network=$BENCHMARK_NETWORK \
  --project=<byoc-project>
```
