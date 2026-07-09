# Ansible Basics Runbook

## Purpose

This runbook explains Ansible basics for DevOps automation.

It covers:

```text
control node
managed nodes
inventory
ansible.cfg
ad-hoc commands
modules
facts
idempotency
first playbook
play recap
lab vs production structures
```

---

## Mental model

Ansible usually works like this:

```text
Control node
  ↓ SSH / local connection
Managed nodes
```

Control node:

```text
machine where Ansible is executed
```

Managed node:

```text
machine managed by Ansible
```

In this lab:

```text
Control node = Mac
Managed node = localhost
Connection = local
```

---

## Terraform vs Ansible vs Kubernetes

Terraform:

```text
creates infrastructure
VMs, networks, cloud resources, security groups
```

Ansible:

```text
configures existing servers
packages, users, files, services, agents, configs
```

Kubernetes:

```text
runs and manages container workloads
Pods, Services, Ingress, ConfigMaps, Secrets
```

Typical production flow:

```text
Terraform creates VMs
  ↓
Ansible configures Linux servers
  ↓
Kubernetes / Helm / GitOps deploys applications
```

---

## Install Ansible on Mac

```bash
brew install ansible
```

Check:

```bash
ansible --version
ansible-playbook --version
```

---

## Project structure

```text
ansible/
├── ansible.cfg
├── inventories/
│   └── local/
│       └── hosts.ini
└── playbooks/
    └── local-basics.yml
```

---

## Inventory

File:

```text
ansible/inventories/local/hosts.ini
```

Content:

```ini
[local]
localhost ansible_connection=local
```

Meaning:

```text
[local]
  inventory group

localhost
  managed host

ansible_connection=local
  run tasks locally without SSH
```

Check:

```bash
cd ansible
ansible-inventory --list
```

---

## ansible.cfg

File:

```text
ansible/ansible.cfg
```

Content:

```ini
[defaults]
inventory = inventories/local/hosts.ini
host_key_checking = False
retry_files_enabled = False
stdout_callback = yaml
interpreter_python = auto_silent
```

Meaning:

```text
inventory
  default inventory path

host_key_checking
  SSH host key checking; disabled for lab

retry_files_enabled
  avoid .retry files

stdout_callback
  readable output

interpreter_python
  automatic Python interpreter discovery
```

Production note:

```text
Do not blindly disable host_key_checking in serious production environments.
```

---

## Ad-hoc commands

Ad-hoc commands are one-time Ansible commands without a playbook.

Ping module:

```bash
ansible local -m ping
```

Expected:

```text
pong
```

Command module:

```bash
ansible local -m command -a "hostname"
ansible local -m command -a "whoami"
```

Shell module:

```bash
ansible local -m shell -a "echo $SHELL && date"
```

Rule:

```text
Use Ansible modules when possible.
Use shell only when a suitable module does not exist or shell behavior is required.
```

---

## Facts

Gather facts:

```bash
ansible local -m setup
```

Filtered facts:

```bash
ansible local -m setup -a "filter=ansible_os_family"
ansible local -m setup -a "filter=ansible_distribution*"
```

Facts can include:

```text
OS family
distribution
hostname
IP addresses
CPU
memory
mounts
interfaces
Python version
```

---

## Idempotency

Idempotency means:

```text
If the desired state already exists, repeated execution should not change anything.
```

Good Ansible automation:

```text
first run  → changed
second run → ok / changed=0
```

Bad automation:

```text
every run changes something without a real reason
```

---

## file module practice

Create directory:

```bash
ansible local -m file -a "path=/tmp/ansible-demo state=directory mode=0755"
```

Run again:

```bash
ansible local -m file -a "path=/tmp/ansible-demo state=directory mode=0755"
```

Expected behavior:

```text
first run: changed=true
second run: changed=false
```

Check:

```bash
ls -ld /tmp/ansible-demo
```

---

## copy module practice

Create file:

```bash
ansible local -m copy -a "dest=/tmp/ansible-demo/hello.txt content='Hello from Ansible\n' mode=0644"
```

Run again:

```bash
ansible local -m copy -a "dest=/tmp/ansible-demo/hello.txt content='Hello from Ansible\n' mode=0644"
```

Expected:

```text
first run: changed=true
second run: changed=false
```

Change content:

```bash
ansible local -m copy -a "dest=/tmp/ansible-demo/hello.txt content='Hello from Ansible v2\n' mode=0644"
```

Expected:

```text
changed=true
```

---

## First playbook

File:

```text
ansible/playbooks/local-basics.yml
```

Content:

```yaml
---
- name: Local Ansible basics demo
  hosts: local
  gather_facts: true

  tasks:
    - name: Ensure demo directory exists
      ansible.builtin.file:
        path: /tmp/ansible-demo
        state: directory
        mode: "0755"

    - name: Write demo file
      ansible.builtin.copy:
        dest: /tmp/ansible-demo/hello.txt
        content: |
          Hello from Ansible playbook.
          Managed by Ansible.
        mode: "0644"

    - name: Show OS family
      ansible.builtin.debug:
        msg: "OS family is {{ ansible_os_family }}"
```

Run:

```bash
cd ansible
ansible-playbook playbooks/local-basics.yml
```

Run again:

```bash
ansible-playbook playbooks/local-basics.yml
```

Expected:

```text
Second run should have changed=0 or fewer changed tasks.
```

---

## Play recap

Example:

```text
PLAY RECAP
localhost : ok=4 changed=0 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0
```

Meaning:

```text
ok
  task succeeded

changed
  Ansible changed host state

unreachable
  Ansible could not connect to host

failed
  task failed

skipped
  task skipped by condition
```

Operational rule:

```text
If a playbook changes something every run without a real reason, check idempotency.
```

---

## Lab structure

```text
ansible/
├── ansible.cfg
├── inventories/
│   └── local/
│       └── hosts.ini
└── playbooks/
    └── local-basics.yml
```

This is enough for learning.

---

## Small production structure

```text
ansible/
├── ansible.cfg
├── inventories/
│   ├── dev/hosts.ini
│   ├── stage/hosts.ini
│   └── prod/hosts.ini
├── playbooks/
│   ├── setup-nginx.yml
│   ├── setup-monitoring-agent.yml
│   └── deploy-app.yml
└── group_vars/
    ├── dev.yml
    ├── stage.yml
    └── prod.yml
```

Example command:

```bash
ansible-playbook -i inventories/prod/hosts.ini playbooks/setup-monitoring-agent.yml --limit web
```

---

## Enterprise structure

```text
ansible-platform/
├── inventories/
│   ├── aws_ec2.yml
│   ├── vmware.yml
│   └── cmdb_inventory.yml
├── playbooks/
│   ├── site.yml
│   ├── linux-baseline.yml
│   ├── monitoring-agents.yml
│   └── security-hardening.yml
├── roles/
│   ├── common/
│   ├── nginx/
│   ├── node_exporter/
│   └── docker/
├── group_vars/
├── host_vars/
├── collections/
└── ansible.cfg
```

Enterprise patterns:

```text
dynamic inventory
AWX / Ansible Automation Platform
RBAC
approvals
roles
collections
Vault integration
CI validation
audit logs
```

---

## When to use Ansible

Good use cases:

```text
Linux server configuration
packages
users and groups
files and templates
systemd services
monitoring agents
backup agents
VM and bare-metal automation
legacy app deployments
server bootstrap
```

Less ideal as primary tool:

```text
cloud infrastructure state management → Terraform
Kubernetes app deployment at scale → Helm / GitOps
secrets as source of truth → Vault / cloud secret manager
```

---

## Key lessons

```text
1. Ansible automates configuration on managed nodes.
2. Inventory describes target hosts and groups.
3. ansible.cfg defines defaults for the project.
4. Ad-hoc commands are useful for quick one-time actions.
5. Playbooks are reusable automation.
6. Modules are preferred over raw shell commands.
7. Facts expose system information to playbooks.
8. Idempotency is a core Ansible principle.
9. changed=0 on repeated runs is usually a good sign.
10. Ansible is often used with Terraform, Kubernetes, Helm, and GitOps.
```