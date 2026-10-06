# java8-kube-min2

Minimal Spring Boot / Java 8 gRPC client pattern for Kubernetes and a simple
non-Kubernetes deployment.

## Design

- The application talks to a stable business interface: CustomerApi.
- CustomerApiAdapter is the explicit adapter to the generated gRPC stub.
- GrpcClientManager is a Spring singleton and keeps one long-lived gRPC client
  per logical service.
- Kubernetes uses normal Service DNS, for example customer-service:50051.
- Kubernetes selects the backend Pod; the Java application does not discover
  or cache Pod IPs.
- Interim non-Kubernetes mode uses one configured host and port.
- Retries are bounded by max-attempts and reuse the same gRPC channel.
- Retryable statuses are UNAVAILABLE, DEADLINE_EXCEEDED, and RESOURCE_EXHAUSTED.
- Mutating RPCs should only be retried when duplicate execution is safe or an
  idempotency mechanism is available.

## Classes

- CustomerApi.java - stable application-facing interface.
- CustomerApiAdapter.java - maps the application call to the generated gRPC
  blocking stub and applies bounded retry.
- GrpcClientManager.java - creates and caches long-lived gRPC clients by
  logical service name.
- ServiceEndpoint.java - host/port value object.
- ServiceEndpointProvider.java - endpoint lookup abstraction.
- StaticServiceEndpointProvider.java - simple one-host/one-port implementation
  for the interim non-Kubernetes case.
- GrpcClientConfiguration.java - Spring bean wiring and sample properties.

The generated protobuf classes CustomerServiceGrpc, Customer, and
GetCustomerRequest are expected to come from the project's .proto build.

## Kubernetes configuration

Use the Kubernetes Service DNS name:

  grpc.customer.host=customer-service
  grpc.customer.port=50051

A fully qualified service name also works, for example:

  customer-service.default.svc.cluster.local

## Non-Kubernetes configuration

Point the same client at one fixed server:

  grpc.customer.host=server-a
  grpc.customer.port=50051

No registry or endpoint discovery component is required.

## Notes

The sample uses plaintext gRPC to keep the example minimal. Add TLS configuration
to the ManagedChannelBuilder when the deployment requires encrypted transport.

The gRPC ManagedChannel is intentionally long-lived and reusable. Do not create a
new channel for every request.
