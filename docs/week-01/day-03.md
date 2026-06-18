# Week 1, Day 3 — VPC, EC2 and Linux

## Network

- VPC: sre-lab-vpc
- VPC CIDR: 10.20.0.0/16
- Public subnet: sre-lab-public-a
- Subnet CIDR: 10.20.1.0/24
- Internet Gateway attached
- Public route table associated
- Route 0.0.0.0/0 configured

## Security

- Security Group: sre-lab-web-sg
- SSH port was not opened
- Session Manager was used
- Temporary HTTP rule was restricted to My IP
- HTTP rule was removed after testing

## EC2

- Amazon Linux 2023
- Instance type: t3.micro
- IAM role: SRELabEC2SSMRole
- IMDSv2 required
- SSM Agent verified
- Instance terminated after the lab

## Linux checks

- OS and kernel reviewed
- CPU, memory and storage reviewed
- IP addresses and routes reviewed
- Nginx installed and started
- Listening port verified
- Local and external HTTP tested
- Logs reviewed

## Incident drill

- Local service success with external network failure reproduced
- Security Group identified as the blocking layer
- HTTP access restored and removed safely

## Cost control

- NAT Gateway was not created
- Elastic IP was not created
- EC2 instance was terminated
- Root EBS volume was deleted
- Credit balance checked
