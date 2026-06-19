#!/usr/bin/env bash

set -u

echo "=== TIME ==="
date -u

echo
echo "=== HOST ==="
hostnamectl

echo
echo "=== UPTIME AND LOAD ==="
uptime

echo
echo "=== MEMORY ==="
free -h

echo
echo "=== FILESYSTEMS ==="
df -hT

echo
echo "=== INODES ==="
df -i

echo
echo "=== FAILED SYSTEMD UNITS ==="
systemctl --failed --no-pager

echo
echo "=== TOP CPU PROCESSES ==="
ps -eo pid,ppid,user,stat,%cpu,%mem,etime,cmd \
  --sort=-%cpu | head -n 10

echo
echo "=== TOP MEMORY PROCESSES ==="
ps -eo pid,ppid,user,stat,%cpu,%mem,etime,cmd \
  --sort=-%mem | head -n 10

echo
echo "=== LISTENING TCP PORTS ==="
ss -lntp

echo
echo "=== RECENT SYSTEM ERRORS ==="
journalctl -p err -b --no-pager | tail -n 20
EOF
