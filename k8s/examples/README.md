# Kubernetes Examples

This directory contains educational Kubernetes manifests.

These files are used to understand Kubernetes objects and should not be applied as part of the normal application deployment flow.

Normal application deployment manifests are located in:

```text
k8s/application-service/

Current examples:

pod.yaml

The raw Pod manifest is kept only for learning purposes. In production-like deployments, use Deployment instead of creating raw Pods directly.