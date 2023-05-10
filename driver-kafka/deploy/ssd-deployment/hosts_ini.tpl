[kafka:children]
broker
controller

[broker]
%{ for i, ip in broker_public_ips ~}
${ ip } ansible_user=${ ssh_user } ansible_become=True private_ip=${broker_private_ips[i]} id=${i}
%{ endfor ~}
%{ for i, ip in broker_controller_public_ips ~}
${ ip } ansible_user=${ ssh_user } ansible_become=True private_ip=${broker_controller_private_ips[i]} id=${i}
%{ endfor ~}

[controller]
%{ for i, ip in controller_public_ips ~}
${ ip } ansible_user=${ ssh_user } ansible_become=True private_ip=${controller_private_ips[i]} id=${i}
%{ endfor ~}
%{ for i, ip in broker_controller_public_ips ~}
${ ip } ansible_user=${ ssh_user } ansible_become=True private_ip=${broker_controller_private_ips[i]} id=${i}
%{ endfor ~}

[zookeeper]
%{ for i, ip in zk_public_ips ~}
${ ip } ansible_user=${ ssh_user } ansible_become=True private_ip=${zk_private_ips[i]} id=${i}
%{ endfor ~}

[client]
%{ for i, ip in clients_public_ips ~}
${ ip } ansible_user=${ ssh_user } ansible_become=True private_ip=${clients_private_ips[i]} id=${i}
%{ endfor ~}

[all:vars]
instance_type=${ instance_type }
