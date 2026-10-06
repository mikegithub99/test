# java8-min5

Minimal Spring Boot / Java 8 gRPC client pattern for Kubernetes and a simple
non-Kubernetes deployment.

## Design

- The application talks to a stable business interface: `CustomerApi`.
- `CustomerAdapterGrpc` is the explicit adapter to the generated gRPC stub.
- `AdapterGrpcBase<T>` owns the long-lived gRPC channel and the generated stub
  of type `T`.
- The adapter is intentionally thin: it builds the service-specific protobuf
  request and calls `getService().myMethod(...)`.
- A dynamic retry proxy wraps the adapter. Retry is behavior around the adapter,
  not part of the gRPC stub holder.
- The proxy works for arbitrary interface arguments and return types, including
  `void`.
- Spring creates the final application-facing proxy as a singleton, so the
  adapter's channel/stub is long-lived and reused across concurrent calls.
- There is no `GrpcClientManager`, registry, EndpointSlice discovery, Pod/IP
  cache, or application-managed backend selection.
- Kubernetes uses the normal Service DNS name and Service port, for example
  `customer-service:50051`. Kubernetes owns backend Pod selection.
- Non-Kubernetes mode uses the same host/port configuration with a fixed server.
- Retryable statuses are `UNAVAILABLE`, `DEADLINE_EXCEEDED`, and
  `RESOURCE_EXHAUSTED`.
- Retries are bounded by `max-attempts`.
- Mutating RPCs should only be retried when duplicate execution is safe or an
  idempotency mechanism is available.

## Classes

- `CustomerApi.java` - stable application-facing interface.
- `CustomerAdapterGrpc.java` - service-specific business-to-gRPC adapter.
- `AdapterGrpcBase.java` - owns the channel and generated stub of generic type
  `T`.
- `GrpcRetryProxy.java` - dynamic proxy that adds bounded retry behavior
  around an adapter delegate.
- `GrpcClientConfiguration.java` - creates the adapter and wraps it with the
  proxy as a Spring singleton.

The generated protobuf classes `CustomerServiceGrpc`, `Customer`, and
`GetCustomerRequest` are expected to come from the project's .proto build.

## Kubernetes configuration

Use the Kubernetes Service DNS name and the Service port:

    grpc:
      customer:
        host: customer-service
        port: 50051

The host is the Kubernetes Service DNS name. The port is still required because
a normal DNS A/AAAA lookup gives the service address, not the TCP port used by
the gRPC connection.

A fully qualified Service DNS name also works:

    customer-service.default.svc.cluster.local

Kubernetes then routes the connection to one of the Service's backend Pods.

## Non-Kubernetes configuration

Point the same client at one fixed server:

    grpc:
      customer:
        host: server-a
        port: 50051

No registry or application-side endpoint discovery is required.

## Why the port is still configured in Kubernetes

The DNS name and the port have different jobs:

- DNS answers: "Where is customer-service?"
- The port answers: "Which TCP port should the gRPC client connect to?"

Kubernetes Service discovery normally provides the Service address through DNS.
The Service also has a port, but that port is not automatically supplied to a
client performing an ordinary hostname lookup.

It is possible to design around this with DNS SRV records or another URI/service
discovery convention, but that adds complexity that this minimal design does
not need.

## Notes

The sample uses plaintext gRPC to keep the example minimal. Add TLS configuration
to the ManagedChannelBuilder when encrypted transport is required.

The ManagedChannel is intentionally long-lived and reusable. Do not create a new
channel for every request.

The retry proxy unwraps InvocationTargetException so callers see the original
exception thrown by the adapter.

Retries apply to the adapter invocation. Do not blindly retry non-idempotent
mutating operations unless duplicate execution is safe or an idempotency key or
equivalent mechanism is used.
