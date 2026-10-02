# Design

The production target is classic Kubernetes Service discovery.

    CustomerApi
        -> dynamic proxy
        -> GrpcClientManager
        -> one long-lived channel/client
        -> customer-service:50051
        -> Kubernetes Service
        -> ready Pods

The proxy provides a stable application API and a transparent, bounded retry boundary. Retryable failures are retried on the existing client/channel.

The application does not manage EndpointSlices, Pod identities, endpoint health, client-side load balancing, instance exclusion, or channel replacement.

For the interim non-Kubernetes deployment, Spring supplies exactly one host and port. The same manager and proxy are used; only the configured target changes.

Default retryable statuses are UNAVAILABLE, DEADLINE_EXCEEDED, and RESOURCE_EXHAUSTED. Mutating RPCs require normal idempotency analysis before retries are enabled.

TLS belongs in channel construction and does not affect the application API.
