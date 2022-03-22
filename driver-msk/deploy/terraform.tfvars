public_key_path     = "~/.ssh/id_rsa.pub"
region              = "us-east-2"
ami                 = "ami-03d64741867e7bb94"
profile             = "default"
kafka_version       = "2.6.2"

instance_types = {
  "client"     = "m5.4xlarge"
  "broker"     = "kafka.m5.2xlarge"
}

num_instances = {
  "client"     = 2
  "broker"     = 9
}
