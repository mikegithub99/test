# java8-min6

Minimal Java 8 / Spring gRPC client design with a generic adapter base and a retry policy supplied by Spring configuration.

## Design

- `CustomerApi` is the stable application-facing interface.
- `CustomerAdapterGrpc` is the service-specific adapter. It builds protobuf requests and invokes `getService().method(...)`.
- `AdapterGrpcBase<T>` owns the long-lived `ManagedChannel` and typed generated gRPC stub.
- `GrpcRetryPolicy` contains all retry behavior configuration in one object.
- `GrpcRetryProxy` wraps an adapter delegate and applies the policy around the adapter invocation. It supports arbitrary method arguments, return types, and `void` methods because it works at the Java interface boundary.
- `GrpcProperties` is the single Spring configuration object. It contains the retry policy and a map of service endpoint definitions.
- No `GrpcClientManager`, endpoint provider, registry, EndpointSlice discovery, or Pod/IP cache is used.
- Spring creates the adapter/proxy as a singleton.

## Configuration

This version deliberately uses standard `.properties` key/value configuration rather than YAML.

Kubernetes:

    grpc.services.customer.host=customer-service
    grpc.services.customer.port=50051

Non-Kubernetes:

    grpc.services.customer.host=server-a
    grpc.services.customer.port=50051

Global retry policy:

    grpc.retry.max-attempts=3
    grpc.retry.delay-millis=100
    grpc.retry.retryable-statuses=UNAVAILABLE,DEADLINE_EXCEEDED,RESOURCE_EXHAUSTED

The same retry policy is shared by all gRPC API proxies created from this configuration.

## Why Kubernetes still has a port

`customer-service` is the DNS name/address of the Kubernetes Service. A normal DNS A/AAAA lookup supplies the address, not the TCP port for the gRPC connection.

The gRPC client therefore connects to:

    customer-service:50051

Kubernetes Service configuration maps the Service port to its backend target port and selects backend Pods. The Java client does not discover or cache those Pods.

DNS SRV or another service-discovery convention could encode port information, but using that would add complexity that this minimal design does not need.

## Classes

- `CustomerApi.java` - application contract.
- `CustomerAdapterGrpc.java` - customer-specific protobuf/stub mapping.
- `AdapterGrpcBase.java` - generic channel/stub owner.
- `GrpcRetryPolicy.java` - immutable retry policy.
- `GrpcRetryProxy.java` - dynamic proxy implementing the retry policy.
- `GrpcProperties.java` - Spring-bound map of service endpoints plus one retry policy.
- `GrpcClientConfiguration.java` - creates the singleton adapter and proxy.

Generated protobuf classes such as `CustomerServiceGrpc`, `Customer`, and `GetCustomerRequest` are expected to be produced by the project's protobuf build.

## Retry guidance

Retrying is limited to `UNAVAILABLE`, `DEADLINE_EXCEEDED`, and `RESOURCE_EXHAUSTED` by default. Mutating operations should only be retried when duplicate execution is safe or an idempotency mechanism is available.

The policy is global by design. If a future service genuinely requires a different retry policy, that should be an explicit per-service policy rather than duplicating retry parameters across every adapter constructor.

## Lifecycle

The channel is intentionally long-lived. The adapter is a Spring singleton, so requests reuse the same channel/stub. The base exposes `shutdown()` for explicit application lifecycle integration when required.
