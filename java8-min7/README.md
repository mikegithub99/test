# java8-min7

Java 8 gRPC adapter design using a small dynamic-proxy/interceptor architecture inspired by the structure described by OpenCredo in *New Tricks with Dynamic Proxies in Java 8 (part 2)*. This implementation is independently written for this project; it does not copy their source code.

## Design

The proxy separates method interpretation from method execution and lets cross-cutting behavior be composed as interceptors. Method handlers are cached because the Spring-created service proxy is long-lived. The delegate is the concrete gRPC adapter.

OpenCredo describes this separation as a `MethodInterpreter` producing a `MethodCallHandler`, with interceptor wrappers and cached interpretations. See: https://opencredo.com/blogs/dynamic-proxies-java-part-2

Our layers are:

    CustomerApi
        -> Dynamic proxy
           -> retry interceptor
           -> delegate binding
        -> CustomerAdapterGrpc
        -> AdapterGrpcBase<T>
        -> generated CustomerServiceGrpc stub
        -> Kubernetes Service / fixed host

### Responsibilities

- `CustomerApi`: application-facing interface.
- `CustomerAdapterGrpc`: service-specific mapping between Java API arguments and protobuf requests/responses.
- `AdapterGrpcBase<T>`: owns the long-lived `ManagedChannel` and typed generated stub.
- `GrpcProxy`: owns the reusable proxy/interpreter/interceptor machinery.
- `GrpcRetryPolicy`: one retry policy object shared by all proxies.
- `GrpcProperties`: Spring-bound endpoint map and retry properties.
- `GrpcClientConfiguration`: creates the singleton adapter and proxy.

The adapter does not contain retry code. It can simply build a specific request and call `getService().method(...)`.

## Properties

Use ordinary Spring `application.properties`, not YAML:

    grpc.services.customer.host=customer-service
    grpc.services.customer.port=50051

    grpc.retry.max-attempts=3
    grpc.retry.delay-millis=100
    grpc.retry.retryable-statuses=UNAVAILABLE,DEADLINE_EXCEEDED,RESOURCE_EXHAUSTED

For the interim non-Kubernetes case, change only the service endpoint:

    grpc.services.customer.host=server-a
    grpc.services.customer.port=50051

The retry policy remains centralized and applies consistently to all proxied adapters.

## Kubernetes

The Kubernetes Service DNS name supplies the address. The gRPC client still needs a TCP port. A normal DNS A/AAAA lookup does not return the port, so the Service name and Service port are configured separately. Kubernetes then selects a backend Pod for the Service connection.

No EndpointSlice discovery, Pod IP cache, registry, or application-side load balancing is used.

## Retry

The retry interceptor invokes the delegate and retries only configured gRPC status codes, up to `max-attempts`. It supports arbitrary interface argument lists and arbitrary return values, including `void`, because it wraps the method invocation rather than modeling a return type with a generic Java method.

Mutating RPCs should only be retried when duplicate execution is safe or an idempotency mechanism is available.

## Java 8

The implementation uses Java 8-compatible reflection, `Proxy`, lambdas, and concurrency primitives. It does not depend on newer Java language features.

## Generated classes

The sample expects generated protobuf classes named `CustomerServiceGrpc`, `Customer`, and `GetCustomerRequest` to be supplied by the project's protobuf build.
