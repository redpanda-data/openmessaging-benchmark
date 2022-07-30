public_key_path = "~/.ssh/redpanda_aws.pub"
region          = "us-west-2"
az		= "us-west-2a"
ami             = "ami-0928f4202481dfdf6"
profile         = "default"

instance_types = {
  "redpanda"      = "i3en.6xlarge"
  "client"        = "c5n.9xlarge"
}

num_instances = {
  "client"     = 2
  "redpanda"   = 3
}
