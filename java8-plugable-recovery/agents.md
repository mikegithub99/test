# Agent Guidance

Use java8-1 as the behavioral baseline. Preserve existing public names and contracts
where practical. Do not rename or redesign unrelated infrastructure.

Keep one reusable GrpcClientManager. Put environment-specific failure decisions in
RecoveryPolicy. Do not add Kubernetes-vs-registry branches to the manager.

Required semantics:
1. Never shut down a channel while a leased operation is in flight.
2. Only the currently active client may be replaced.
3. Replacement is serialized per logical service.
4. Retry attempts are bounded.
5. Non-retryable statuses propagate.
6. Registry recovery can exclude failed instance IDs.
7. Normal Kubernetes Service recovery does not assume Pod identity.
8. Headless/EndpointSlice discovery may use instance exclusion later.
9. Do not introduce automatic channel sharing across unrelated logical services.
10. Keep Java 8 compatibility.

Read this file and design.md before changing code. Inspect current source first.
Make the smallest coherent change, add focused tests, and update reproduction docs.
