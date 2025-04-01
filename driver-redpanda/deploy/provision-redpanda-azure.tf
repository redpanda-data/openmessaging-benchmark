terraform {
  required_providers {
    azurerm = {
    }
  }
}

provider "azurerm" {
  features {
    virtual_machine {
      # https://github.com/hashicorp/terraform-provider-azurerm/issues/28218
      skip_shutdown_and_force_delete = true
      delete_os_disk_on_deletion = true
    }
  }
  subscription_id = "db02ad12-b4bf-4472-b63a-d677b88866dd"
}

locals {
  #   resource_group        = "rg-rpcloud-cv45q6rhn4qo4datcqbg-network"
  location              = "East US"
  redpanda_vnet_name    = "/subscriptions/db02ad12-b4bf-4472-b63a-d677b88866dd/resourceGroups/rg-rpcloud-cv4pqc8mv808te19q9h0-network/providers/Microsoft.Network/virtualNetworks/vnet-rpcloud-eastus-cv4pqb0mv808te19q9eg"
  bastion_instance_size = "Standard_D2_v4"
  client_instance_size  = "Standard_D4_v4"
  new_vnet_cidr         = "10.1.0.0/16"
  client_count          = 0
}

resource "random_id" "bastion" {
  byte_length = 8
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${random_id.bastion.hex}"
  location = local.location
}

resource "azurerm_virtual_network" "main" {
  name                = "vnet-${random_id.bastion.hex}"
  address_space       = [local.new_vnet_cidr]
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
}

resource "azurerm_subnet" "main" {
  name                 = "subnet-${random_id.bastion.hex}"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = azurerm_virtual_network.main.address_space
}


resource "azurerm_network_security_group" "allow_ssh" {
  name                = "nsg-${random_id.bastion.hex}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  security_rule {
    name                       = "allow-ssh"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_public_ip" "public_ip" {
  name                = "pip-${random_id.bastion.hex}-bastion"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  allocation_method   = "Static"
}

resource "azurerm_network_interface" "bastion" {
  name                = "nic-${random_id.bastion.hex}-bastion"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  ip_configuration {
    name                          = "main"
    subnet_id                     = azurerm_subnet.main.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.public_ip.id
  }
}

resource "azurerm_network_interface_security_group_association" "bastion_allow_ssh" {
  network_interface_id      = azurerm_network_interface.bastion.id
  network_security_group_id = azurerm_network_security_group.allow_ssh.id
}

resource "tls_private_key" "main" {
  algorithm = "RSA"
  rsa_bits  = 4096
}

resource "local_sensitive_file" "private_key" {
  filename        = "private_key"
  file_permission = "400"
  content         = tls_private_key.main.private_key_openssh
}

resource "azurerm_linux_virtual_machine" "bastion" {
  name                = "vm-${random_id.bastion.hex}-bastion"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  size                = local.bastion_instance_size

  admin_username = "ubuntu"
  admin_ssh_key {
    username   = "ubuntu"
    public_key = tls_private_key.main.public_key_openssh
  }

  network_interface_ids = [
    azurerm_network_interface.bastion.id
  ]
  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
  }
  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-gen2"
    version   = "latest"
  }

  lifecycle {
    ignore_changes = [identity]
  }
}

# resource "azurerm_virtual_network_peering" "main_to_redpanda" {
#   name                      = "vnet-peering-${random_id.bastion.hex}"
#   resource_group_name       = azurerm_resource_group.main.name
#   virtual_network_name      = azurerm_virtual_network.main.name
#   remote_virtual_network_id = local.redpanda_vnet_name

#   # these force recreation otherwise
#   local_subnet_names        = []
#   only_ipv6_peering_enabled = false
#   remote_subnet_names       = []
# }


# resource "azurerm_virtual_network_peering" "redpanda_to_main" {
#   name                      = "vnet-peering-${random_id.bastion.hex}-reverse"
#   resource_group_name       = provider::azurerm::parse_resource_id(local.redpanda_vnet_name)["resource_group_name"]
#   virtual_network_name      = provider::azurerm::parse_resource_id(local.redpanda_vnet_name)["resource_name"]
#   remote_virtual_network_id = azurerm_virtual_network.main.id
# }

resource "azurerm_network_interface" "client" {
  count               = local.client_count
  name                = "nic-${random_id.bastion.hex}-client-${count.index}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  ip_configuration {
    name                          = "main"
    subnet_id                     = azurerm_subnet.main.id
    private_ip_address_allocation = "Dynamic"
  }
}

resource "azurerm_linux_virtual_machine" "client" {
  count               = local.client_count
  name                = "vm-${random_id.bastion.hex}-client-${count.index}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  size                = local.client_instance_size
  zone                = (count.index % 3) + 1

  admin_username = "ubuntu"
  admin_ssh_key {
    username   = "ubuntu"
    public_key = tls_private_key.main.public_key_openssh
  }

  network_interface_ids = [
    azurerm_network_interface.client[count.index].id
  ]
  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
  }
  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-gen2"
    version   = "latest"
  }

  lifecycle {
    ignore_changes = [identity]
  }
}

resource "azurerm_private_endpoint" "redpanda" {
  name                = "pe-${random_id.bastion.hex}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.main.id

  private_service_connection {
    name                           = "redpanda-pls-cv832675uii2sah1gbr0"
    private_connection_resource_id = "/subscriptions/db02ad12-b4bf-4472-b63a-d677b88866dd/resourceGroups/rg-rpcloud-cv832675uii2sah1gbr0-network/providers/Microsoft.Network/privateLinkServices/redpanda-pls-cv832675uii2sah1gbr0"
    is_manual_connection           = true
    request_message = "hello world"
  }
}

resource "azurerm_private_dns_zone" "redpanda1" {
  name = "cv832675uii2sah1gbr0.byoc.ign.cloud.redpanda.com"
  resource_group_name = azurerm_resource_group.main.name
}

resource "azurerm_private_dns_zone_virtual_network_link" "redpanda1" {
  name                  = "dns-link-${random_id.bastion.hex}"
  resource_group_name   = azurerm_resource_group.main.name
  private_dns_zone_name = azurerm_private_dns_zone.redpanda1.name
  virtual_network_id    = azurerm_virtual_network.main.id
}

resource "azurerm_private_dns_a_record" "redpanda" {
  name                = "*"
  zone_name           = azurerm_private_dns_zone.redpanda1.name
  resource_group_name = azurerm_resource_group.main.name
  ttl = 300
  records             = azurerm_private_endpoint.redpanda.private_service_connection.*.private_ip_address
}


output "bastion_public_ip" {
  value = azurerm_linux_virtual_machine.bastion.public_ip_address
}

output "bastion_username" {
  value = azurerm_linux_virtual_machine.bastion.admin_username
}

output "bastion_private_key" {
  value = local_sensitive_file.private_key.filename
}

output "bastion_ssh_command" {
  value = "ssh -i ${local_sensitive_file.private_key.filename} ${azurerm_linux_virtual_machine.bastion.admin_username}@${azurerm_linux_virtual_machine.bastion.public_ip_address}"
}

output "client_private_ips" {
  value = azurerm_linux_virtual_machine.client.*.private_ip_address
}

# output "client_ssh_command" {
#   value = "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ProxyCommand=\"ssh -i ${local_sensitive_file.private_key.filename} -W %h:%p ${azurerm_linux_virtual_machine.bastion.admin_username}@${azurerm_linux_virtual_machine.bastion.public_ip_address}\" -i ${local_sensitive_file.private_key.filename} ${azurerm_linux_virtual_machine.client.0.admin_username}@${azurerm_linux_virtual_machine.client.0.private_ip_address}"
# }

resource "local_file" "hosts_ini" {
  content = templatefile("${path.module}/hosts_ini.tpl",
    {
      redpanda_public_ips            = []
      redpanda_private_ips           = []
      clients_public_ips             = azurerm_linux_virtual_machine.client.*.private_ip_address
      clients_private_ips            = azurerm_linux_virtual_machine.client.*.private_ip_address
      prometheus_host_public_ips     = []
      prometheus_host_private_ips    = []
      control_public_ips             = [azurerm_linux_virtual_machine.bastion.public_ip_address]
      control_private_ips            = [azurerm_linux_virtual_machine.bastion.private_ip_address]
      instance_type                  = ""
      ssh_user                       = "ubuntu"
      private_key_file = local_sensitive_file.private_key.filename
      client_ansible_ssh_common_args = "-o ProxyCommand=\"ssh -W %h:%p -o StrictHostKeyChecking=no -i ${local_sensitive_file.private_key.filename} ${azurerm_linux_virtual_machine.bastion.admin_username}@${azurerm_linux_virtual_machine.bastion.public_ip_address}\""
    }
  )
  filename = "${path.module}/hosts.ini"
}
