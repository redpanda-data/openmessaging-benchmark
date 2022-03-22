provider "aws" {
  region  = "${var.region}"
}

provider "random" {
}

variable "public_key_path" {}

variable "key_name" {
  default     = "kafka-benchmark-key"
  description = "Desired name prefix for the AWS key pair"
}

variable "region" {}

variable "ami" {}

variable "profile" {}

variable "kafka_version" {}

variable "instance_types" {
  type = map(string)
}

variable "num_instances" {
  type = map(number)
}

data "aws_availability_zones" "azs" {
  state = "available"
}

resource "random_id" "hash" {
  byte_length = 8
}

resource "aws_vpc" "vpc" {
  cidr_block = "192.168.0.0/22"
}

# Create an internet gateway to give our subnet access to the outside world
resource "aws_internet_gateway" "msk-bench" {
  vpc_id = "${aws_vpc.vpc.id}"
}

# Grant the VPC internet access on its main route table
resource "aws_route" "internet_access" {
  route_table_id         = "${aws_vpc.vpc.main_route_table_id}"
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = "${aws_internet_gateway.msk-bench.id}"
}

resource "aws_subnet" "subnet_az1" {
  availability_zone = data.aws_availability_zones.azs.names[0]
  cidr_block        = "192.168.0.0/24"
  vpc_id            = aws_vpc.vpc.id
}

resource "aws_subnet" "subnet_az2" {
  availability_zone = data.aws_availability_zones.azs.names[1]
  cidr_block        = "192.168.1.0/24"
  vpc_id            = aws_vpc.vpc.id
}

resource "aws_subnet" "subnet_az3" {
  availability_zone = data.aws_availability_zones.azs.names[2]
  cidr_block        = "192.168.2.0/24"
  vpc_id            = aws_vpc.vpc.id
}

#resource "aws_subnet" "subnet_clients" {
#  availability_zone       = data.aws_availability_zones.azs.names[0]
#  cidr_block              = "192.168.3.0/24"
#  vpc_id                  = aws_vpc.vpc.id
#  map_public_ip_on_launch = true
#}

resource "aws_security_group" "sg" {
  vpc_id = aws_vpc.vpc.id
  name   = "omb-sg-${random_id.hash.hex}"

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

  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    security_groups = [aws_security_group.sg-client.id]
  }
}

resource "aws_security_group" "sg-client" {
  vpc_id = aws_vpc.vpc.id
  name   = "omb-client-sg-${random_id.hash.hex}"

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

  # All ports open to this machine
  ingress {
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = ["${chomp(data.http.myip.body)}/32"]
  }

  # Prometheus/Dashboard access
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

resource "aws_msk_cluster" "bench" {
  cluster_name           = "bench"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = "${var.num_instances["broker"]}"

  broker_node_group_info {
    instance_type   = "${var.instance_types["broker"]}"

    ebs_volume_size = 4000
    client_subnets = [
      aws_subnet.subnet_az1.id,
      aws_subnet.subnet_az2.id,
      aws_subnet.subnet_az3.id,
    ]
    security_groups = [aws_security_group.sg.id]
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "PLAINTEXT"
      in_cluster    = false
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  tags = {
    owner = "user"
  }
}

###
# Get public IP of this machine
data "http" "myip" {
  url = "http://ipv4.icanhazip.com"
}

resource "aws_key_pair" "auth" {
  key_name   = "${var.key_name}-${random_id.hash.hex}"
  public_key = "${file(var.public_key_path)}"
}

resource "aws_instance" "client" {
  ami                         = "${var.ami}"
  instance_type               = "${var.instance_types["client"]}"
  key_name                    = "${aws_key_pair.auth.id}"
  subnet_id                   = aws_subnet.subnet_az1.id
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.sg-client.id]
  count                       = "${var.num_instances["client"]}"
  monitoring                  = true

  tags = {
    Name = "kafka-client-${count.index}"
  }

  root_block_device {
    volume_size = 100
  }
}

output "clients" {
  value = {
    for instance in aws_instance.client :
        instance.public_ip => instance.private_ip
  }
}

output "zookeeperServers" {
  value = aws_msk_cluster.bench.zookeeper_connect_string
}

output "boostrapServers" {
  value       = aws_msk_cluster.bench.bootstrap_brokers
}

output "zookeeper" {
  value = {
    for instance in "${split (",", aws_msk_cluster.bench.zookeeper_connect_string)}" :
        "${element (split (":",instance),0)}" => "${element (split (":",instance),0)}"
  }
}

output "brokers" {
  value = {
    for instance in "${split (",", aws_msk_cluster.bench.bootstrap_brokers)}" :
        "${element (split (":",instance),0)}" => "${element (split (":",instance),0)}"
  }
}


#output "client_ssh_host" {
#  value = "${aws_instance.client.0.public_ip}"
#}
