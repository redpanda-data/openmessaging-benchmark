#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

SERVERS=( $(terraform output server0) $(terraform output server1) $(terraform output server2) )
for i in "${SERVERS[@]}"
do
  ssh -i ~/.ssh/pulsar_aws ec2-user@$i 'sudo service bookkeeper.service restart'
  sleep 1 
  ssh -i ~/.ssh/pulsar_aws ec2-user@$i 'sudo service pulsar restart'
done


WORKERS=( $(terraform output client_ssh_host) $(terraform output client2) $(terraform output client2) $(terraform output client3) )
for i in "${WORKERS[@]}"
do
  ssh -i ~/.ssh/pulsar_aws ec2-user@$i 'sudo service benchmark-worker restart'
done



