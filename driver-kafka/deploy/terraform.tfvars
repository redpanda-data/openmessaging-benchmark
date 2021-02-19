public_key_path = "~/.ssh/kafka_aws.pub"
region          = "us-west-2"
ami             = "ami-01e78c5619c5e68b4"
profile         = "default"

instance_types = {
  "kafka"      = "i3en.6xlarge"
  "zookeeper"  = "c5.xlarge"
  "client"     = "m5n.8xlarge"
  "prometheus" = "c5.2xlarge"
}

num_instances = {
  "client"     = 2
  "kafka"      = 3
  "zookeeper"  = 3
  "prometheus" = 1
}
