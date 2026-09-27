# Jenkins Proxmox Plugin

Single-use Jenkins agents from Proxmox VM and container templates

[![Build](https://github.com/cavefire/jenkins-proxmox/actions/workflows/build.yml/badge.svg)](https://github.com/cavefire/jenkins-proxmox/actions/workflows/build.yml)
[![Dependency and security checks](https://github.com/cavefire/jenkins-proxmox/actions/workflows/security.yml/badge.svg)](https://github.com/cavefire/jenkins-proxmox/actions/workflows/security.yml)
[![Release](https://img.shields.io/github/v/release/cavefire/jenkins-proxmox?label=release)](https://github.com/cavefire/jenkins-proxmox/releases/latest)
[![License](https://img.shields.io/github/license/cavefire/jenkins-proxmox.svg)](LICENSE)

## Description

This plugin gives every Jenkins build a fresh Proxmox guest: it clones a template (VM or LXC container) when a
build needs an agent and deletes the clone after the build.

This is a fork of [jenkinsci/proxmox-plugin](https://github.com/jenkinsci/proxmox-plugin) that adds single-use
agents and supports Jenkins 2.319.1 and newer.

## Configuration

To add a Proxmox datacenter cloud, click on "Manage Jenkins" then "Configure System". In the "Cloud" section
click "Add cloud" and select "Datacenter". The cloud creates a fresh agent for every build from a template, and
deletes it afterwards.

#### Templates

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

##### Permissions of the Proxmox API user

Set "Resource pool for clones" (e.g. `jenkins`) so that Jenkins may only change and delete its own clones: clones
are created in that pool, and the rights to modify guests are granted on the pool only. Proxmox permissions cannot
be limited to a range of VM IDs, so a pool is the way to do it.

| Privilege | Needed on | Used for |
|---|---|---|
| `VM.Audit` | `/vms` | finding templates, free VM IDs and devices in use (read only) |
| `VM.Clone` | each template (`/vms/<id>`, or a pool holding the templates) | cloning templates |
| `VM.Allocate`, `VM.Config.Options`, `VM.PowerMgmt`, `VM.Migrate` | `/pool/<pool>` | creating, tagging, starting, stopping, migrating and deleting clones |
| `VM.GuestAgent.Audit` (Proxmox 9) or `VM.Monitor` (Proxmox 8) | `/pool/<pool>` | reading the clones' IP addresses from the QEMU guest agent |
| `Pool.Allocate` | `/pool/<pool>` | adding clones to the pool |
| `Datastore.Audit`, `Datastore.AllocateSpace` | `/storage` | listing storages and allocating clone disks |
| `Sys.Audit` | `/nodes` | node load and cluster status |
| `SDN.Use` | `/sdn/zones` | attaching clones to bridges |
| `Mapping.Audit`, `Mapping.Use` | `/mapping` | only for templates with PCI, USB or directory resource mappings |

For example, on Proxmox 9 (use `VM.Monitor` instead of `VM.GuestAgent.Audit` on Proxmox 8), with the templates
in a pool `jenkins-templates`:

```
pveum pool add jenkins
pveum role add JenkinsRead -privs "VM.Audit Datastore.Audit Sys.Audit Mapping.Audit"
pveum role add JenkinsTemplates -privs "VM.Audit VM.Clone"
pveum role add JenkinsClones -privs "VM.Audit VM.Allocate VM.Config.Options VM.PowerMgmt VM.Migrate VM.GuestAgent.Audit Pool.Allocate"
pveum role add JenkinsUse -privs "Datastore.AllocateSpace SDN.Use Mapping.Use"
pveum user add jenkins@pve --password '<password>'
pveum acl modify / -user jenkins@pve -role JenkinsRead
pveum acl modify /pool/jenkins-templates -user jenkins@pve -role JenkinsTemplates
pveum acl modify /pool/jenkins -user jenkins@pve -role JenkinsClones
pveum acl modify /storage -user jenkins@pve -role JenkinsUse
pveum acl modify /sdn/zones -user jenkins@pve -role JenkinsUse
pveum acl modify /mapping -user jenkins@pve -role JenkinsUse
```

Jenkins can then see all guests, but only clone the templates and change or delete the guests in the `jenkins`
pool. Together with "First VM ID for clones" (e.g. `10000`), its clones are also easy to spot by ID.

The plugin supports Jenkins 2.319.1 and newer.

## Installing

1.  Download `proxmox-<version>.hpi`:
    -   a released version from [GitHub Releases](https://github.com/cavefire/jenkins-proxmox/releases), or
    -   the latest build of any branch or pull request: open its run of the
        [Build workflow](https://github.com/cavefire/jenkins-proxmox/actions/workflows/build.yml) and download the
        `proxmox-<version>` artifact (a zip containing `proxmox-<version>.hpi`).
2.  In Jenkins, go to *"Manage Jenkins"* → *"Manage Plugins"* → *"Advanced"* and upload the `.hpi` file under
    *"Upload Plugin"*.
3.  Restart Jenkins.

## Building

Building needs JDK 11 and Maven 3.

```
mvn clean verify
```

runs the tests and writes the plugin to `target/proxmox.hpi`. `mvn hpi:run` starts a Jenkins test instance with
the plugin installed.

## Continuous integration

GitHub Actions runs these workflows:

-   **Build** (every pull request, push to `master` and tag): runs the tests and, when they pass, builds
    `proxmox-<version>.hpi` and attaches it to the run as the `proxmox-<version>` artifact. The plugin version is
    `<tag>-<commits since tag>-<commit hash>` (e.g. `0.8.0-3-1a2b3c4`), based on the latest tag, or
    `0.0.0-<commits>-<commit hash>` before the first tag.
-   **Dependency and security checks** (every pull request, push to `master` and weekly):
    -   pull requests fail when they add a dependency with a known high or critical vulnerability;
    -   the libraries bundled in the plugin are scanned with Trivy, failing on fixable high or critical
        vulnerabilities and reporting all findings under *Security* → *Code scanning*;
    -   pushes to `master` submit the full Maven dependency tree, so Dependabot alerts cover transitive dependencies.
-   **Jenkins Security Scan** (every pull request and push to `master`): Jenkins-specific CodeQL checks.

Dependabot proposes updates for Maven dependencies and actions monthly.

To publish a release, push a tag named after the version, e.g. `0.8.0` or `v0.8.0`. The Build workflow then builds
the plugin with the tag as version (a leading `v` is dropped) and creates a GitHub release with `proxmox-<version>.hpi`
attached.

## Changelog

-   For recent versions, see [GitHub Releases](https://github.com/cavefire/jenkins-proxmox/releases)
-   For upstream versions, see the [upstream releases](https://github.com/jenkinsci/proxmox-plugin/releases)
-   For versions 0.2.1 and older, see the [Wiki page](https://wiki.jenkins.io/display/JENKINS/Proxmox+Plugin)

