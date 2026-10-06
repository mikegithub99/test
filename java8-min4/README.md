# java8-min4

Minimal Spring Boot / Java 8 gRPC client pattern for Kubernetes and a simple
non-Kubernetes deployment.

## Design

- The application talks to a stable business interface: `CustomerApi`.
- `CustomerAdapterGrpc` is the explicit adapter to the generated gRPC stub.
- `AdapterGrpcBase<T>` owns the long-lived gRPC channel/stub for the adapter and
  contains the common bounded retry logic.
- There is no `GrpcClientManager` or application-managed client cache.
- Spring creates the adapter as a singleton, so its channel/stub is naturally
  long-lived and reused across concurrent calls.
- Kubernetes uses normal Service DNS, for example `customer-service:50051`.
- Kubernetes selects the backend Pod; the Java application does not discover
  or cache Pod IPs.
- Interim non-Kubernetes mode uses one configured host and port.
- Retryable statuses are `UNAVAILABLE`, `DEADLINE_EXCEEDED`, and
  `RESOURCE_EXHAUSTED`.
- Retries are bounded by `max-attempts`.
- Mutating RPCs should only be retried when duplicate execution is safe or an
  idempotency mechanism is available.

## Classes

- `CustomerApi.java` - stable application-facing interface.
- `CustomerAdapterGrpc.java` - business-to-gRPC adapter.
- `AdapterGrpcBase.java` - owns the channel/stub and common retry behavior.
- `ServiceEndpoint.java` - host/port value object.
- `ServiceEndpointProvider.java` - endpoint lookup abstraction.
- `StaticServiceEndpointProvider.java` - one-host/one-port implementation for
  the interim non-Kubernetes case.
- `GrpcClientConfiguration.java` - Spring bean wiring and example properties.

The generated protobuf classes `CustomerServiceGrpc`, `Customer`, and
`GetCustomerRequest` are expected to come from the project's .proto build.

## Kubernetes configuration

Use the Kubernetes Service DNS name:

    grpc.customer.host=customer-service
    grpc.customer.port=50051

A fully qualified service name also works:

    customer-service.default.svc.cluster.local

## Non-Kubernetes configuration

Point the same client at one fixed server:

    grpc.customer.host=server-a
    grpc.customer.port=50051

No registry, EndpointSlice discovery, or Pod/IP cache is required.

## Notes

The sample uses plaintext gRPC to keep the example minimal. Add TLS configuration
to the ManagedChannelBuilder when encrypted transport is required.

The ManagedChannel is intentionally long-lived and reusable. Do not create a new
channel for every request.
