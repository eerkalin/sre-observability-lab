# Week 1, Day 2 — IAM Roles and Temporary Credentials

## Existing identities

- IAM user: yergali-lab
- IAM group: sre-lab-admins
- User MFA enabled
- No permanent access keys

## Access migration

- Direct AdministratorAccess removed from the group
- SignInLocalDevelopmentAccess attached
- AssumeSRELabRoles policy created

## Roles

- SRELabViewOnlyRole
- SRELabAdministratorRole
- MFA required by both trust policies

## AWS CLI profiles

- sre-lab-user
- sre-lab-view
- sre-lab-admin

## Permission validation

- Base user cannot describe EC2 instances
- ViewOnly role can describe EC2 instances
- ViewOnly role cannot run EC2 instances
- Administrator role passes EC2 dry-run
- No EC2 instance was created

## Audit

- CloudTrail Event history reviewed
- AssumeRole event reviewed
- IAM policy changes reviewed

## Chargeable resources

- EC2 instances: 0
- EBS volumes: 0
- RDS databases: 0
- EKS clusters: 0
- Load balancers: 0
- NAT gateways: 0
