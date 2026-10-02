# Pluggable gRPC Recovery Design

One reusable GrpcClientManager owns client/channel lifecycle. RecoveryPolicy owns
the environment-specific decision after a retryable gRPC failure.

RecoveryAction:
- RETRY_CURRENT: keep the active client/channel and make another bounded attempt.
- REPLACE_CLIENT: exclude the failed instance when possible, discover a new target,
  retire the old client, create a replacement, and retry.
- FAIL: propagate the original exception.

RegistryRecoveryPolicy uses REPLACE_CLIENT for retryable transport statuses.
KubernetesRecoveryPolicy uses RETRY_CURRENT for those statuses because a normal
Kubernetes Service is a stable logical target and does not identify a different Pod.

A future headless/EndpointSlice discovery implementation can expose Pod identity and
use a replacement policy when instance exclusion is appropriate.

Lifecycle remains ACTIVE -> RETIRING -> CLOSED. Operations acquire a lease before
calling the adapter and release it in finally. Cache eviction retires rather than
unconditionally shutting down a channel. Replacement is serialized per logical service
and only happens if the failed client is still active.

No Kubernetes API client, EndpointSlice watcher, pod-level load balancer, automatic
cross-service channel sharing, or hard-coded TLS policy is included.
