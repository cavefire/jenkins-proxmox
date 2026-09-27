# Jenkins Proxmox Plugin

Use Proxmox virtual machines as agents in Jenkins

[![Proxmox Plugin](https://img.shields.io/jenkins/plugin/v/proxmox.svg)](https://plugins.jenkins.io/proxmox)
[![ChangeLog](https://img.shields.io/github/release/jenkinsci/proxmox-plugin.svg?label=changelog)](https://github.com/jenkinsci/proxmox-plugin/releases/latest)
[![Installs](https://img.shields.io/jenkins/plugin/i/proxmox.svg?color=blue)](https://plugins.jenkins.io/proxmox)
[![License](https://img.shields.io/github/license/jenkinsci/proxmox-plugin.svg)](LICENSE)
[![Build Status](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/)

## Description

This plugin allows the use of Proxmox virtual machines as agents in Jenkins.

## Limitations

-   Only Qemu virtual machines supported (at the moment).
-   No option to avoid rolling back to a snapshot on agent start up.
-   No checking on virtual machine ready state/errors during rollback.

## Configuration

#### Datacenter cloud

To add a new Proxmox datacenter cloud, click on "Manage Jenkins" then
"Configure System". In the "Cloud" section click "Add cloud" and select
"Datacenter".

#### Virtual machine agents

To add agents click on "Manage Jenkins" then "Manage Nodes". Select the
node type "Agent virtual machine running on a Proxmox datacenter." and
enter a name for the node.

#### Single-use agents from templates

A Proxmox datacenter cloud can also create a fresh agent for every build from a template, and delete it
afterwards. Enable "Provision agents from templates" in the cloud configuration.

-   Templates (QEMU VMs or LXC containers) are used when they carry the Proxmox tag `jenkins-template`. All other
    tags are the labels they provide: a template tagged `jenkins-template;ubuntu2404` serves
    `agent { label 'ubuntu2404' }`.
-   For each build waiting for such a label, the template is cloned (linked clone by default). The clone is tagged
    `jenkins-instance`, and its notes name the Jenkins instance, the agent node and, once the build starts,
    the build URL.
-   The agent takes one build. When the build has finished (successful, failed or aborted), the clone is stopped
    and destroyed, but only if its notes still name that build. Clones with edited notes are left alone.
-   Clones left behind (e.g. after a Jenkins crash) are deleted by a periodic cleanup once their node is gone and
    their build is no longer running, using the same notes check.

Jenkins connects to the clones with SSH (using the SSH Build Agents plugin), so templates need an SSH server and
Java; Linux and Windows (Microsoft OpenSSH) guests work. The clone's IP address is read from Proxmox: from the
container's interfaces for LXC, and through the QEMU guest agent for VMs, so VM templates need
`qemu-guest-agent` installed and the guest agent option enabled.

##### Connecting only over SSH

Set "SSH credentials for the Proxmox hosts" when Jenkins can only reach the Proxmox hosts on port 22. Jenkins
then uses nothing but SSH to the hosts:

-   The Proxmox API is tunneled through SSH to the host's local port 8006. List all hosts of the cluster in
    "Hostname", separated by commas; the API fails over between them.
-   Clones are reached through the host running them (a port forward to the clone's SSH port), so clones can be on
    a network only the Proxmox hosts reach, e.g. an internal bridge.

The SSH user needs no shell, only port forwarding to the clones' SSH port and to `127.0.0.1:8006`. For example,
for a user `jenkinsro` (shell `/usr/sbin/nologin`) in `/etc/ssh/sshd_config.d/jenkinsro.conf`:

```
Match User jenkinsro
    AllowTcpForwarding local
    PermitOpen *:22 127.0.0.1:8006
    PermitTTY no
    X11Forwarding no
    AllowAgentForwarding no
    ForceCommand /usr/sbin/nologin
```

##### Clusters and passed-through devices

In a cluster, every clone is started on the least loaded online node (average of CPU and memory use, counting
clones that are being created or have not started yet) that can run it:

-   Templates may use PCI, USB and directory [resource mappings](https://pve.proxmox.com/wiki/QEMU/KVM_Virtual_Machines#resource_mapping)
    (`hostpci0: mapping=gpu`, `usb0: mapping=dongle`, `virtiofs0: dirid=share`). A clone only goes to a node the
    mapping has a device on, and only while one of those devices is free: devices used by running VMs, by other
    Jenkins clones and by clones being created are counted. Mediated devices (vGPUs) and directories are not
    counted, as they can be shared. If no node has a free device, the build waits and provisioning is retried.
-   Devices passed through without a mapping (`hostpci0: 0000:01:00`, `usb0: host=1234:5678`) only exist on
    the template's node, so such templates only run there. Create resource mappings to use them cluster wide.
-   If the template's disks are on shared storage, the clone is created directly on the chosen node. With local
    storage, Jenkins makes a full clone on the template's node and migrates it (offline) to the chosen node; the
    storage must exist on that node too. To avoid the copy, put a template with the same tags on every node:
    Jenkins then uses the copy on the chosen node.
-   Device passthrough into containers (`dev0: ...`) is not taken into account.

"Test provisioning" shows the load of every node and, per template, which nodes can run it and why not.

Settings that differ per template go into the template's notes, one `jenkins-<key>: <value>` line each, and
override the cloud defaults:

```
jenkins-credentials: windows-ssh
jenkins-remote-fs: C:\jenkins
jenkins-java: C:\Program Files\Java\jdk-11\bin\java.exe
jenkins-jvm-options: -Xmx2g
jenkins-ssh-port: 22
jenkins-network: 10.10.10.0/24
```

`jenkins-credentials` is the ID of a Jenkins "SSH username with private key" (or username/password) credential.
`jenkins-network` picks the address to use if a clone has several.

The Proxmox API user needs permission to clone, configure, start, stop and delete guests and to read guest agent
data (`VM.Clone`, `VM.Allocate`, `VM.Config.*`, `VM.PowerMgmt`, `VM.Audit`, `VM.Monitor` or
`VM.GuestAgent.Audit` on Proxmox 9, `Datastore.AllocateSpace`).

The plugin supports Jenkins 2.319.1 and newer.

## Manually Installing
 1. Clone this repo.
 2. Run ``mvn clean package``. 
 3. Go to Jenkins in a web browser.
 4. Click on *"Manage Jenkins"*, select *"Manage Plugins"*. 
 5. Click on the *"Advanced"* tab then upload the file `target/proxmox.hpi` under the *"Upload Plugin"* section.
 
To run directly a Jenkins test instance with the plugin, run ``mvn hpi:run``.


## ChangLog
-   For recent versions, see [GitHub Releases](https://github.com/jenkinsci/proxmox-plugin/releases)
-   For versions 0.2.1 and older, see the [Wiki page](https://wiki.jenkins.io/display/JENKINS/Proxmox+Plugin)

