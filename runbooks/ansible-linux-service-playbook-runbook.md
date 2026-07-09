# Ansible Linux Service Playbook Runbook

## Purpose

This runbook explains how to automate Linux service configuration with Ansible.

It covers:

```text
directories
static files
templates
variables
handlers
service restart logic
idempotency
lab vs real Linux service
production structures
```

---

## Service automation mental model

Typical Linux service automation:

```text
install package
  ↓
create user/group
  ↓
create directories
  ↓
deploy static files
  ↓
render config templates
  ↓
restart service only if config changed
  ↓
ensure service is enabled and running
```

Important principle:

```text
Do not restart services on every playbook run.
Restart only when configuration changed.
```

---

## Handler

A handler is a special task triggered by `notify`.

Flow:

```text
template task changed
  ↓
notify handler
  ↓
handler runs at the end of the play
```

If the template does not change:

```text
template task changed=false
  ↓
handler does not run
```

---

## Lab structure

```text
ansible/
├── ansible.cfg
├── inventories/
│   └── local/
│       └── hosts.ini
├── playbooks/
│   ├── local-basics.yml
│   └── linux-service-demo.yml
├── templates/
│   └── demo-service.conf.j2
└── files/
    └── welcome.txt
```

---

## Static file

File:

```text
ansible/files/welcome.txt
```

Content:

```text
Welcome to Ansible managed service demo.
This file is managed by Ansible.
Do not edit manually.
```

Static files are copied without templating.

---

## Template

File:

```text
ansible/templates/demo-service.conf.j2
```

Content:

```jinja2
# Managed by Ansible
# Service: {{ service_name }}

service_name={{ service_name }}
service_port={{ service_port }}
log_level={{ log_level }}
environment={{ environment_name }}
managed_by=ansible
```

Variables:

```text
service_name
service_port
log_level
environment_name
```

---

## Playbook

File:

```text
ansible/playbooks/linux-service-demo.yml
```

Content:

```yaml
---
- name: Linux service automation demo
  hosts: local
  gather_facts: true

  vars:
    service_name: demo-service
    service_port: 8080
    log_level: INFO
    environment_name: local
    service_base_dir: /tmp/ansible-service-demo
    service_config_dir: /tmp/ansible-service-demo/config
    service_log_dir: /tmp/ansible-service-demo/logs
    service_runtime_dir: /tmp/ansible-service-demo/runtime

  tasks:
    - name: Ensure service base directory exists
      ansible.builtin.file:
        path: "{{ service_base_dir }}"
        state: directory
        mode: "0755"

    - name: Ensure service config directory exists
      ansible.builtin.file:
        path: "{{ service_config_dir }}"
        state: directory
        mode: "0755"

    - name: Ensure service log directory exists
      ansible.builtin.file:
        path: "{{ service_log_dir }}"
        state: directory
        mode: "0755"

    - name: Ensure service runtime directory exists
      ansible.builtin.file:
        path: "{{ service_runtime_dir }}"
        state: directory
        mode: "0755"

    - name: Copy welcome file
      ansible.builtin.copy:
        src: ../files/welcome.txt
        dest: "{{ service_base_dir }}/welcome.txt"
        mode: "0644"

    - name: Render service config from template
      ansible.builtin.template:
        src: ../templates/demo-service.conf.j2
        dest: "{{ service_config_dir }}/demo-service.conf"
        mode: "0644"
      notify: Restart demo service

    - name: Ensure service state file exists
      ansible.builtin.copy:
        dest: "{{ service_runtime_dir }}/service.status"
        content: |
          service={{ service_name }}
          state=running
        mode: "0644"
        force: false

    - name: Show rendered config
      ansible.builtin.command:
        cmd: cat "{{ service_config_dir }}/demo-service.conf"
      register: rendered_config
      changed_when: false

    - name: Print rendered config
      ansible.builtin.debug:
        var: rendered_config.stdout_lines

  handlers:
    - name: Restart demo service
      ansible.builtin.copy:
        dest: "{{ service_runtime_dir }}/last_restart.txt"
        content: |
          service={{ service_name }}
          restarted_at={{ ansible_date_time.iso8601 }}
          reason=config_changed
        mode: "0644"
```

---

## Important fields

### vars

```yaml
vars:
  service_name: demo-service
  service_port: 8080
  log_level: INFO
```

Variables can be used in tasks and templates:

```yaml
"{{ service_name }}"
```

---

### file module

```yaml
ansible.builtin.file:
  path: "{{ service_config_dir }}"
  state: directory
  mode: "0755"
```

Ensures:

```text
directory exists
permissions are correct
```

---

### copy module

```yaml
ansible.builtin.copy:
  src: ../files/welcome.txt
  dest: "{{ service_base_dir }}/welcome.txt"
  mode: "0644"
```

Copies a static file.

---

### template module

```yaml
ansible.builtin.template:
  src: ../templates/demo-service.conf.j2
  dest: "{{ service_config_dir }}/demo-service.conf"
  mode: "0644"
notify: Restart demo service
```

Renders a Jinja2 template.

If rendered content changes, it notifies the handler.

---

### force: false

```yaml
force: false
```

Means:

```text
create file if missing
do not overwrite if it already exists
```

Useful for runtime/bootstrap files.

---

### changed_when: false

```yaml
changed_when: false
```

Means:

```text
this task is informational and should not count as changed
```

Useful for commands like `cat`, `ps`, `systemctl status`, `curl`, or checks.

---

## Run playbook

```bash
cd ~/Documents/GitHub/sre-observability-lab/ansible
ansible-playbook playbooks/linux-service-demo.yml
```

Check files:

```bash
ls -R /tmp/ansible-service-demo
cat /tmp/ansible-service-demo/config/demo-service.conf
cat /tmp/ansible-service-demo/runtime/last_restart.txt
```

---

## Idempotency check

Run again:

```bash
ansible-playbook playbooks/linux-service-demo.yml
```

Expected:

```text
No unnecessary changed tasks.
Handler should not run if config did not change.
```

Operational rule:

```text
A good service playbook should not restart the service without a real config change.
```

---

## Handler check

Change in playbook:

```yaml
log_level: INFO
```

to:

```yaml
log_level: DEBUG
```

Run:

```bash
ansible-playbook playbooks/linux-service-demo.yml
```

Expected:

```text
template task changed
handler Restart demo service runs
last_restart.txt updated
```

Run again:

```bash
ansible-playbook playbooks/linux-service-demo.yml
```

Expected:

```text
changed=0 or no unnecessary changed tasks
handler does not run again
```

---

## Real Linux service example

Example for nginx:

```yaml
---
- name: Configure nginx service
  hosts: web
  become: true

  tasks:
    - name: Install nginx
      ansible.builtin.package:
        name: nginx
        state: present

    - name: Deploy nginx config
      ansible.builtin.template:
        src: nginx.conf.j2
        dest: /etc/nginx/nginx.conf
        mode: "0644"
      notify: Restart nginx

    - name: Ensure nginx is enabled and running
      ansible.builtin.service:
        name: nginx
        state: started
        enabled: true

  handlers:
    - name: Restart nginx
      ansible.builtin.service:
        name: nginx
        state: restarted
```

Important fields:

```text
become: true
  run with sudo/root privileges

package
  install package

service
  manage systemd/init service

enabled: true
  enable service on boot

notify
  restart only when config changed
```

---

## Why modules are better than shell

Bad pattern:

```yaml
- name: Install nginx badly
  ansible.builtin.shell: apt install nginx -y
```

Problems:

```text
not portable
poor idempotency
harder error handling
OS-specific
harder to reason about changed state
```

Better:

```yaml
ansible.builtin.package:
  name: nginx
  state: present
```

---

## Lab vs production

### Lab

```text
localhost
/tmp/ansible-service-demo
template config
handler simulation
```

### Small production

```text
ansible/
├── inventories/
│   ├── dev/hosts.ini
│   └── prod/hosts.ini
├── playbooks/
│   ├── configure-nginx.yml
│   └── install-monitoring-agent.yml
├── templates/
│   └── nginx.conf.j2
└── group_vars/
    ├── dev.yml
    └── prod.yml
```

### Enterprise

```text
roles
collections
dynamic inventory
AWX / Ansible Automation Platform
Vault integration
approvals
CI lint/test
separate environment inventories
change records
audit logs
```

Example:

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
│   └── promtail/
├── group_vars/
├── host_vars/
└── collections/
```

---

## Real DevOps use cases

```text
install node_exporter on Linux servers
deploy promtail config
install Docker on VMs
create service users
configure nginx reverse proxy
manage SSH keys
apply package baseline
restart service after config change
prepare server for GitLab Runner
deploy backup scripts
```

---

## Key lessons

```text
1. Service automation usually includes packages, users, directories, files, templates, and services.
2. Handlers run only when notified by changed tasks.
3. Config changes should trigger service restart.
4. No config change means no restart.
5. Templates use Jinja2 variables.
6. Static files use copy.
7. Directories and permissions use file.
8. force: false prevents overwriting existing files.
9. changed_when: false keeps informational tasks clean.
10. Modules are preferred over shell for idempotency and portability.
11. Real Linux services use package and service modules.
12. Production Ansible usually uses inventories, group_vars, roles, Vault, CI, and approvals.
```