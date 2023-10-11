public_key_path = "~/.ssh/redpanda_aws.pub"
region          = "us-east-1"
az		        = "us-east-1b"
profile         = "default"

instance_types = {
  "redpanda"      = "i3en.6xlarge"
  "client"        = "m5.large"
  "prometheus"    = "c5.2xlarge"
}

num_instances = {
  "client"     = 2
  "redpanda"   = 0
  "prometheus" = 0
}
