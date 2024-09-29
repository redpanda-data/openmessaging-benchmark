terraform {
  required_providers {
    aws = {
      version = "4.35.0"
    }
    random = {
      version = "~> 3.4.3"
    }
  }
}
provider "aws" {
  region  = var.region
}

provider "random" {
}

variable "public_key_path" {
  description = <<DESCRIPTION
Path to the SSH public key to be used for authentication.
Ensure this keypair is added to your local SSH agent so provisioners can
connect.

Example: ~/.ssh/redpanda_aws.pub
DESCRIPTION
}

resource "random_id" "hash" {
  byte_length = 8
}

variable "key_name" {
  default     = "redpanda-benchmark-key"
  description = "Desired name prefix for the AWS key pair"
}

variable "region" {}

#variable "ami" {}

variable "profile" {
  default = null
}

variable "instance_types" {
  type = map
}

variable "num_instances" {
  type = map
}

variable "machine_architecture" {
  description = "Architecture used for selecting the AMI - change this if using ARM based instances"
  default     = "x86_64"
}

variable "distro" {
  description = "The default distribution to base the cluster on"
  default     = "ubuntu-focal"
}
variable "redpanda_ami" {
  description = "AMI for Redpanda broker nodes (if not set, will select based on the client_distro variable"
  default     = null
}

variable "prometheus_ami" {
  description = "AMI for prometheus nodes (if not set, will select based on the client_distro variable"
  default     = null
}

variable "client_ami" {
  description = "AMI for Redpanda client nodes (if not set, will select based on the client_distro variable"
  default     = null
}

data "aws_caller_identity" "current" {}

locals {
    user_id    = data.aws_caller_identity.current.user_id
}


# Create a VPC to launch our instances into
resource "aws_vpc" "benchmark_vpc" {
  cidr_block = "10.0.0.0/16"

  tags = {
    Name  = "RedPanda-Benchmark-VPC-${random_id.hash.hex}"
    owner = "${local.user_id}"
  }
}

# Create an internet gateway to give our subnet access to the outside world
resource "aws_internet_gateway" "redpanda" {
  vpc_id = aws_vpc.benchmark_vpc.id
}

# Grant the VPC internet access on its main route table
resource "aws_route" "internet_access" {
  route_table_id         = aws_vpc.benchmark_vpc.main_route_table_id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = "${aws_internet_gateway.redpanda.id}"
}

# Create a subnet to launch our instances into
resource "aws_subnet" "benchmark_subnet" {
  vpc_id                  = aws_vpc.benchmark_vpc.id
  cidr_block              = "10.0.0.0/24"
  map_public_ip_on_launch = true
  availability_zone       = "us-west-2a"
}

# Get public IP of this machine
data "http" "myip" {
  url = "http://ipv4.icanhazip.com"
}

data "aws_ami" "ami" {
  most_recent = true

  filter {
    name = "name"
    values = [
      "ubuntu/images/hvm-ssd/ubuntu-*-amd64-server-*",
      "ubuntu/images/hvm-ssd/ubuntu-*-arm64-server-*",
      "Fedora-Cloud-Base-*.x86_64-hvm-us-west-2-gp2-0",
      "debian-*-amd64-*",
      "debian-*-hvm-x86_64-gp2-*'",
      "amzn2-ami-hvm-2.0.*-x86_64-gp2",
      "RHEL*HVM-*-x86_64*Hourly2-GP2"
    ]
  }

  filter {
    name   = "architecture"
    values = [var.machine_architecture]
  }

  filter {
    name   = "name"
    values = ["*${var.distro}*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  owners = ["099720109477", "125523088429", "136693071363", "137112412989", "309956199498"]
  # Canonical, Fedora, Debian (new), Amazon, RedHat
}

resource "aws_security_group" "benchmark_security_group" {
  name   = "terraform-redpanda-${random_id.hash.hex}"
  vpc_id = aws_vpc.benchmark_vpc.id

  # SSH access from anywhere
  # ingress {
  #   from_port   = 22
  #   to_port     = 22
  #   protocol    = "tcp"
  #   cidr_blocks = ["0.0.0.0/0"]
  # }

  # Allow pings between ec2 instances
   ingress {
     from_port   = 8
     to_port     = 0
     protocol    = "icmp"
     cidr_blocks = ["10.0.0.0/16"]
   }

  # All ports open within the VPC
  ingress {
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]
  }

  # All ports open to this machine
  ingress {
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = ["${chomp(data.http.myip.body)}/32"]
  }

  #Prometheus/Dashboard access
  ingress {
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = ["${chomp(data.http.myip.body)}/32"]
  }
  ingress {
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["${chomp(data.http.myip.body)}/32"]
  }

  # outbound internet access
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name  = "Benchmark-Security-Group-${random_id.hash.hex}"
    owner = "${local.user_id}"
  }
}

resource "aws_key_pair" "auth" {
  key_name   = "${var.key_name}-${random_id.hash.hex}"
  public_key = "${file(var.public_key_path)}"
}

resource "aws_instance" "redpanda" {
  ami                    = coalesce(var.redpanda_ami, data.aws_ami.ami.image_id)
  instance_type          = "${var.instance_types["redpanda"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["redpanda"]}"
  monitoring             = true

  tags = {
    Name  = "redpanda-${count.index}"
    owner = "${local.user_id}"
  }
}

resource "aws_instance" "client" {
  ami                    = coalesce(var.client_ami, data.aws_ami.ami.image_id)
  instance_type          = "${var.instance_types["client"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["client"]}"
  monitoring             = true

  tags = {
    Name  = "redpanda-client-${count.index}"
    owner = "${local.user_id}"
  }
}

resource "aws_instance" "prometheus" {
  ami                    = coalesce(var.prometheus_ami, data.aws_ami.ami.image_id)
  instance_type          = "${var.instance_types["prometheus"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["prometheus"]}"

  tags = {
    Name  = "prometheus-${count.index}"
    owner = "${local.user_id}"
  }
}

output "client_ssh_host" {
  value = "${aws_instance.client.0.public_ip}"
}

resource "local_file" "hosts_ini" {
  content = templatefile("${path.module}/hosts_ini.tpl",
    {
      redpanda_public_ips   = aws_instance.redpanda.*.public_ip
      redpanda_private_ips  = aws_instance.redpanda.*.private_ip
      clients_public_ips   = aws_instance.client.*.public_ip
      clients_private_ips  = aws_instance.client.*.private_ip
      prometheus_host_public_ips   = aws_instance.prometheus.*.public_ip
      prometheus_host_private_ips  = aws_instance.prometheus.*.private_ip
      control_public_ips   = aws_instance.client.*.public_ip
      control_private_ips  = aws_instance.client.*.private_ip
      instance_type	   = var.instance_types["redpanda"]
      ssh_user              = "ubuntu"
    }
  )
  filename = "${path.module}/hosts.ini"
}
