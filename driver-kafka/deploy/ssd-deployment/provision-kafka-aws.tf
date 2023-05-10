provider "aws" {
  region  = "${var.region}"
  version = "3.50"
}

provider "random" {
  version = "3.1"
}

variable "public_key_path" {
  description = <<DESCRIPTION
Path to the SSH public key to be used for authentication.
Ensure this keypair is added to your local SSH agent so provisioners can
connect.

Example: ~/.ssh/kafka_aws.pub
DESCRIPTION
}

resource "random_id" "hash" {
  byte_length = 8
}

variable "key_name" {
  default     = "kafka-benchmark-key"
  description = "Desired name prefix for the AWS key pair"
}

variable "region" {}

variable "ami" {}

variable "kafka_ami" {}

variable "az" {}

variable "instance_types" {
  type = map(string)
}

variable "num_instances" {
  type = map(string)
}

variable "owner" {}

# Create a VPC to launch our instances into
resource "aws_vpc" "benchmark_vpc" {
  cidr_block = "10.0.0.0/16"

  tags = {
    Name  = "Kafka-Benchmark-VPC-${random_id.hash.hex}"
    owner = "${var.owner}"
  }
}

# Create an internet gateway to give our subnet access to the outside world
resource "aws_internet_gateway" "kafka" {
  vpc_id = "${aws_vpc.benchmark_vpc.id}"
}

# Grant the VPC internet access on its main route table
resource "aws_route" "internet_access" {
  route_table_id         = "${aws_vpc.benchmark_vpc.main_route_table_id}"
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = "${aws_internet_gateway.kafka.id}"
}

# Create a subnet to launch our instances into
resource "aws_subnet" "benchmark_subnet" {
  vpc_id                  = "${aws_vpc.benchmark_vpc.id}"
  cidr_block              = "10.0.0.0/24"
  map_public_ip_on_launch = true
  availability_zone       = "${var.az}"
}

resource "aws_security_group" "benchmark_security_group" {
  name   = "terraform-kafka-${random_id.hash.hex}"
  vpc_id = "${aws_vpc.benchmark_vpc.id}"

  # SSH access from anywhere
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # All ports open within the VPC
  ingress {
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]
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
    owner = "${var.owner}"
  }
}

resource "aws_key_pair" "auth" {
  key_name   = "${var.key_name}-${random_id.hash.hex}"
  public_key = "${file(var.public_key_path)}"
}

resource "aws_instance" "zookeeper" {
  ami                    = "${var.ami}"
  instance_type          = "${var.instance_types["zookeeper"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["zookeeper"]}"

  tags = {
    Name  = "zk-${count.index}"
    owner = "${var.owner}"
  }
}

resource "aws_instance" "broker" {
  ami                    = "${var.kafka_ami}"
  instance_type          = "${var.instance_types["kafka"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["broker"]}"

  tags = {
    Name  = "broker-${count.index}"
    owner = "${var.owner}"
  }
}

resource "aws_instance" "broker_controller" {
  ami                    = "${var.kafka_ami}"
  instance_type          = "${var.instance_types["kafka"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["broker_controller"]}"

  tags = {
    Name  = "brokercontroller-${count.index}"
    owner = "${var.owner}"
  }
}

resource "aws_instance" "controller" {
  ami                    = "${var.kafka_ami}"
  instance_type          = "${var.instance_types["kafka"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["controller"]}"

  tags = {
    Name  = "controller-${count.index}"
    owner = "${var.owner}"
  }
}

resource "aws_instance" "client" {
  ami                    = "${var.ami}"
  instance_type          = "${var.instance_types["client"]}"
  key_name               = "${aws_key_pair.auth.id}"
  subnet_id              = "${aws_subnet.benchmark_subnet.id}"
  vpc_security_group_ids = ["${aws_security_group.benchmark_security_group.id}"]
  count                  = "${var.num_instances["client"]}"

  tags = {
    Name  = "kafka-client-${count.index}"
    owner = "${var.owner}"
  }
}

output "client_ssh_host" {
  value = "${aws_instance.client.0.public_ip}"
}

resource "local_file" "hosts_ini" {
  content = templatefile("${path.module}/hosts_ini.tpl",
    {
      broker_public_ips      = aws_instance.broker.*.public_ip
      broker_private_ips     = aws_instance.broker.*.private_ip
      zk_public_ips          = aws_instance.zookeeper.*.public_ip
      zk_private_ips         = aws_instance.zookeeper.*.private_ip
      clients_public_ips     = aws_instance.client.*.public_ip
      clients_private_ips    = aws_instance.client.*.private_ip
      controller_public_ips     = aws_instance.controller.*.public_ip
      controller_private_ips    = aws_instance.controller.*.private_ip
      broker_controller_public_ips     = aws_instance.broker_controller.*.public_ip
      broker_controller_private_ips    = aws_instance.broker_controller.*.private_ip
      ssh_user               = "ec2-user"
      instance_type          = var.instance_types["kafka"]
    }
  )
  filename = "${path.module}/hosts.ini"
}
