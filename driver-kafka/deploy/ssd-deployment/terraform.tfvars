public_key_path = "~/.ssh/kafka_aws.pub"
region          = "us-west-2"
az              = "us-west-2a"
ami             = "ami-08970fb2e5767e3b8" // RHEL-7.4
kafka_ami       = "ami-08970fb2e5767e3b8" // RHEL-7.4

instance_types = {
  "kafka"     = "i3en.xlarge"
  "zookeeper" = "t2.large"
  "client"    = "m5n.8xlarge"
}

num_instances = {
  "client"            = 4
  "broker"            = 3
  "controller"        = 0
  "broker_controller" = 0
  "zookeeper"         = 3
}
