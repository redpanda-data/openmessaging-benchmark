terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 4.0"
    }
  }
}


provider "google" {
  #project     = var.project_name
  region      = var.region
}

provider "random" {
}

variable "private_key_path" {
  description = "Path to the private SSH key used for Ansible"
  type        = string
}

variable "public_key_path" {
  description = <<DESCRIPTION
Path to the SSH public key to be used for authentication.
Ensure this keypair is added to your local SSH agent so provisioners can
connect.

Example: ~/.ssh/redpanda_gcp.pub
DESCRIPTION
  type = string
}

variable "subnet_cidr_range" {
  type        = string
  description = "CIDR range for Redpanda subnet"
  default     = "10.10.0.0/16"
}

variable "instance_types" {
  type = map(string)
  default = {
    redpanda = "n2-standard-8"
    monitor  = "n2-standard-4"
    client   = "n2-standard-16"
  }
}

variable "num_instances" {
  description = "Map of instance counts by role"
  type        = map(number)
  default     = {
    redpanda = 3
    client   = 4
    monitor  = 1
  }
}

variable "redpanda_disk_size_gb" {
  type        = number
  description = "Size (in GB) of the local NVMe disk for Redpanda.  Must be in 375GB increments."
  default     = 375
}

data "http" "my_ip" {
  url = "https://ipv4.icanhazip.com"
}


resource "random_id" "hash" {
  byte_length = 8
}

resource "random_uuid" "cluster" {}

locals {
  uuid          = random_uuid.cluster.result
  deployment_id = random_uuid.cluster.result
}


# Create a new VPC
resource "google_compute_network" "redpanda_vpc" {
  name                    = "redpanda-vpc-${random_id.hash.hex}"
  auto_create_subnetworks = false
}


# Create subnets
resource "google_compute_subnetwork" "redpanda_subnet" {
  name          = "redpanda-subnet-${random_id.hash.hex}"
  ip_cidr_range = var.subnet_cidr_range
  region        = var.region
  network       = google_compute_network.redpanda_vpc.id
}


# Allow traffic on Redpanda, Prometheus, and Grafana ports
resource "google_compute_firewall" "allow_ssh" {
  name    = "allow-external-access"
  network = google_compute_network.redpanda_vpc.name

  allow {
    protocol = "tcp"
    ports    = ["22", "3000", "9090"]
  }

  source_ranges = ["${chomp(data.http.my_ip.response_body)}/32"]
}


# Allow traffic on Redpanda, Prometheus, and Grafana ports
resource "google_compute_firewall" "allow_redpanda" {
  name    = "allow-redpanda"
  network = google_compute_network.redpanda_vpc.name

  allow {
    protocol = "tcp"
    ports    = ["9092", "9644", "8080", "8081", "8082", "33145", "3000", "9090"] # Kafka API + Admin API + Prometheus/Grafana
  }

  source_ranges = [var.subnet_cidr_range]
}



resource "google_compute_resource_policy" "redpanda-rp" {
  name   = "redpanda-rp"
  region = var.region
  group_placement_policy {
    availability_domain_count = var.ha ? max(3, var.num_instances["redpanda"]) : 1
  }
  count = var.ha ? 1 : 0
}

resource "google_compute_instance" "redpanda" {
  count             = var.num_instances["redpanda"]
  name              = "rp-node-${count.index}-${local.deployment_id}"
  tags              = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  zone              = "${var.region}-${var.availability_zone[count.index % length(var.availability_zone)]}"
  machine_type      = var.instance_types["redpanda"]
  // GCP does not give you visibility nor control over which failure domain a resource has been placed into
  // (https://issuetracker.google.com/issues/256993209?pli=1). So the only way that we can guarantee that
  // specific nodes are in separate racks is to put them into entirely separate failure domains - basically one
  // broker per failure domain, and we are limited by the number of failure domains (at the moment 8).
  resource_policies = (var.ha && var.num_instances["redpanda"] <= 8) ? [
    google_compute_resource_policy.redpanda-rp[0].id
  ] : null

  metadata = {
    ssh-keys = "${var.ssh_user}:${file(var.public_key_path)}"
  }

  boot_disk {
    initialize_params {
      image = var.image
    }
  }

  dynamic "scratch_disk" {
    for_each = range(floor(var.redpanda_disk_size_gb / 375))
    content {
      // 375 GB local SSD drive.
      interface = "NVME"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.redpanda_subnet.id
    access_config {
    }
  }

  labels = tomap(var.labels)
}

resource "google_compute_instance" "monitor" {
  count        = var.num_instances["monitor"]
  name         = "rp-monitor-${local.deployment_id}"
  tags         = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  machine_type = var.instance_types["monitor"]
  zone         = "${var.region}-${var.availability_zone[0]}"

  metadata = {
    ssh-keys = "${var.ssh_user}:${file(var.public_key_path)}"
  }

  boot_disk {
    initialize_params {
      image = var.image
    }
  }

  scratch_disk {
    // 375 GB local SSD drive.
    interface = "NVME"
  }

  network_interface {
    subnetwork = google_compute_subnetwork.redpanda_subnet.id
    access_config {
    }
  }

  labels = tomap(var.labels)
}

resource "google_compute_instance" "client" {
  count        = var.num_instances["client"]
  name         = "rp-client-${count.index}-${local.deployment_id}"
  tags         = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  machine_type = var.instance_types["client"]
  zone         = "${var.region}-${var.availability_zone[count.index % length(var.availability_zone)]}"

  metadata = {
    ssh-keys = "${var.ssh_user}:${file(var.public_key_path)}"
  }

  boot_disk {
    initialize_params {
      image = var.image
    }
  }

  dynamic "scratch_disk" {
    for_each = range(var.client_disks)
    content {
      // 375 GB local SSD drive.
      interface = "NVME"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.redpanda_subnet.id
    access_config {
    }
  }
  labels = tomap(var.labels)
}

resource "google_compute_route" "internet_access" {
  name              = "redpanda-default-route"
  network           = google_compute_network.redpanda_vpc.name
  dest_range        = "0.0.0.0/0"
  next_hop_gateway  = "default-internet-gateway"
  priority          = 1000
}


resource "google_compute_instance_group" "redpanda" {
  name      = "redpanda-group-${local.deployment_id}"
  count     = length(var.availability_zone)
  zone      = "${var.region}-${var.availability_zone[count.index]}"
  instances = tolist(concat(
    [for i in google_compute_instance.redpanda.* : i.self_link if i.zone == "${var.region}-${var.availability_zone[count.index]}"],
    [for i in google_compute_instance.monitor.* : i.self_link if i.zone == "${var.region}-${var.availability_zone[count.index]}"],
    [for i in google_compute_instance.client.* : i.self_link if i.zone == "${var.region}-${var.availability_zone[count.index]}"]
   )
  )
}

resource "local_file" "hosts_ini" {
  content = templatefile("${path.module}/../hosts_ini.tpl",
    {
      redpanda_public_ips         = google_compute_instance.redpanda[*].network_interface.0.access_config.0.nat_ip
      redpanda_private_ips        = google_compute_instance.redpanda[*].network_interface.0.network_ip
      clients_public_ips          = google_compute_instance.client[*].network_interface.0.access_config.0.nat_ip
      clients_private_ips         = google_compute_instance.client[*].network_interface.0.network_ip
      control_public_ips          = google_compute_instance.client[*].network_interface.0.access_config.0.nat_ip
      control_private_ips         = google_compute_instance.client[*].network_interface.0.network_ip
      prometheus_host_public_ips  = google_compute_instance.monitor[*].network_interface.0.access_config.0.nat_ip
      prometheus_host_private_ips = google_compute_instance.monitor[*].network_interface.0.network_ip
      instance_type               = var.instance_types["redpanda"]
      ssh_user                    = var.ssh_user
      private_key_path            = var.private_key_path 
   }
  )
  filename = "${path.module}/hosts.ini"
}

output "ip" {
  value = google_compute_instance.redpanda[*].network_interface.0.access_config.0.nat_ip
}

output "private_ips" {
  value = google_compute_instance.redpanda[*].network_interface.0.network_ip
}

output "ssh_user" {
  value = var.ssh_user
}

output "public_key_path" {
  value = var.public_key_path
}

output "client_ssh_host" {
  value = "${google_compute_instance.client[0].network_interface.0.access_config.0.nat_ip}"
}

variable "region" {
  default = "us-west2"
}

variable "availability_zone" {
  description = "The zone where the cluster will be deployed [a,b,...]"
  default     = ["a"]
  type        = list(string)
}

variable "instance_group_name" {
  description = "The name of the GCP instance group"
  default     = "redpanda-group"
}

variable "ha" {
  description = "Whether to use placement groups to create an HA topology"
  type        = bool
  default     = false
}

variable "disks" {
  description = "The number of local disks on each machine."
  type        = number
  default     = 1
}

variable "client_disks" {
  description = "The number of local disks on each machine."
  type        = number
  default     = 2
}

variable "image" {
  # See https://cloud.google.com/compute/docs/images#os-compute-support
  # for an updated list.
  default = "ubuntu-os-cloud/ubuntu-2204-lts"
}

variable "ssh_user" {
  description = "The ssh user. Must match the one in the public ssh key's comments."
}

variable "enable_monitoring" {
  default = "yes"
}

variable "labels" {
  description = "passthrough of GCP labels"
  default     = {
    "purpose"      = "redpanda-cluster-via-omb"
    "created-with" = "terraform"
  }
}
