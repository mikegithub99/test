# JDK 8 Kubernetes gRPC client — no dynamic proxy

This is the simpler variant of the Kubernetes-first design. It removes the Java dynamic proxy entirely.

## Flow

    Application
        |
    CustomerApi
        |
    CustomerApiAdapter
        |
    GrpcClientManager
        |
    one long-lived gRPC client/channel
        |
    customer-service:50051
        |
    Kubernetes Service
        |
    ready Pods

The application uses a normal Java interface and an explicit adapter. There is no InvocationHandler, Method.invoke(), or proxy-specific Object-method handling.

Kubernetes owns backend endpoint selection. The Java application does not query Kubernetes, watch EndpointSlices, track Pod IDs, maintain an instance registry, or replace the channel after ordinary RPC failures.

## Manager behavior

The manager lazily creates one gRPC client/channel per logical service and reuses it. For UNAVAILABLE, DEADLINE_EXCEEDED, and RESOURCE_EXHAUSTED it retries the operation on the same client/channel up to maxAttempts.

Non-retryable failures are propagated. Mutating RPCs should only be retried when duplicate execution is safe or an idempotency mechanism is used.

## Configuration

Kubernetes:

    grpc:
      customer:
        host: customer-service
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

Interim non-Kubernetes:

    grpc:
      customer:
        host: server-a
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

The library is Spring-independent.

## Why this version

Use this version when the dynamic proxy does not provide enough value to justify reflection. The explicit adapter is easier to debug, step through, and understand.

## Production notes

The sample channel uses plaintext. Add TLS configuration at channel construction for production.
