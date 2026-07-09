# Ansible Roles and Variables Runbook

## Purpose

This runbook explains how to structure Ansible automation with roles and variables.

It covers:

```text
roles
defaults
tasks
templates
files
handlers
group_vars
host_vars
extra-vars
variable precedence
lab vs production role structure
```

---

## Why roles

A role is a reusable unit of Ansible automation.

Instead of putting all tasks, templates, files, handlers, and variables into one large playbook, roles provide structure:

```text
playbook
  ↓
role
  ↓
tasks / templates / files / handlers / defaults
```

Examples of real roles:

```text
common
nginx
node_exporter
promtail
docker
gitlab_runner
backup_agent
security_baseline
```

---

## Role structure

Demo role:

```text
ansible/roles/demo_service/
├── defaults/
│   └── main.yml
├── files/
│   └── welcome.txt
├── handlers/
│   └── main.yml
├── tasks/
│   └── main.yml
└── templates/
    └── demo-service.conf.j2
```

Meaning:

```text
defaults/main.yml
  default role variables

tasks/main.yml
  main role tasks

templates/
  Jinja2 templates

files/
  static files

handlers/main.yml
  handlers triggered by notify
```

---

## Create role directories

```bash
mkdir -p ansible/roles/demo_service/defaults
mkdir -p ansible/roles/demo_service/tasks
mkdir -p ansible/roles/demo_service/templates
mkdir -p ansible/roles/demo_service/files
mkdir -p ansible/roles/demo_service/handlers
```

Check:

```bash
find ansible/roles/demo_service -maxdepth 2 -type d
```

---

## Role defaults

File:

```text
ansible/roles/demo_service/defaults/main.yml
```

Content:

```yaml
---
demo_service_name: demo-service
demo_service_port: 8080
demo_service_log_level: INFO
demo_service_environment: local

demo_service_base_dir: /tmp/ansible-role-demo
demo_service_config_dir: /tmp/ansible-role-demo/config
demo_service_log_dir: /tmp/ansible-role-demo/logs
demo_service_runtime_dir: /tmp/ansible-role-demo/runtime
```

Important:

```text
Role defaults are weak variables.
They are easy to override from group_vars, host_vars, playbook vars, or extra-vars.
```

Production rule:

```text
Prefix role variables with the role name.
```

Good:

```text
demo_service_port
demo_service_log_level
```

Bad:

```text
port
log_level
```

Reason:

```text
Generic variable names can collide with variables from other roles.
```

---

## Static role file

File:

```text
ansible/roles/demo_service/files/welcome.txt
```

Content:

```text
Welcome to Ansible role managed service demo.
This file is managed by Ansible role demo_service.
Do not edit manually.
```

Inside a role, `copy` can use:

```yaml
src: welcome.txt
```

Ansible automatically looks in:

```text
roles/demo_service/files/
```

---

## Role template

File:

```text
ansible/roles/demo_service/templates/demo-service.conf.j2
```

Content:

```jinja2
# Managed by Ansible role: demo_service
# Service: {{ demo_service_name }}

service_name={{ demo_service_name }}
service_port={{ demo_service_port }}
log_level={{ demo_service_log_level }}
environment={{ demo_service_environment }}
managed_by=ansible_role
```

Inside a role, `template` can use:

```yaml
src: demo-service.conf.j2
```

Ansible automatically looks in:

```text
roles/demo_service/templates/
```

---

## Role handler

File:

```text
ansible/roles/demo_service/handlers/main.yml
```

Content:

```yaml
---
- name: Restart demo service
  ansible.builtin.copy:
    dest: "{{ demo_service_runtime_dir }}/last_restart.txt"
    content: |
      service={{ demo_service_name }}
      restarted_at={{ ansible_date_time.iso8601 }}
      reason=config_changed
      managed_by=demo_service_role
    mode: "0644"
```

Meaning:

```text
The handler simulates service restart by writing last_restart.txt.
In real Linux service automation, this would usually use ansible.builtin.service or ansible.builtin.systemd.
```

---

## Role tasks

File:

```text
ansible/roles/demo_service/tasks/main.yml
```

Content:

```yaml
---
- name: Ensure service base directory exists
  ansible.builtin.file:
    path: "{{ demo_service_base_dir }}"
    state: directory
    mode: "0755"

- name: Ensure service config directory exists
  ansible.builtin.file:
    path: "{{ demo_service_config_dir }}"
    state: directory
    mode: "0755"

- name: Ensure service log directory exists
  ansible.builtin.file:
    path: "{{ demo_service_log_dir }}"
    state: directory
    mode: "0755"

- name: Ensure service runtime directory exists
  ansible.builtin.file:
    path: "{{ demo_service_runtime_dir }}"
    state: directory
    mode: "0755"

- name: Copy welcome file
  ansible.builtin.copy:
    src: welcome.txt
    dest: "{{ demo_service_base_dir }}/welcome.txt"
    mode: "0644"

- name: Render service config from role template
  ansible.builtin.template:
    src: demo-service.conf.j2
    dest: "{{ demo_service_config_dir }}/demo-service.conf"
    mode: "0644"
  notify: Restart demo service

- name: Ensure service state file exists
  ansible.builtin.copy:
    dest: "{{ demo_service_runtime_dir }}/service.status"
    content: |
      service={{ demo_service_name }}
      state=running
    mode: "0644"
    force: false

- name: Show rendered service config
  ansible.builtin.command:
    cmd: cat "{{ demo_service_config_dir }}/demo-service.conf"
  register: demo_service_rendered_config
  changed_when: false

- name: Print rendered service config
  ansible.builtin.debug:
    var: demo_service_rendered_config.stdout_lines
```

Important details:

```text
copy src: welcome.txt
  uses role files/ directory

template src: demo-service.conf.j2
  uses role templates/ directory

notify: Restart demo service
  calls handler only when rendered config changes

changed_when: false
  keeps read-only command task from reporting changed
```

---

## Playbook using role

File:

```text
ansible/playbooks/demo-service-role.yml
```

Basic version:

```yaml
---
- name: Configure demo service through Ansible role
  hosts: local
  gather_facts: true

  roles:
    - role: demo_service
```

Run:

```bash
cd ~/Documents/GitHub/sre-observability-lab/ansible
ansible-playbook playbooks/demo-service-role.yml
```

Check:

```bash
ls -R /tmp/ansible-role-demo
cat /tmp/ansible-role-demo/config/demo-service.conf
cat /tmp/ansible-role-demo/runtime/last_restart.txt
```

Run again:

```bash
ansible-playbook playbooks/demo-service-role.yml
```

Expected:

```text
No unnecessary changed tasks.
Handler does not run again if config did not change.
```

---

## Override variables from playbook vars

Example:

```yaml
---
- name: Configure demo service through Ansible role
  hosts: local
  gather_facts: true

  vars:
    demo_service_port: 9090
    demo_service_log_level: DEBUG
    demo_service_environment: dev

  roles:
    - role: demo_service
```

Expected config:

```text
service_port=9090
log_level=DEBUG
environment=dev
```

Meaning:

```text
Playbook vars override role defaults.
```

---

## group_vars

Create:

```bash
mkdir -p ansible/group_vars
```

File:

```text
ansible/group_vars/local.yml
```

Content:

```yaml
---
demo_service_port: 7070
demo_service_log_level: WARN
demo_service_environment: group_vars_local
```

Why this works:

```text
Inventory group name is local.
Ansible automatically loads group_vars/local.yml for hosts in that group.
```

Inventory:

```ini
[local]
localhost ansible_connection=local
```

Expected config:

```text
service_port=7070
log_level=WARN
environment=group_vars_local
```

---

## host_vars

Create:

```bash
mkdir -p ansible/host_vars
```

File:

```text
ansible/host_vars/localhost.yml
```

Content:

```yaml
---
demo_service_log_level: ERROR
```

Expected config:

```text
service_port=7070
log_level=ERROR
environment=group_vars_local
```

Meaning:

```text
host_vars/localhost.yml overrides group_vars/local.yml for this specific host.
```

Practical rule:

```text
group_vars = settings for a group of hosts
host_vars = exceptions for one host
```

---

## extra-vars

Run:

```bash
ansible-playbook playbooks/demo-service-role.yml \
  -e demo_service_log_level=TRACE
```

Expected:

```text
log_level=TRACE
```

Warning:

```text
extra-vars are powerful and high-priority.
Do not turn production automation into a pile of -e parameters.
Use group_vars, host_vars, inventories, and roles for normal structure.
```

---

## Simplified variable precedence

Simplified order:

```text
role defaults
  ↓
group_vars
  ↓
host_vars
  ↓
playbook vars
  ↓
extra-vars
```

Lower means stronger.

Note:

```text
Real Ansible variable precedence has more levels.
This simplified model is enough for the current course stage.
```

---

## Lab structure

```text
ansible/
├── ansible.cfg
├── inventories/
│   └── local/
│       └── hosts.ini
├── group_vars/
│   └── local.yml
├── host_vars/
│   └── localhost.yml
├── playbooks/
│   └── demo-service-role.yml
└── roles/
    └── demo_service/
        ├── defaults/
        ├── files/
        ├── handlers/
        ├── tasks/
        └── templates/
```

---

## Small production structure

```text
ansible/
├── ansible.cfg
├── inventories/
│   ├── dev/hosts.ini
│   ├── stage/hosts.ini
│   └── prod/hosts.ini
├── group_vars/
│   ├── all.yml
│   ├── dev.yml
│   ├── stage.yml
│   ├── prod.yml
│   └── web.yml
├── host_vars/
│   └── web-01.yml
├── playbooks/
│   └── web.yml
└── roles/
    ├── common/
    ├── nginx/
    └── node_exporter/
```

Playbook:

```yaml
---
- name: Configure web servers
  hosts: web
  become: true

  roles:
    - common
    - nginx
    - node_exporter
```

Inventory example:

```ini
[web]
web-01 ansible_host=10.10.10.11
web-02 ansible_host=10.10.10.12

[dev]
web-01

[prod]
web-02
```

---

## Enterprise structure

```text
ansible-platform/
├── inventories/
│   ├── aws_ec2.yml
│   ├── vmware.yml
│   └── cmdb.yml
├── playbooks/
│   ├── site.yml
│   ├── linux-baseline.yml
│   ├── web.yml
│   └── monitoring.yml
├── roles/
│   ├── common/
│   ├── nginx/
│   ├── node_exporter/
│   ├── promtail/
│   └── gitlab_runner/
├── group_vars/
│   ├── all.yml
│   ├── dev.yml
│   ├── stage.yml
│   ├── prod.yml
│   ├── web.yml
│   └── monitoring.yml
├── host_vars/
└── collections/
```

Enterprise patterns:

```text
versioned roles
roles in separate repos or collections
dynamic inventory from cloud/CMDB
AWX / Ansible Automation Platform
RBAC and approvals
Vault / Ansible Vault
CI with ansible-lint and syntax-check
auditable executions
host_vars only for exceptions
```

---

## Practical rules

```text
1. Put reusable logic in roles.
2. Put weak defaults in role defaults.
3. Use role-name prefixes for variables.
4. Use group_vars for environment/group settings.
5. Use host_vars for host-specific exceptions.
6. Use extra-vars carefully.
7. Keep playbooks small; let roles do the work.
8. Use handlers for restart/reload logic.
9. Keep roles idempotent.
10. Avoid raw shell when modules exist.
```

---

## Key lessons

```text
1. Roles make Ansible automation reusable and maintainable.
2. Role directories have standard meanings.
3. defaults/main.yml provides weak default values.
4. tasks/main.yml contains role logic.
5. handlers/main.yml contains notify targets.
6. templates/ and files/ are automatically searched inside roles.
7. group_vars are loaded by inventory group name.
8. host_vars are loaded by hostname.
9. Variable precedence controls which value wins.
10. Production Ansible relies heavily on roles, inventories, group_vars, Vault, CI, and controlled execution.
```