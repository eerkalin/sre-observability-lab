# Week 1, Day 5 — Internal Networking and HTTP Diagnostics

## Infrastructure

- Client instance: sre-lab-client-01
- App instance: sre-lab-app-01
- Client Security Group: sre-lab-client-sg
- App Security Group: sre-lab-app-sg
- VPC: sre-lab-vpc
- Subnet: sre-lab-public-a

## Access model

- SSH was not used
- Session Manager was used
- No inbound SSH rules were created
- HTTP was not opened to the internet

## Network lab

- Nginx installed on app instance
- Local HTTP on app verified
- Initial client-to-app request timed out
- App Security Group blocked TCP/80
- TCP/80 was allowed from client Security Group
- Client-to-app HTTP returned 200
- Nginx was stopped to reproduce connection refused
- DNS resolution via private DNS was tested
- tcpdump was used to observe TCP traffic
- Nginx access logs were reviewed

## Key lessons

- Timeout usually points to network path or firewall
- Connection refused means host reachable but port closed
- HTTP 404 means application responded but path is wrong
- Security Group source can be another Security Group
- Local service success does not prove network reachability
- tcpdump and access logs provide evidence at different layers

## Cleanup

- Client EC2 instance terminated
- App EC2 instance terminated
- Root EBS volumes deleted
- No Elastic IP created
- Inbound HTTP rule reviewed and removed if not needed
