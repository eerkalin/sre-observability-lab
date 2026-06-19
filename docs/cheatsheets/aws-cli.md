# AWS CLI Cheatsheet

## Identity

```bash
aws sts get-caller-identity --profile sre-lab-admin
aws sts get-caller-identity --profile sre-lab-view

## Profile
aws configure list-profiles
aws configure list --profile sre-lab-admin
aws configure get region --profile sre-lab-admin
aws configure get role_arn --profile sre-lab-admin

## ec2 Instances
aws ec2 describe-instances \
  --profile sre-lab-view \
  --region eu-central-1 \
  --query 'Reservations[].Instances[].{Name:Tags[?Key==`Name`]|[0].Value,State:State.Name,PrivateIP:PrivateIpAddress,PublicIP:PublicIpAddress}' \
  --output table

## CloudTrail lookup

aws cloudtrail lookup-events \
  --profile sre-lab-admin \
  --region eu-central-1 \
  --lookup-attributes AttributeKey=EventName,AttributeValue=AuthorizeSecurityGroupIngress \
  --max-results 10 \
  --output table


