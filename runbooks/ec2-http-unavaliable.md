# Runbook: EC2 HTTP service is unavailable

## Symptoms

- Public URL does not respond
- Connection timeout
- Connection refused
- HTTP 5xx response

## Initial checks

1. Verify EC2 instance state.
2. Verify EC2 status checks.
3. Connect through Session Manager.
4. Check the application service.
5. Check the listening port.
6. Test the application locally.
7. Check the Security Group.
8. Check public IP assignment.
9. Check the subnet route table.
10. Check the Network ACL.

## Commands

```bash
sudo systemctl status nginx --no-pager
sudo ss -lntp | grep ':80'
curl -I http://localhost
sudo journalctl -u nginx -n 30 --no-pager
sudo tail -n 20 /var/log/nginx/access.log
sudo tail -n 20 /var/log/nginx/error.log
