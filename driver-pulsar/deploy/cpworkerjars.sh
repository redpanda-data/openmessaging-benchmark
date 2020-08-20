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

WORKERS=( $(terraform output client_ssh_host) $(terraform output client2) $(terraform output client2) $(terraform output client3) )
for i in "${WORKERS[@]}"
do
  scp -i ~/.ssh/pulsar_aws  ../../*/target/*.jar ec2-user@$i:~/
  ssh -i ~/.ssh/pulsar_aws ec2-user@$i 'sudo cp /home/ec2-user/*.jar /opt/benchmark/lib/'
  ssh -i ~/.ssh/pulsar_aws ec2-user@$i 'sudo service benchmark-worker restart'
done



