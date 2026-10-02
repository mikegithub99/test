# Simple Kubernetes-oriented gRPC client

Kubernetes-first design with one long-lived gRPC channel per logical service, bounded proxy-level retry, and a single static host/port option for the interim non-Kubernetes environment.
