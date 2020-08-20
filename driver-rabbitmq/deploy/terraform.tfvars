public_key_path = "~/.ssh/rabbitmq_aws.pub"
region          = "us-west-2"
ami             = "ami-9fa343e7" // RHEL-7.4

instance_types = {
  "rabbitmq"   = "i3en.2xlarge"
  "client"     = "c5n.9xlarge"
  "prometheus" = "t2.small"
}

num_instances = {
  "rabbitmq"    = 3
  "client"      = 4
  "prometheus"  = 1
}