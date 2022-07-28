provider "aws" {
  region  = var.region
  version = "~> 2.7"
  profile = var.profile
}

provider "random" {
  version = "~> 2.1"
}

variable "username" {
  description = "Owner"
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

variable "ami" {}

variable "profile" {}

variable "instance_types" {
  type = map
}

variable "num_instances" {
  type = map
}

# Create a VPC to launch our instances into
resource "aws_vpc" "benchmark_vpc" {
  cidr_block = "10.0.0.0/16"

  tags = {
    Name = "RedPanda-Benchmark-VPC-${random_id.hash.hex}"
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
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # outbound internet access
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "Benchmark-Security-Group-${random_id.hash.hex}"
  }
}

resource "aws_key_pair" "auth" {
  key_name   = "${var.key_name}-${random_id.hash.hex}"
  public_key = "${file(var.public_key_path)}"
}

resource "aws_instance" "redpanda" {
  ami                    = "${var.ami}"
  instance_type          = "${var.instance_types["redpanda"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["redpanda"]}"
  monitoring             = true

  tags = {
    Name = "omb-redpanda-${count.index}"
    Owner = "${var.username}"
  }
}

resource "aws_instance" "client" {
  ami                    = "${var.ami}"
  instance_type          = "${var.instance_types["client"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["client"]}"
  monitoring             = true

  root_block_device {
    delete_on_termination = true
    volume_type = "gp2"
    volume_size = 40
  }

  tags = {
    Name = "omb-client-${count.index}"
    Owner = "${var.username}"
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
      control_public_ips   = aws_instance.client.*.public_ip
      control_private_ips  = aws_instance.client.*.private_ip
      ssh_user              = "ubuntu"
    }
  )
  filename = "${path.module}/hosts.ini"
}
