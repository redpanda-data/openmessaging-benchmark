public_key_path = "~/.ssh/redpanda_aws.pub"
region          = "us-west-2"
ami             = "ami-020405ee5d5747724"
profile         = "default"

instance_types = {
  "redpanda"      = "i3en.2xlarge"
  "client"        = "c5n.2xlarge"
  "prometheus"    = "c5.2xlarge"
}

num_instances = {
  "client"     = 4
  "redpanda"   = 3
  "prometheus" = 1
}
