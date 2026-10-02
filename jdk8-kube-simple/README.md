# JDK 8 Kubernetes-oriented gRPC client

This folder contains only the classes needed for the simplified design and is source-compatible with JDK 8.

## Design

    Application API
          |
      dynamic proxy
          |
    GrpcClientManager
          |
    one long-lived channel/client
          |
    customer-service:50051
          |
    Kubernetes Service
          |
       ready Pods

Kubernetes owns Service endpoint selection. The Java application does not query Kubernetes, watch EndpointSlices, track Pod IDs, maintain an instance registry, or replace channels after ordinary RPC failures.

The interim non-Kubernetes mode uses exactly one configured host and port, such as server-a:50051.

## Classes

- ServiceEndpoint: immutable logical service name and gRPC target.
- ServiceEndpointProvider: supplies the configured target for a logical service.
- GrpcClientFactory: creates the generated gRPC stub/client from a channel.
- GrpcClientManager: owns one long-lived channel/client per logical service and performs bounded retry.
- GrpcApiProxyFactory: exposes a stable application API while delegating to the generated gRPC client.

There is deliberately no separate registry client, EndpointSlice watcher, recovery-policy abstraction, instance-exclusion model, or unused static endpoint implementation.

## Retry

The manager retries the same client/channel for:

- UNAVAILABLE
- DEADLINE_EXCEEDED
- RESOURCE_EXHAUSTED

Retries are bounded by maxAttempts. Non-retryable failures are propagated. Mutating RPCs should only be retried when duplicate execution is safe or an idempotency mechanism is used.

The sample channel uses plaintext to keep the example focused. Add TLS configuration at channel construction for production.

## Example configuration

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

The library is Spring-independent, so the Spring Boot application can bind these values and provide a ServiceEndpointProvider.
