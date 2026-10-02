# Guidance

1. Prefer Kubernetes Service DNS over Pod/EndpointSlice discovery.
2. Keep one long-lived client/channel per logical service.
3. Keep the application-facing API separate from generated gRPC stubs.
4. Keep retry bounded and limited to explicitly retryable statuses.
5. Do not add instance IDs, excluded endpoints, recovery policies, or channel replacement unless requirements change to endpoint-level discovery.
6. Interim non-Kubernetes support is one configured host and port.
7. Keep network target configuration independent of protobuf/stub Java types.
8. Add TLS as channel infrastructure, not application API behavior.