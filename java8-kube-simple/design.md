# Design

The production target is classic Kubernetes Service discovery, not application-managed Pod discovery.

Architecture:

    CustomerApi -> dynamic proxy -> GrpcClientManager -> long-lived channel -> customer-service:50051 -> Kubernetes Service -> ready Pods

The proxy remains valuable because it provides a stable application API and a transparent, bounded retry boundary. Retryable failures are retried on the existing client/channel. There is no EndpointSlice watch, Pod identity, excluded-instance list, recovery-policy hierarchy, or channel replacement.

For the interim non-Kubernetes deployment, Spring supplies exactly one host and port. The same manager and proxy are used; only the configured target changes.

The manager caches one client per logical service and closes channels on shutdown. The endpoint provider is deliberately configuration-oriented rather than a discovery subsystem.

Default retryable gRPC statuses: UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED. Mutating RPCs require normal idempotency/duplicate-execution analysis before enabling retries.

TLS should be introduced in channel construction and should not affect the application API.