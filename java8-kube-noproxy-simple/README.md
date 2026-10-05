# JDK 8 Kubernetes gRPC client — simple, no proxy

This variant keeps the design intentionally small.

- Spring owns the singleton `GrpcClientManager`.
- `CustomerApiAdapter` is the explicit business-to-gRPC adapter.
- The generated gRPC stub is bound directly in the adapter; there is no `CustomerGrpcClient` wrapper.
- `GrpcClientManager` lazily caches one long-lived client/stub and channel per logical service.
- Kubernetes owns backend endpoint selection through the Service DNS name.
- Retryable RPC failures are retried on the same cached client/channel.
- No Java dynamic proxy, `InvocationHandler`, EndpointSlice watcher, Pod registry, Pod-ID tracking, or channel replacement.

## Flow

    Application
        |
    CustomerApi
        |
    CustomerApiAdapter
        |
    GrpcClientManager (Spring singleton)
        |
    generated gRPC stub + ManagedChannel
        |
    customer-service:50051
        |
    Kubernetes Service
        |
    ready Pods

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

## Spring wiring

    @Bean(destroyMethod = "close")
    GrpcClientManager grpcClientManager(ServiceEndpointProvider endpoints, GrpcProperties p) {
        return new GrpcClientManager(
            endpoints,
            p.getClient().getMaxAttempts(),
            p.getClient().getRetryDelay());
    }

    @Bean
    CustomerApi customerApi(GrpcClientManager manager) {
        return new CustomerApiAdapter(manager);
    }

## Generated stub binding

The application-specific generated stub now lives directly in `CustomerApiAdapter`.

Replace the placeholder factory with your generated stub, for example:

    return CustomerServiceGrpc.newBlockingStub(channel);

Then build the generated request and invoke the stub in `getCustomer`.

## Retry behavior

The manager retries `UNAVAILABLE`, `DEADLINE_EXCEEDED`, and `RESOURCE_EXHAUSTED` up to `maxAttempts`.

Do not blindly retry non-idempotent mutations unless duplicate execution is safe or an idempotency mechanism is available.

## Production

The sample uses plaintext for simplicity. Add TLS configuration when constructing the channel for production.