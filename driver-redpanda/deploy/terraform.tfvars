public_key_path = "~/.ssh/redpanda_aws.pub"
region          = "us-west-2"
ami             = "ami-02f147dfb8be58a10"
profile         = "default"

instance_types = {
  "redpanda"      = "i3en.2xlarge"
  "client"        = "c5n.4xlarge"
  "prometheus"    = "c5.2xlarge"
}

num_instances = {
  "client"     = 1
  "redpanda"   = 3
  "prometheus" = 1
}
