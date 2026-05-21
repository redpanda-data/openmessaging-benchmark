terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
    random = {
      version = "~> 3.4.3"
    }
    http = {
      version = "~> 3.0"
    }
    local = {
      version = "~> 2.0"
    }
  }
}

provider "google" {
  project = var.project_name
  region  = var.region
}

data "http" "myip" {
  url = "http://ipv4.icanhazip.com"
}

resource "random_uuid" "cluster" {}

locals {
  deployment_id    = random_uuid.cluster.result
  ssh_key_metadata = "${var.ssh_user}:${file(pathexpand(var.public_key_path))}"
}

resource "google_compute_network" "benchmark" {
  name                    = "rp-benchmark-${local.deployment_id}"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "benchmark" {
  name          = "rp-benchmark-subnet-${local.deployment_id}"
  ip_cidr_range = var.benchmark_subnet_cidr
  region        = var.region
  network       = google_compute_network.benchmark.self_link
}

resource "google_compute_firewall" "internal_icmp" {
  name    = "rp-benchmark-internal-icmp-${local.deployment_id}"
  network = google_compute_network.benchmark.name
  allow {
    protocol = "icmp"
  }
  source_ranges = [var.benchmark_subnet_cidr]
  target_tags   = ["rp-cluster"]
}

resource "google_compute_firewall" "internal_tcp" {
  name    = "rp-benchmark-internal-tcp-${local.deployment_id}"
  network = google_compute_network.benchmark.name
  allow {
    protocol = "tcp"
    ports    = ["0-65535"]
  }
  source_ranges = [var.benchmark_subnet_cidr]
  target_tags   = ["rp-cluster"]
}

resource "google_compute_firewall" "deployer_access" {
  name    = "rp-benchmark-deployer-${local.deployment_id}"
  network = google_compute_network.benchmark.name
  allow {
    protocol = "tcp"
    ports    = ["0-65535"]
  }
  source_ranges = ["${chomp(data.http.myip.response_body)}/32"]
  target_tags   = ["rp-cluster"]
}

resource "google_compute_firewall" "monitoring" {
  name    = "rp-benchmark-monitoring-${local.deployment_id}"
  network = google_compute_network.benchmark.name
  allow {
    protocol = "tcp"
    ports    = ["9090", "3000"]
  }
  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["rp-cluster"]
}

resource "google_compute_network_peering" "benchmark_to_byoc" {
  count        = var.byoc_vpc_name != null ? 1 : 0
  name         = "rp-benchmark-to-byoc-${local.deployment_id}"
  network      = google_compute_network.benchmark.self_link
  peer_network = "https://www.googleapis.com/compute/v1/projects/${coalesce(var.gcp_project, var.project_name)}/global/networks/${var.byoc_vpc_name}"
}

resource "google_compute_network_peering" "byoc_to_benchmark" {
  count        = var.byoc_vpc_name != null ? 1 : 0
  name         = "rp-byoc-to-benchmark-${local.deployment_id}"
  network      = "https://www.googleapis.com/compute/v1/projects/${coalesce(var.gcp_project, var.project_name)}/global/networks/${var.byoc_vpc_name}"
  peer_network = google_compute_network.benchmark.self_link
  depends_on   = [google_compute_network_peering.benchmark_to_byoc]
}

# GCP does not give visibility or control over which failure domain a resource is placed into
# (https://issuetracker.google.com/issues/256993209). Separate failure domains are used to
# guarantee separate racks. GCP caps availability_domain_count at 8.
resource "google_compute_resource_policy" "redpanda" {
  name   = "rp-placement-${local.deployment_id}"
  region = var.region
  group_placement_policy {
    availability_domain_count = var.ha ? min(8, max(3, var.num_instances["redpanda"])) : 1
  }
  count = var.ha ? 1 : 0
}

resource "google_compute_instance" "redpanda" {
  count             = var.num_instances["redpanda"]
  name              = "rp-node-${count.index}-${local.deployment_id}"
  tags              = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  zone              = var.availability_zone[count.index % length(var.availability_zone)]
  machine_type      = var.instance_types["redpanda"]
  resource_policies = (var.ha && var.num_instances["redpanda"] <= 8) ? [google_compute_resource_policy.redpanda[0].id] : null

  metadata = {
    ssh-keys = local.ssh_key_metadata
  }

  boot_disk {
    initialize_params {
      image = var.image
    }
  }

  dynamic "scratch_disk" {
    for_each = range(var.disks)
    content {
      interface = "NVME"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.benchmark.self_link
    access_config {}
  }

  labels = tomap(var.labels)
}

resource "google_compute_instance" "monitor" {
  count        = var.num_instances["prometheus"]
  name         = "rp-monitor-${count.index}-${local.deployment_id}"
  tags         = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  machine_type = var.instance_types["prometheus"]
  zone         = var.availability_zone[0]

  metadata = {
    ssh-keys = local.ssh_key_metadata
  }

  boot_disk {
    initialize_params {
      image = var.image
    }
  }

  scratch_disk {
    interface = "NVME"
  }

  network_interface {
    subnetwork = google_compute_subnetwork.benchmark.self_link
    access_config {}
  }

  labels = tomap(var.labels)
}

resource "google_compute_instance" "client" {
  count        = var.num_instances["client"]
  name         = "rp-client-${count.index}-${local.deployment_id}"
  tags         = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  machine_type = var.instance_types["client"]
  zone         = var.availability_zone[count.index % length(var.availability_zone)]

  metadata = {
    ssh-keys = local.ssh_key_metadata
  }

  boot_disk {
    initialize_params {
      image = var.image
    }
  }

  dynamic "scratch_disk" {
    for_each = range(var.client_disks)
    content {
      interface = "NVME"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.benchmark.self_link
    access_config {}
  }

  labels = tomap(var.labels)
}

resource "google_compute_instance_group" "redpanda" {
  count = length(var.availability_zone)
  name  = "redpanda-group-${local.deployment_id}-${count.index}"
  zone  = var.availability_zone[count.index]
  instances = tolist(concat(
    [for i in google_compute_instance.redpanda[*] : i.self_link if i.zone == var.availability_zone[count.index]],
    [for i in google_compute_instance.monitor[*] : i.self_link if i.zone == var.availability_zone[count.index]],
    [for i in google_compute_instance.client[*] : i.self_link if i.zone == var.availability_zone[count.index]]
  ))
}

resource "local_file" "hosts_ini" {
  content = templatefile("${path.module}/../hosts_ini.tpl", {
    redpanda_public_ips         = google_compute_instance.redpanda[*].network_interface.0.access_config.0.nat_ip
    redpanda_private_ips        = google_compute_instance.redpanda[*].network_interface.0.network_ip
    clients_public_ips          = google_compute_instance.client[*].network_interface.0.access_config.0.nat_ip
    clients_private_ips         = google_compute_instance.client[*].network_interface.0.network_ip
    control_public_ips          = google_compute_instance.client[*].network_interface.0.access_config.0.nat_ip
    control_private_ips         = google_compute_instance.client[*].network_interface.0.network_ip
    prometheus_host_public_ips  = google_compute_instance.monitor[*].network_interface.0.access_config.0.nat_ip
    prometheus_host_private_ips = google_compute_instance.monitor[*].network_interface.0.network_ip
    ssh_user                    = var.ssh_user
    instance_type               = var.instance_types["redpanda"]
  })
  filename = "${path.module}/../hosts.ini"
}

output "ip" {
  value = google_compute_instance.redpanda[*].network_interface.0.access_config.0.nat_ip
}

output "private_ips" {
  value = google_compute_instance.redpanda[*].network_interface.0.network_ip
}

output "client_ssh_host" {
  value = google_compute_instance.client[0].network_interface.0.access_config.0.nat_ip
}

output "ssh_user" {
  value = var.ssh_user
}

output "public_key_path" {
  value = var.public_key_path
}

output "benchmark_network_name" {
  value = google_compute_network.benchmark.name
}

variable "project_name" {
  description = "The GCP project ID."
}

variable "region" {
  default = "us-central1"
}

variable "availability_zone" {
  description = "Zones where the cluster will be deployed, e.g. [\"us-central1-a\", \"us-central1-b\"]"
  default     = ["us-central1-a"]
  type        = list(string)
}

variable "benchmark_subnet_cidr" {
  description = "CIDR block for the benchmark subnet"
  default     = "10.0.0.0/24"
}

variable "ha" {
  description = "Whether to use placement groups to create an HA topology."
  type        = bool
  default     = false
}

variable "disks" {
  description = "The number of local NVMe disks on each Redpanda node."
  type        = number
  default     = 1
}

variable "client_disks" {
  description = "The number of local NVMe disks on each client node."
  type        = number
  default     = 2
}

variable "image" {
  description = "The GCP OS image for all instances. See https://cloud.google.com/compute/docs/images#os-compute-support"
  default     = "ubuntu-os-cloud/ubuntu-2204-lts"
}

variable "instance_types" {
  description = "Machine type for each node role. Must support local NVMe SSDs (e.g. n2d, c2, c3 series)."
  type        = map(string)
  default = {
    "redpanda"   = "n2d-standard-8"
    "client"     = "n2d-standard-16"
    "prometheus" = "n2d-standard-4"
  }
}

variable "num_instances" {
  description = "Number of instances per node role."
  type        = map(number)
  default = {
    "redpanda"   = 3
    "client"     = 4
    "prometheus" = 1
  }
}

variable "public_key_path" {
  description = "Path to the SSH public key file."
}

variable "ssh_user" {
  description = "SSH username."
  default     = "ubuntu"
}

variable "labels" {
  description = "GCP labels to apply to all instances."
  default = {
    "purpose"      = "redpanda-cluster"
    "created-with" = "terraform"
  }
}

variable "byoc_vpc_name" {
  description = "Name of the Redpanda BYOC VPC to peer with. Set to null to skip peering. Note: GCP peering automatically exchanges subnet routes — no CIDR needed unlike AWS."
  default     = null
  type        = string
}

variable "gcp_project" {
  description = "GCP project ID where the BYOC VPC lives. Defaults to project_name if null."
  default     = null
  type        = string
}
