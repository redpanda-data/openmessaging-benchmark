provider "google" {
  project     = var.project_name
  region      = var.region
}

resource "random_uuid" "cluster" {}

locals {
  uuid          = random_uuid.cluster.result
  deployment_id = random_uuid.cluster.result
}

resource "google_compute_resource_policy" "redpanda-rp" {
  name   = "redpanda-rp"
  region = var.region
  group_placement_policy {
    availability_domain_count = var.ha ? max(3, var.nodes) : 1
  }
  count = var.ha ? 1 : 0
}

resource "google_compute_instance" "redpanda" {
  count             = var.nodes
  name              = "rp-node-${count.index}-${local.deployment_id}"
  tags              = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  zone              = "${var.region}-${var.availability_zone[count.index % length(var.availability_zone)]}"
  machine_type      = var.machine_type
  // GCP does not give you visibility nor control over which failure domain a resource has been placed into
  // (https://issuetracker.google.com/issues/256993209?pli=1). So the only way that we can guarantee that
  // specific nodes are in separate racks is to put them into entirely separate failure domains - basically one
  // broker per failure domain, and we are limited by the number of failure domains (at the moment 8).
  resource_policies = (var.ha && var.nodes <= 8) ? [
    google_compute_resource_policy.redpanda-rp[0].id
  ] : null

  metadata = {
    ssh-keys = <<KEYS
${var.ssh_user}:${file(abspath(var.public_key_path))}
KEYS
  }

  boot_disk {
    initialize_params {
      image = var.image
    }
  }

  dynamic "scratch_disk" {
    for_each = range(var.disks)
    content {
      // 375 GB local SSD drive.
      interface = "NVME"
    }
  }

  network_interface {
    subnetwork = var.subnet
    access_config {
    }
  }

  labels = tomap(var.labels)
}

resource "google_compute_instance" "monitor" {
  count        = 1
  name         = "rp-monitor-${local.deployment_id}"
  tags         = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  machine_type = var.monitor_machine_type
  zone         = "${var.region}-${var.availability_zone[0]}"

  metadata = {
    ssh-keys = <<KEYS
${var.ssh_user}:${file(abspath(var.public_key_path))}
KEYS
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
    subnetwork = var.subnet
    access_config {
    }
  }

  labels = tomap(var.labels)
}

resource "google_compute_instance" "client" {
  count        = var.client_nodes
  name         = "rp-client-${count.index}-${local.deployment_id}"
  tags         = ["rp-cluster", "tf-deployment-${local.deployment_id}"]
  machine_type = var.client_machine_type
  zone         = "${var.region}-${var.availability_zone[count.index % length(var.availability_zone)]}"

  metadata = {
    ssh-keys = <<KEYS
${var.ssh_user}:${file(abspath(var.public_key_path))}
KEYS
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
    subnetwork = var.subnet
    access_config {
    }
  }
  labels = tomap(var.labels)
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
      redpanda_public_ips        = google_compute_instance.redpanda[*].network_interface.0.access_config.0.nat_ip
      redpanda_private_ips       = google_compute_instance.redpanda[*].network_interface.0.network_ip
      clients_public_ips         = google_compute_instance.client[*].network_interface.0.access_config.0.nat_ip
      clients_private_ips        = google_compute_instance.client[*].network_interface.0.network_ip
      control_public_ips         = google_compute_instance.client[*].network_interface.0.access_config.0.nat_ip
      control_private_ips        = google_compute_instance.client[*].network_interface.0.network_ip
      prometheus_host_public_ips = google_compute_instance.monitor[*].network_interface.0.access_config.0.nat_ip
      prometheus_host_private_ips= google_compute_instance.monitor[*].network_interface.0.network_ip
      ssh_user                   = var.ssh_user
      instance_type              = var.machine_type
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

variable "subnet" {
  description = "The name of the existing subnet where the machines will be deployed"
}

variable "project_name" {
  description = "The project name on GCP."
}

variable "nodes" {
  description = "The number of nodes to deploy."
  type        = number
  default     = "3"
}

variable "ha" {
  description = "Whether to use placement groups to create an HA topology"
  type        = bool
  default     = false
}

variable "client_nodes" {
  description = "The number of clients to deploy."
  type        = number
  default     = "4"
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

variable machine_type {
  # List of available machines per region/ zone:
  # https://cloud.google.com/compute/docs/regions-zones#available
  default = "n2-standard-8"
}

variable monitor_machine_type {
  default = "n2-standard-4"
}

variable client_machine_type {
  default = "n2-standard-16"
}

variable "public_key_path" {
  description = "The ssh key."
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
    "purpose"      = "redpanda-cluster"
    "created-with" = "terraform"
  }
}
