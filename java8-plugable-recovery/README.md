# Pluggable gRPC Recovery — Java 8

This folder is a reproducible reference implementation of one common
GrpcClientManager with pluggable environment-specific recovery policies.

Registry/instance discovery uses RegistryRecoveryPolicy and can replace the channel
after a retryable instance failure.

Normal Kubernetes Service discovery uses KubernetesRecoveryPolicy; it retains the
current channel for retryable statuses because the Service DNS target is stable and
does not identify a failed Pod.

Build:
    mvn test

No live Kubernetes cluster or registry is required.

Spring wiring selects the policy:
    new GrpcClientManager(discovery, new KubernetesRecoveryPolicy(), 100, maxAttempts)
or
    new GrpcClientManager(discovery, new RegistryRecoveryPolicy(), 100, maxAttempts)

The application-facing CustomerApi, OrderApi, etc. remain singleton interfaces backed
by dynamic proxies. The infrastructure is shared.

usePlaintext() is only for the self-contained example. Production TLS/mTLS should be
supplied through the channel configuration layer.
See design.md, agents.md, and example.md.
