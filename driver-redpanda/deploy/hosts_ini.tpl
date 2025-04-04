[redpanda]
%{ for i, ip in redpanda_public_ips ~}
${ ip } ansible_user=${ ssh_user } private_ip=${redpanda_private_ips[i]} id=${i}
%{ endfor ~}

[client]
%{ for i, ip in clients_public_ips ~}
${ ip } ansible_user=${ ssh_user } private_ip=${clients_private_ips[i]} id=${i}
%{ endfor ~}

[control]
${control_public_ips[0]} ansible_user=${ ssh_user } private_ip=${control_private_ips[0]} id=0

[prometheus]
%{ for i, ip in prometheus_host_public_ips ~}
${ ip } ansible_user=${ ssh_user } private_ip=${prometheus_host_private_ips[i]} id=${i}
%{ endfor ~}

[all:vars]
instance_type=${instance_type}
ansible_ssh_private_key_file=${private_key_file}

[client:vars]
ansible_ssh_common_args=${client_ansible_ssh_common_args}