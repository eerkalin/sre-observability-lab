# Runbook: Linux service is unhealthy

## Symptoms

- Service is not responding
- systemd unit is failed
- Process is missing
- Host is slow
- Disk is full
- Permission denied errors

## Initial checks

1. Confirm the host and current time.
2. Check uptime and load average.
3. Check available memory.
4. Check filesystem usage.
5. Check inode usage.
6. Check failed systemd units.
7. Check service status.
8. Review service journal.
9. Check process and listening ports.
10. Check file ownership and permissions.
11. Check deleted but open files.

## Commands

```bash
uptime
free -h
df -hT
df -i
systemctl --failed --no-pager
systemctl status SERVICE --no-pager
journalctl -u SERVICE -n 100 --no-pager
ps -ef
ss -lntp
stat PATH
namei -l PATH
lsof +L1
