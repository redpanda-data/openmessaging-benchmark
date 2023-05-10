public_key_path = "~/.ssh/redpanda_aws.pub"
region          = "us-west-2"
az		        = "us-west-2a"
ami             = "ami-0d31d7c9fc9503726"
redpanda_ami    = "ami-0d31d7c9fc9503726"
profile         = "default"

instance_types = {
  "redpanda"      = "i3en.6xlarge"
  "client"        = "c5n.9xlarge"
  "prometheus"    = "c5.2xlarge"
}

num_instances = {
  "client"     = 4
  "redpanda"   = 3
  "prometheus" = 1
}
