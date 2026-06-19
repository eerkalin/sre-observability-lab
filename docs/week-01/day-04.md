## Environment

- Amazon Linux 2023
- EC2 instance accessed through Session Manager
- SSH was not enabled
- No inbound Security Group rules were required

## Processes

- Background process created
- SIGTERM tested
- SIGKILL tested
- Graceful and forced termination compared

## systemd

- System user `srelab` created
- Custom worker service created
- Automatic restart tested
- Broken ExecStart reproduced
- status 203/EXEC investigated

## Logging

- systemd journal reviewed
- Service logs queried by unit
- Recent boot errors reviewed
- Journal disk usage checked

## Permissions

- Dedicated service directory created
- Ownership and mode 0750 configured
- Authorized write tested
- Permission denied reproduced
- `stat` and `namei` used

## Storage incident

- Isolated loop filesystem created
- No-space-left-on-device reproduced safely
- Block usage and inode usage compared
- Deleted but open file reproduced
- `lsof +L1` used to identify hidden disk usage
- Temporary filesystem removed

## Operational practices

- Diagnostic evidence collected before restart
- Linux health-check script created
- Service incident runbook created

## Cleanup

- EC2 instance terminated after the lab
- Root EBS volume configured for deletion
- No Elastic IP created
EOF
