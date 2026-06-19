# Runbook: Internal HTTP service is unavailable

## Symptoms

- Client cannot reach internal service
- curl returns timeout
- curl returns connection refused
- HTTP response is 404, 5xx or empty

## First question

Is this a network problem or an application problem?

## Checks from client

```bash
nc -vz -w 5 TARGET_IP 80
curl -v --connect-timeout 5 http://TARGET_IP
getent hosts TARGET_DNS


##Check from application host

sudo ss -lntp | grep ':80'
curl -I http://localhost
sudo systemctl status nginx --no-pager
sudo journalctl -u nginx -n 50 --no-pager
sudo tail -n 20 /var/log/nginx/access.log
sudo tail -n 20 /var/log/nginx/error.log
sudo timeout 15 tcpdump -ni any "tcp port 80"
