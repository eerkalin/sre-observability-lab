# Ansible Vault and Secure Automation Runbook

## Purpose

This runbook explains how to manage secrets in Ansible using Ansible Vault.

It covers:

```text
Ansible Vault
encrypted variables
vault password file
vault.yml
group_vars structure
ansible-vault create/view/edit/encrypt/decrypt
encrypt_string
safe logging
no_log
file permissions
lab vs production secrets handling
```

---

## Why secrets need protection

Never store real secrets in plain text.

Examples of secrets:

```text
database passwords
API tokens
SSH private keys
service account credentials
monitoring agent tokens
cloud access keys
registry credentials
```

Bad pattern:

```yaml
db_password: "SuperSecretPassword123"
api_token: "real-production-token"
```

Risks:

```text
secret enters Git history
everyone with repo access can see it
secret can leak into CI logs
secret can be copied into backups
removing secrets from Git history is hard
```

---

## What Ansible Vault does

Ansible Vault encrypts files or variables.

Plain secret:

```yaml
demo_service_api_token: "local-demo-token-12345"
```

Encrypted Vault file:

```text
$ANSIBLE_VAULT;1.1;AES256
386566353734376237643361363436666...
```

Mental model:

```text
plain secret
  ↓ ansible-vault encrypt
encrypted file in Git
  ↓ ansible-playbook with vault password
decrypted in memory during playbook run
```

Important:

```text
Ansible Vault protects secrets in repository.
It is not a full replacement for HashiCorp Vault, AWS Secrets Manager, Azure Key Vault, GCP Secret Manager, or CyberArk.
```

---

## group_vars structure

Instead of one file:

```text
ansible/group_vars/local.yml
```

Use directory structure:

```text
ansible/group_vars/local/
├── vars.yml
└── vault.yml
```

Meaning:

```text
vars.yml
  non-sensitive variables

vault.yml
  encrypted sensitive variables
```

---

## Non-secret variables

File:

```text
ansible/group_vars/local/vars.yml
```

Content:

```yaml
---
demo_service_port: 7070
demo_service_log_level: WARN
demo_service_environment: group_vars_local
```

---

## Vault password file

Lab file:

```text
ansible/.vault_pass
```

Example content:

```text
local-vault-password
```

Set permissions:

```bash
chmod 600 ansible/.vault_pass
```

Never commit this file.

Add to `.gitignore`:

```gitignore
# Ansible Vault local password files
ansible/.vault_pass
*.vault_pass
.vault_pass
```

Check:

```bash
git check-ignore -v ansible/.vault_pass
```

Expected:

```text
ansible/.vault_pass is ignored
```

---

## Create encrypted vault.yml

From Ansible directory:

```bash
cd ~/Documents/GitHub/sre-observability-lab/ansible
```

Create encrypted file:

```bash
ansible-vault create group_vars/local/vault.yml --vault-password-file .vault_pass
```

Content:

```yaml
---
demo_service_api_token: "local-demo-token-12345"
demo_service_db_password: "local-demo-db-password"
```

Check raw file:

```bash
cat group_vars/local/vault.yml
```

Expected:

```text
$ANSIBLE_VAULT;1.1;AES256
...
```

Check that secret is not visible through grep:

```bash
grep -R "local-demo-token" group_vars/local || true
```

Expected:

```text
no output
```

---

## View encrypted file

Use:

```bash
ansible-vault view group_vars/local/vault.yml --vault-password-file .vault_pass
```

Expected decrypted content:

```yaml
---
demo_service_api_token: "local-demo-token-12345"
demo_service_db_password: "local-demo-db-password"
```

---

## Edit encrypted file

Use:

```bash
ansible-vault edit group_vars/local/vault.yml --vault-password-file .vault_pass
```

This is safer than decrypting the file to disk.

---

## Use secrets in role template

File:

```text
ansible/roles/demo_service/templates/demo-service.conf.j2
```

Example:

```jinja2
# Managed by Ansible role: demo_service
# Service: {{ demo_service_name }}

service_name={{ demo_service_name }}
service_port={{ demo_service_port }}
log_level={{ demo_service_log_level }}
environment={{ demo_service_environment }}
managed_by=ansible_role
api_token={{ demo_service_api_token }}
db_password={{ demo_service_db_password }}
```

Lab note:

```text
In this lab, secrets are rendered into a config file to prove that Vault variables work.
In production, control owner, group, mode, logs, and file access carefully.
```

---

## Protect rendered config permissions

File:

```text
ansible/roles/demo_service/tasks/main.yml
```

Template task:

```yaml
- name: Render service config from role template
  ansible.builtin.template:
    src: demo-service.conf.j2
    dest: "{{ demo_service_config_dir }}/demo-service.conf"
    mode: "0600"
  notify: Restart demo service
```

Why:

```text
If a config contains secrets, 0644 is too open.
0600 means only the owner can read and write.
```

On real Linux service automation, also define:

```yaml
owner: demo-service
group: demo-service
```

---

## Run without vault password

If `vault_password_file` is not configured and no vault password is provided:

```bash
ansible-playbook playbooks/demo-service-role.yml
```

Expected:

```text
Ansible fails because it cannot decrypt vault.yml.
```

Reason:

```text
Ansible sees an encrypted file but does not have the vault password.
```

---

## Run with vault password file

```bash
ansible-playbook playbooks/demo-service-role.yml --vault-password-file .vault_pass
```

Check rendered config:

```bash
cat /tmp/ansible-role-demo/config/demo-service.conf
ls -l /tmp/ansible-role-demo/config/demo-service.conf
```

Expected:

```text
api_token=local-demo-token-12345
db_password=local-demo-db-password
-rw-------
```

---

## Do not print secrets in debug

Bad pattern:

```yaml
- name: Print rendered service config
  ansible.builtin.debug:
    var: demo_service_rendered_config.stdout_lines
```

Problem:

```text
Secrets can leak to terminal output or CI logs.
```

Better pattern:

```yaml
- name: Check rendered service config exists
  ansible.builtin.stat:
    path: "{{ demo_service_config_dir }}/demo-service.conf"
  register: demo_service_config_stat

- name: Print rendered service config metadata
  ansible.builtin.debug:
    msg:
      - "Config exists: {{ demo_service_config_stat.stat.exists }}"
      - "Config mode: {{ demo_service_config_stat.stat.mode }}"
      - "Config size: {{ demo_service_config_stat.stat.size }}"
```

This shows metadata without exposing secret values.

---

## no_log

`no_log: true` hides task output.

Example:

```yaml
- name: Render service config from role template
  ansible.builtin.template:
    src: demo-service.conf.j2
    dest: "{{ demo_service_config_dir }}/demo-service.conf"
    mode: "0600"
  notify: Restart demo service
  no_log: true
```

Useful for:

```text
user creation with password
API calls with tokens
template/copy with sensitive content
database initialization
cloud credentials
```

Rule:

```text
Do not debug secrets.
Use no_log as a safety mechanism, not as a habit to print secrets safely.
```

Trade-off:

```text
no_log reduces diagnostic detail during failures.
```

---

## Encrypt existing file

Create temporary plain text file:

```bash
cat > group_vars/local/plain-secret.yml <<'EOF'
---
temporary_secret: "do-not-commit-plain-text"
EOF
```

Encrypt it:

```bash
ansible-vault encrypt group_vars/local/plain-secret.yml --vault-password-file .vault_pass
```

Check raw encrypted content:

```bash
cat group_vars/local/plain-secret.yml
```

View decrypted content:

```bash
ansible-vault view group_vars/local/plain-secret.yml --vault-password-file .vault_pass
```

Remove demo file:

```bash
rm group_vars/local/plain-secret.yml
```

---

## Decrypt risk

This command decrypts the file on disk:

```bash
ansible-vault decrypt group_vars/local/vault.yml --vault-password-file .vault_pass
```

Risk:

```text
vault.yml becomes plain text.
It can be accidentally committed.
```

Safer commands:

```bash
ansible-vault view group_vars/local/vault.yml --vault-password-file .vault_pass
ansible-vault edit group_vars/local/vault.yml --vault-password-file .vault_pass
```

If decrypted by mistake, encrypt again immediately:

```bash
ansible-vault encrypt group_vars/local/vault.yml --vault-password-file .vault_pass
```

---

## Encrypt string

Encrypt one variable value:

```bash
ansible-vault encrypt_string \
  --vault-password-file .vault_pass \
  'super-secret-value' \
  --name 'demo_service_single_secret'
```

Example output:

```yaml
demo_service_single_secret: !vault |
          $ANSIBLE_VAULT;1.1;AES256
          ...
```

Pros:

```text
ordinary and encrypted variables can live in one file
```

Cons:

```text
file becomes harder to read
bulk secret editing is less convenient
```

Course default pattern:

```text
separate encrypted vault.yml
```

---

## ansible.cfg vault_password_file

File:

```text
ansible/ansible.cfg
```

Example:

```ini
[defaults]
inventory = inventories/local/hosts.ini
host_key_checking = False
retry_files_enabled = False
stdout_callback = yaml
interpreter_python = auto_silent
vault_password_file = .vault_pass
```

Now this works:

```bash
ansible-playbook playbooks/demo-service-role.yml
```

without:

```bash
--vault-password-file .vault_pass
```

Important:

```text
ansible.cfg can be committed.
.vault_pass must not be committed.
```

---

## Lab pattern

```text
vault password file exists locally
vault.yml is encrypted
ansible.cfg points to vault_password_file
.gitignore excludes .vault_pass
secrets are not printed in debug output
```

---

## Small production pattern

```text
vault.yml encrypted in Git
vault password stored outside repository
CI/CD receives vault password from protected CI variable
CI creates temporary .vault_pass file during job
secrets are not printed in logs
sensitive tasks use no_log
```

Example CI logic:

```bash
echo "$ANSIBLE_VAULT_PASSWORD" > .vault_pass
chmod 600 .vault_pass
ansible-playbook playbooks/site.yml
rm .vault_pass
```

---

## Enterprise pattern

In enterprise, Ansible Vault is often not enough as the main secret backend.

Common tools:

```text
HashiCorp Vault
CyberArk
AWS Secrets Manager
Azure Key Vault
GCP Secret Manager
Ansible Automation Platform credentials
dynamic short-lived secrets
audit logs
rotation policies
RBAC
```

Better enterprise model:

```text
Git stores:
  roles
  playbooks
  non-secret variables
  references to secret paths

Secret manager stores:
  real secrets

Automation platform:
  injects credentials during run
```

---

## What to commit

Safe to commit:

```text
encrypted vault.yml
vars.yml with non-secret values
roles
playbooks
templates
runbooks
ansible.cfg
.gitignore
```

Do not commit:

```text
.vault_pass
plain text passwords
real API tokens
private keys
temporary decrypted vault files
CI-generated credential files
```

---

## Key lessons

```text
1. Do not store secrets in plain text.
2. Ansible Vault encrypts files and variables.
3. vault.yml can be committed only if encrypted.
4. vault password file must not be committed.
5. Use ansible-vault view/edit for encrypted files.
6. Avoid decrypting Vault files to disk.
7. Do not print secrets with debug.
8. Use stat/debug metadata instead of cat/debug content.
9. Use strict file permissions for rendered secret configs.
10. Use no_log for sensitive tasks.
11. Small production can use Vault with protected CI variables.
12. Enterprise environments usually use dedicated secret managers.
```