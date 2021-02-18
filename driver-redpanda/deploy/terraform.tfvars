public_key_path = "~/.ssh/redpanda_aws.pub"
region          = "us-west-2"
ami             = "ami-0928f4202481dfdf6"
profile         = "default"

instance_types = {
  "redpanda"      = "i3en.6xlarge"
  "client"        = "m5n.8xlarge"
  "prometheus"    = "c5.2xlarge"
}

num_instances = {
  "client"     = 2
  "redpanda"   = 3
  "prometheus" = 1
}
